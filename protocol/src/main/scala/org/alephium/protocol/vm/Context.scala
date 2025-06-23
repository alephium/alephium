// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.vm

import scala.collection.mutable.ArrayBuffer

import org.alephium.crypto.Byte64
import org.alephium.io.IOError
import org.alephium.protocol.config.{ConsensusConfigs, GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.nodeindexes.TxOutputLocator
import org.alephium.util.{discard, AVector, EitherF, Math, TimeStamp, U256}

//scalastyle:off file.size.limit
final case class BlockEnv(
    chainIndex: ChainIndex,
    networkId: NetworkId,
    timeStamp: TimeStamp,
    target: Target,
    blockId: Option[BlockHash],
    hardFork: HardFork,
    newOutputRefCache: Option[scala.collection.mutable.HashMap[AssetOutputRef, AssetOutput]]
) {
  @inline def getHardFork(): HardFork = hardFork

  def addOutputRefFromTx(unsignedTx: UnsignedTransaction): Unit = {
    assume(hardFork.isRhoneEnabled() && newOutputRefCache.isDefined)
    newOutputRefCache.foreach { cache =>
      unsignedTx.fixedOutputRefs.foreachWithIndex { case (outputRef, outputIndex) =>
        cache.addOne(outputRef -> unsignedTx.fixedOutputs(outputIndex))
      }
    }
  }
}
object BlockEnv {
  def apply(
      chainIndex: ChainIndex,
      networkId: NetworkId,
      timeStamp: TimeStamp,
      target: Target,
      blockId: Option[BlockHash]
  )(implicit networkConfig: NetworkConfig): BlockEnv =
    BlockEnv(
      chainIndex,
      networkId,
      timeStamp,
      target,
      blockId,
      networkConfig.getHardFork(timeStamp),
      None
    )

  def from(chainIndex: ChainIndex, header: BlockHeader)(implicit
      networkConfig: NetworkConfig
  ): BlockEnv =
    BlockEnv(
      chainIndex,
      networkConfig.networkId,
      header.timestamp,
      header.target,
      Some(header.hash),
      networkConfig.getHardFork(header.timestamp),
      Some(scala.collection.mutable.HashMap.empty)
    )

  def mockup(
      groupIndex: GroupIndex,
      blockHash: BlockHash,
      blockTimeStamp: TimeStamp
  )(implicit networkConfig: NetworkConfig, consensusConfigs: ConsensusConfigs): BlockEnv = {
    val consensusConfig = consensusConfigs.getConsensusConfig(blockTimeStamp)
    BlockEnv(
      ChainIndex(groupIndex, groupIndex),
      networkConfig.networkId,
      blockTimeStamp,
      consensusConfig.maxMiningTarget,
      Some(blockHash)
    )
  }
}

sealed trait TxEnv {
  def txId: TransactionId
  def signatures: Stack[Byte64]
  def prevOutputs: AVector[AssetOutput]
  def fixedOutputs: AVector[AssetOutput]
  def gasPrice: GasPrice
  def gasAmount: GasBox
  def gasFeeUnsafe: U256

  def isEntryMethodPayable: Boolean
  def txIndex: Int // 0 for tx simulation

  def isTxCaller(lockupScript: LockupScript): Boolean =
    prevOutputs.exists(_.lockupScript == lockupScript)
}

object TxEnv {
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def apply(
      tx: TransactionAbstract,
      prevOutputs: AVector[AssetOutput],
      signatures: Stack[Byte64],
      txIndex: Int
  ): TxEnv = Default(tx, prevOutputs, signatures, txIndex)

  def dryrun(
      tx: TransactionAbstract,
      prevOutputs: AVector[AssetOutput],
      signatures: Stack[Byte64]
  ): TxEnv = apply(tx, prevOutputs, signatures, 0)

  def mockup(txId: TransactionId, prevOutputs: AVector[AssetOutput]): TxEnv = {
    mockup(
      txId,
      Stack.popOnly(AVector.empty[Byte64]),
      prevOutputs,
      AVector.empty[AssetOutput],
      nonCoinbaseMinGasPrice,
      maximalGasPerTx,
      isEntryMethodPayable = true
    )
  }

  def mockup(
      txId: TransactionId,
      signatures: Stack[Byte64],
      prevOutputs: AVector[AssetOutput],
      fixedOutputs: AVector[AssetOutput],
      gasPrice: GasPrice,
      gasAmount: GasBox,
      isEntryMethodPayable: Boolean
  ): TxEnv =
    Mockup(
      txId,
      signatures,
      prevOutputs,
      fixedOutputs,
      gasPrice,
      gasAmount,
      gasPrice * gasAmount,
      isEntryMethodPayable,
      0
    )

  final case class Default(
      tx: TransactionAbstract,
      prevOutputs: AVector[AssetOutput],
      signatures: Stack[Byte64],
      txIndex: Int
  ) extends TxEnv {
    def txId: TransactionId                = tx.id
    def fixedOutputs: AVector[AssetOutput] = tx.unsigned.fixedOutputs
    def gasPrice: GasPrice                 = tx.unsigned.gasPrice
    def gasAmount: GasBox                  = tx.unsigned.gasAmount
    def gasFeeUnsafe: U256                 = tx.gasFeeUnsafe
    def isEntryMethodPayable: Boolean      = tx.isEntryMethodPayable
  }

  final case class Mockup(
      txId: TransactionId,
      signatures: Stack[Byte64],
      prevOutputs: AVector[AssetOutput],
      fixedOutputs: AVector[AssetOutput],
      gasPrice: GasPrice,
      gasAmount: GasBox,
      gasFeeUnsafe: U256,
      isEntryMethodPayable: Boolean,
      txIndex: Int
  ) extends TxEnv
}

final case class LogConfig(
    enabled: Boolean,
    indexByTxId: Boolean,
    indexByBlockHash: Boolean,
    contractAddresses: Option[AVector[Address.Contract]]
) {
  def logContractEnabled(contractAddress: Address.Contract): Boolean = {
    enabled && {
      contractAddresses.isEmpty ||                          // allow all contracts
      contractAddresses.exists(_.contains(contractAddress)) // allow the contract
    }
  }
}

object LogConfig {
  def disabled(): LogConfig = {
    LogConfig(
      enabled = false,
      indexByTxId = false,
      indexByBlockHash = false,
      contractAddresses = None
    )
  }

  def allEnabled(): LogConfig = {
    LogConfig(enabled = true, indexByTxId = true, indexByBlockHash = true, contractAddresses = None)
  }
}

final case class NodeIndexesConfig(
    txOutputRefIndex: Boolean,
    subcontractIndex: Boolean
)

final class TestEnv(
    val sourcePosIndex: Int,
    val expectedErrorCode: Option[Int],
    val testFrame: Frame[_],
    private var _exeFailure: Option[ExeFailure]
) {
  def setExeFailure(error: ExeFailure): Unit = {
    if (_exeFailure.isEmpty) _exeFailure = Some(error)
  }
  def exeFailure: Option[ExeFailure] = _exeFailure
}
object TestEnv {
  def apply(errorCode: Int, expectedErrorCode: Option[Int], testFrame: Frame[_]): TestEnv =
    new TestEnv(errorCode, expectedErrorCode, testFrame, None)
}

trait TestContext {
  private var _testEnv: Option[TestEnv] = None
  def initTestEnv(errorCode: Int, expectedErrorCode: Option[Int], testFrame: Frame[_]): Unit = {
    assume(_testEnv.isEmpty)
    _testEnv = Some(TestEnv(errorCode, expectedErrorCode, testFrame))
  }
  def resetTestEnv(): Unit = {
    assume(_testEnv.isDefined)
    _testEnv = None
  }
  def testEnvOpt: Option[TestEnv] = _testEnv

  def setTestError(error: ExeFailure): Unit = {
    assume(_testEnv.isDefined)
    _testEnv.foreach(_.setExeFailure(error))
  }
}

trait StatelessContext extends CostStrategy with TestContext {
  def networkConfig: NetworkConfig
  def groupConfig: GroupConfig
  def blockEnv: BlockEnv

  @inline def getHardFork(): HardFork = blockEnv.getHardFork()
  def checkLemanHardFork[C <: StatelessContext](instr: Instr[C]): ExeResult[Unit] = {
    if (getHardFork().isLemanEnabled()) {
      okay
    } else {
      failed(InactiveInstr(instr))
    }
  }

  def checkRhoneHardFork[C <: StatelessContext](instr: Instr[C]): ExeResult[Unit] = {
    if (getHardFork().isRhoneEnabled()) {
      okay
    } else {
      failed(InactiveInstr(instr))
    }
  }

  def checkDanubeHardFork[C <: StatelessContext](instr: Instr[C]): ExeResult[Unit] = {
    if (getHardFork().isDanubeEnabled()) {
      okay
    } else {
      failed(InactiveInstr(instr))
    }
  }

  def txEnv: TxEnv
  def getInitialBalances(): ExeResult[MutBalances]
  def setTxCallerBalance(callerBalance: MutBalanceState): Unit
  def getTxCallerBalance(): ExeResult[MutBalanceState]

  def writeLog(
      contractIdOpt: Option[ContractId],
      fields: AVector[Val],
      systemEvent: Boolean
  ): ExeResult[Unit]

  def txId: TransactionId       = txEnv.txId
  def signatures: Stack[Byte64] = txEnv.signatures

  def getTxPrevOutput(indexRaw: Val.U256): ExeResult[AssetOutput] = {
    indexRaw.v.toInt
      .flatMap(txEnv.prevOutputs.get)
      .toRight(Right(InvalidTxInputIndex(indexRaw.v.v)))
  }

  def getTxInputAddressAt(indexRaw: Val.U256): ExeResult[Val.Address] = {
    getTxPrevOutput(indexRaw).map(output => Val.Address(output.lockupScript))
  }

  def getUniqueTxInputAddress(): ExeResult[Val.Address] = {
    for {
      _ <-
        if (getHardFork().isLemanEnabled()) okay else failed(PartiallyActiveInstr(CallerAddress))
      _       <- chargeGas(GasUniqueAddress.gas(txEnv.prevOutputs.length))
      address <- _getUniqueTxInputAddress()
    } yield address
  }

  def getFirstTxInputAddress(): ExeResult[Val.Address] = {
    getTxInputAddressAt(Val.U256(U256.Zero))
  }

  private def _getUniqueTxInputAddress(): ExeResult[Val.Address] = {
    txEnv.prevOutputs.headOption match {
      case Some(firstInput) =>
        if (txEnv.prevOutputs.tail.forall(_.lockupScript == firstInput.lockupScript)) {
          Right(Val.Address(firstInput.lockupScript))
        } else {
          val addresses = txEnv.prevOutputs.map { v =>
            Address.Asset(v.lockupScript)
          }.toSet
          failed(TxInputAddressesAreNotIdentical(addresses))
        }
      case None =>
        failed(NoTxInput)
    }
  }

  lazy val allInputAddresses: AVector[Address] = {
    var addresses = AVector.ofCapacity[Address](1) // One input address in most cases
    txEnv.prevOutputs.foreach { output =>
      val address = Address.Asset(output.lockupScript)
      if (!addresses.contains(address)) {
        addresses = addresses :+ address
      }
    }
    addresses
  }

  def chargeGasWithSizeLeman(gasFormula: UpgradedGasFormula, size: Int): ExeResult[Unit] = {
    if (getHardFork().isLemanEnabled()) {
      this.chargeGas(gasFormula.gas(size))
    } else {
      this.chargeGas(gasFormula.gasDeprecated(size))
    }
  }
}

object StatelessContext {
  def apply(
      blockEnv: BlockEnv,
      txEnv: TxEnv,
      txGas: GasBox
  )(implicit networkConfig: NetworkConfig, groupConfig: GroupConfig): StatelessContext =
    new Impl(blockEnv, txEnv, txGas)

  final class Impl(
      val blockEnv: BlockEnv,
      val txEnv: TxEnv,
      var gasRemaining: GasBox
  )(implicit val networkConfig: NetworkConfig, val groupConfig: GroupConfig)
      extends StatelessContext {
    def getInitialBalances(): ExeResult[MutBalances]             = failed(ExpectNonPayableMethod)
    def setTxCallerBalance(callerBalance: MutBalanceState): Unit = ???
    def getTxCallerBalance(): ExeResult[MutBalanceState]         = failed(ExpectNonPayableMethod)
    def writeLog(
        contractIdOpt: Option[ContractId],
        fields: AVector[Val],
        systemEvent: Boolean
    ): ExeResult[Unit] = okay
  }
}

// scalastyle:off number.of.methods
trait StatefulContext extends StatelessContext with ContractPool {
  def worldState: WorldState.Staging

  def logConfig: LogConfig

  var txCallerBalance: Option[MutBalanceState] = None
  var totalAutoFundDustAmount: U256            = U256.Zero

  def setTxCallerBalance(callerBalance: MutBalanceState): Unit = {
    this.txCallerBalance = Some(callerBalance)
  }

  def getTxCallerBalance(): ExeResult[MutBalanceState] = {
    txCallerBalance match {
      case Some(balance) => Right(balance)
      case None          => failed(TxCallerBalanceNotAvailable)
    }
  }

  def outputBalances: MutBalances

  lazy val generatedOutputs: ArrayBuffer[TxOutput] = ArrayBuffer.empty

  def nextOutputIndex: Int

  def nextContractOutputRef(output: ContractOutput): ContractOutputRef =
    ContractOutputRef.from(txId, output, nextOutputIndex)

  def generateOutput(output: TxOutput): ExeResult[Unit] = {
    output match {
      case contractOutput @ ContractOutput(_, LockupScript.P2C(contractId), _) =>
        if (getHardFork().isLemanEnabled()) {
          generateContractOutputLeman(contractId, contractOutput)
        } else {
          generateContractOutputSimple(
            contractId,
            contractOutput,
            nextOutputIndex,
            generatedOutputs.addOne
          )
        }
      case assetOutput: AssetOutput =>
        if (getHardFork().isLemanEnabled()) {
          generateAssetOutputLeman(assetOutput)
        } else {
          generateAssetOutputSimple(assetOutput)
        }
    }
  }

  def generateContractOutputLeman(
      contractId: ContractId,
      contractOutput: ContractOutput
  ): ExeResult[Unit] = {
    val inputIndex = contractInputs.indexWhere(_._2.lockupScript.contractId == contractId)
    if (inputIndex == -1) {
      failed(ContractAssetUnloaded(Address.contract(contractId)))
    } else {
      if (getHardFork().isDanubeEnabled()) {
        generateContractOutputDanube(contractId, contractOutput, inputIndex)
      } else {
        generateContractOutputLeman(contractId, contractOutput, inputIndex)
      }
    }
  }

  def generateContractOutputLeman(
      contractId: ContractId,
      contractOutput: ContractOutput,
      inputIndex: Int
  ): ExeResult[Unit] = {
    val (_, input) = contractInputs(inputIndex)
    if (contractOutput == input) {
      contractInputs.remove(inputIndex)
      markAssetFlushed(contractId)
    } else {
      generateContractOutputSimple(
        contractId,
        contractOutput,
        nextOutputIndex,
        generatedOutputs.addOne
      )
    }
  }

  def generateContractOutputDanube(
      contractId: ContractId,
      contractOutput: ContractOutput,
      inputIndex: Int
  ): ExeResult[Unit] = {
    val lockupScript         = LockupScript.p2c(contractId)
    val generatedOutputIndex = generatedOutputs.indexWhere(_.lockupScript == lockupScript)
    if (generatedOutputIndex == -1) {
      generateContractOutputLeman(contractId, contractOutput, inputIndex)
    } else {
      // Newly created contract
      val outputIndex = txEnv.fixedOutputs.length + generatedOutputIndex
      contractInputs.remove(inputIndex)
      generateContractOutputSimple(
        contractId,
        contractOutput,
        outputIndex,
        generatedOutputs(generatedOutputIndex) = _
      )
    }
  }

  def generateContractOutputSimple(
      contractId: ContractId,
      contractOutput: ContractOutput,
      contractOutputIndex: Int,
      updateGeneratedOutput: TxOutput => Unit
  ): ExeResult[Unit] = {
    val outputRef       = ContractOutputRef.from(txId, contractOutput, contractOutputIndex)
    val txOutputLocator = TxOutputLocator.from(blockEnv, txEnv, contractOutputIndex)
    for {
      _ <- chargeGeneratedOutput()
      _ <- updateContractAsset(contractId, outputRef, contractOutput, txOutputLocator)
    } yield {
      updateGeneratedOutput(contractOutput)
    }
  }

  def generateAssetOutputLeman(assetOutput: AssetOutput): ExeResult[Unit] = {
    assume(assetOutput.tokens.length <= maxTokenPerAssetUtxo)
    generateAssetOutputSimple(assetOutput)
  }

  def generateAssetOutputSimple(assetOutput: AssetOutput): ExeResult[Unit] = {
    generatedOutputs.addOne(assetOutput)
    chargeGeneratedOutput()
  }

  def outputRemainingContractAssetsForRhone(): ExeResult[Unit] = {
    if (getHardFork().isRhoneEnabled()) {
      EitherF.foreachTry(assetStatus) {
        case (contractId, ContractPool.ContractAssetInUsing(balances)) =>
          outputBalances.add(LockupScript.p2c(contractId), balances) match {
            case Some(_) => okay
            case None    => failed(InvalidBalances)
          }
        case _ => okay
      }
    } else {
      okay
    }
  }

  def chainCallerOutputs(frameBalanceStateOpt: Option[MutBalanceState]): ExeResult[Unit] = {
    frameBalanceStateOpt match {
      case Some(frameBalanceState) => chainCallerOutputs(frameBalanceState)
      case None                    => okay
    }
  }

  def chainCallerOutputs(frameBalanceState: MutBalanceState): ExeResult[Unit] = {
    EitherF.foreachTry(allInputAddresses.toIterable) { caller =>
      val success = outputBalances
        .useForChainedInput(caller.lockupScript)
        .forall(outputs => frameBalanceState.remaining.add(caller.lockupScript, outputs).nonEmpty)

      if (success) okay else failed(ChainCallerOutputsFailed(caller))
    }
  }

  def contractExists(contractId: ContractId): ExeResult[Boolean] = {
    worldState
      .contractExists(contractId)
      .left
      .flatMap(error => ioFailed(IOErrorLoadContract(error)))
  }

  def createContract(
      contractId: ContractId,
      code: StatefulContract.HalfDecoded,
      initialImmFields: AVector[Val],
      initialBalances: MutBalancesPerLockup,
      initialMutFields: AVector[Val],
      tokenIssuanceInfo: Option[TokenIssuance.Info]
  ): ExeResult[ContractId] = {
    tokenIssuanceInfo.foreach { info =>
      info.transferTo match {
        case Some(transferTo) =>
          outputBalances.addToken(transferTo, TokenId.from(contractId), info.amount.v)
        case None =>
          initialBalances.addToken(TokenId.from(contractId), info.amount.v)
      }
    }

    val contractOutput = ContractOutput(
      initialBalances.attoAlphAmount,
      LockupScript.p2c(contractId),
      initialBalances.tokenVector
    )
    val outputRef = nextContractOutputRef(contractOutput)

    for {
      _ <-
        worldState.getContractState(contractId) match {
          case Left(_: IOError.KeyNotFound) =>
            okay
          case Left(otherIOError) =>
            ioFailed(IOErrorLoadContract(otherIOError))
          case Right(_) =>
            Left(Right(ContractAlreadyExists(Address.contract(contractId))))
        }
      _ <- code.check(initialImmFields, initialMutFields)
      _ <- createContract(
        contractId,
        code,
        initialImmFields,
        initialMutFields,
        outputRef,
        contractOutput
      )
      _ <- cacheNewContractIfNecessary(contractId)
    } yield {
      contractId
    }
  }

  private def createContract(
      contractId: ContractId,
      code: StatefulContract.HalfDecoded,
      initialImmFields: AVector[Val],
      initialMutFields: AVector[Val],
      outputRef: ContractOutputRef,
      contractOutput: ContractOutput
  ): ExeResult[Unit] = {
    val result = worldState
      .createContractUnsafe(
        contractId,
        code,
        initialImmFields,
        initialMutFields,
        outputRef,
        contractOutput,
        getHardFork().isLemanEnabled(),
        txId,
        TxOutputLocator.from(blockEnv, txEnv, nextOutputIndex)
      )

    result match {
      case Right(_) => Right(discard(generatedOutputs.addOne(contractOutput)))
      case Left(e)  => ioFailed(IOErrorUpdateState(e))
    }
  }

  def destroyContract(
      contractId: ContractId,
      contractAssets: MutBalancesPerLockup,
      refundAddress: LockupScript
  ): ExeResult[Unit] = {
    for {
      _ <- outputBalances.add(refundAddress, contractAssets).toRight(Right(InvalidBalances))
      _ <- removeContract(contractId)
    } yield ()
  }

  def migrateContract(
      contractId: ContractId,
      obj: ContractObj[StatefulContext],
      newContractCode: StatefulContract,
      newImmFieldsOpt: Option[AVector[Val]],
      newMutFieldsOpt: Option[AVector[Val]]
  ): ExeResult[Unit] = {
    val newImmFields = newImmFieldsOpt.getOrElse(AVector.from(obj.immFields))
    val newMutFields = newMutFieldsOpt.getOrElse(AVector.from(obj.mutFields))
    for {
      _ <- newContractCode.check(newImmFields, newMutFields)
      _ <- chargeFieldSize(newImmFields.toIterable ++ newMutFields.toIterable)
      succeeded <- worldState
        .migrateContractLemanUnsafe(contractId, newContractCode, newImmFields, newMutFields)
        .left
        .map(e => Left(IOErrorMigrateContract(e)))
      _ <- if (succeeded) okay else failed(UnableToMigratePreLemanContract)
    } yield {
      blockContractLoad(contractId)
      removeContractFromCache(contractId)
    }
  }

  def updateContractAsset(
      contractId: ContractId,
      outputRef: ContractOutputRef,
      output: ContractOutput,
      txOutputLocator: Option[TxOutputLocator]
  ): ExeResult[Unit] = {
    for {
      _ <- markAssetFlushed(contractId)
      _ <- worldState
        .updateContract(
          contractId,
          outputRef,
          output,
          txId,
          txOutputLocator
        )
        .left
        .map(e => Left(IOErrorUpdateState(e)))
    } yield ()
  }

  def writeLog(
      contractIdOpt: Option[ContractId],
      fields: AVector[Val],
      systemEvent: Boolean
  ): ExeResult[Unit] = {
    val result = (blockEnv.blockId, contractIdOpt) match {
      case (Some(blockId), Some(contractId))
          if systemEvent || logConfig.logContractEnabled(Address.contract(contractId)) =>
        worldState.logState.putLog(
          blockId,
          txId,
          contractId,
          fields,
          logConfig.indexByTxId,
          logConfig.indexByBlockHash
        )
      case _ => Right(())
    }

    result.left.map(e => Left(IOErrorWriteLog(e)))
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def writeSubContractIndexes(
      parentContract: Option[ContractId],
      contractId: ContractId
  ): ExeResult[Unit] = {
    val subContractIndexState = worldState.nodeIndexesState.subContractIndexState
    if (parentContract.nonEmpty && subContractIndexState.nonEmpty) {
      subContractIndexState.get
        .createSubContractIndexes(parentContract.get, contractId)
        .left
        .map(e => Left(IOErrorCreateSubContractIndex(e)))
    } else {
      Right(())
    }
  }

  def outputGeneratedBalances(): ExeResult[Unit] = {
    val hardFork = getHardFork()
    for {
      _ <- EitherF.foreachTry(outputBalances.all) { case (lockupScript, balances) =>
        lockupScript match {
          case l: LockupScript.P2C if !assetStatus.contains(l.contractId) =>
            failed(ContractAssetUnloaded(Address.contract(l.contractId)))
          case _ =>
            for {
              outputs <- balances.toTxOutput(lockupScript, hardFork)
              _       <- outputs.foreachE(output => generateOutput(output))
            } yield ()
        }
      }
    } yield ()
  }

  def cleanBalances(): ExeResult[Unit] = {
    if (getHardFork().isDanubeEnabled()) {
      cleanBalancesDanube()
    } else {
      cleanBalancesPreDanube()
    }
  }

  // outputRemainingContractAssetsForRhone is called before coverUtxoMinimalAmounts to check
  // if the contract UTxOs need more ALPH to cover the minimal storage deposit.
  def cleanBalancesDanube(): ExeResult[Unit] = {
    for {
      _ <- reimburseGas()
      _ <- outputRemainingContractAssetsForRhone()
      _ <- coverUtxoMinimalAmounts()
      _ <- flushCallerBalances()
      _ <- outputGeneratedBalances()
      _ <- checkAllAssetsFlushed()
    } yield ()
  }

  def cleanBalancesPreDanube(): ExeResult[Unit] = {
    for {
      _ <- flushCallerBalances()
      _ <- reimburseGas()
      _ <- outputRemainingContractAssetsForRhone()
      _ <- outputGeneratedBalances()
      _ <- checkAllAssetsFlushed()
    } yield ()
  }

  private def coverUtxoMinimalAmounts(): ExeResult[Unit] = {
    if (getHardFork().isDanubeEnabled()) {
      coverUtxoMinimalAmountsDanube()
    } else {
      okay
    }
  }

  private def coverUtxoMinimalAmountsDanube(): ExeResult[Unit] = {
    EitherF.foreachTry(outputBalances.all) { case (lockupScript, outputBalance) =>
      if (txEnv.isTxCaller(lockupScript)) {
        okay
      } else {
        coverUtxoDustAmounts(lockupScript, outputBalance)
      }
    }
  }

  private def coverUtxoDustAmounts(
      lockupScript: LockupScript,
      balances: MutBalancesPerLockup
  ): ExeResult[Unit] = {
    balances.utxoMinimalAlphAmountToCover(lockupScript) match {
      case None => okay
      case Some(alphToCover) =>
        coverExtraAlphAmount(balances, alphToCover)
    }
  }

  def flushCallerBalances(): ExeResult[Unit] = {
    txCallerBalance match {
      case None => okay
      case Some(balances) =>
        val resultOpt = for {
          _ <- outputBalances.merge(balances.approved)
          _ <- outputBalances.merge(balances.remaining)
        } yield ()
        resultOpt match {
          case Some(_) => okay
          case None    => failed(InvalidBalances)
        }
    }
  }

  def reimburseGas(): ExeResult[Unit] = {
    if (getHardFork().isRhoneEnabled() && gasFeePaid > U256.Zero) {
      val totalGasFee = txEnv.gasFeeUnsafe

      assume(totalGasFee >= gasFeePaid) // This should always be true, so we check with assume

      txEnv.prevOutputs.headOption match {
        case Some(firstInput) =>
          outputBalances
            .addAlph(firstInput.lockupScript, gasFeePaid)
            .toRight(Right(InvalidBalances))
        case None =>
          okay
      }
    } else {
      okay
    }
  }

  def coverExtraAlphAmount(
      targetBalance: MutBalancesPerLockup,
      alphToCover: U256
  ): ExeResult[Unit] = {
    for {
      txCallerBalance <- getTxCallerBalance()
      callerAddress   <- getFirstTxInputAddress()
      _ <- coverExtraAlphAmount(
        targetBalance,
        callerAddress.lockupScript,
        txCallerBalance,
        alphToCover
      )
    } yield ()
  }

  private def coverExtraAlphAmount(
      targetBalance: MutBalancesPerLockup,
      caller: LockupScript,
      callerBalance: MutBalanceState,
      alphToCover: U256
  ): ExeResult[Unit] = {
    // First try to cover from remaining balance
    val remainingAfterRemaining = transferAvailableDeposit(
      targetBalance,
      caller,
      callerBalance.remaining,
      alphToCover
    )

    // Then try to cover from approved balance if needed
    val remainingAfterApproved = transferAvailableDeposit(
      targetBalance,
      caller,
      callerBalance.approved,
      remainingAfterRemaining
    )

    if (remainingAfterApproved == U256.Zero) {
      totalAutoFundDustAmount.add(alphToCover) match {
        case Some(amount) =>
          totalAutoFundDustAmount = amount
          okay
        case None => failed(BalanceOverflow)
      }
    } else {
      failed(InsufficientFundsForUTXODustAmount(remainingAfterApproved))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def transferAvailableDeposit(
      targetBalance: MutBalancesPerLockup,
      caller: LockupScript,
      callerBalance: MutBalances,
      alphToCover: U256
  ): U256 = {
    if (alphToCover == U256.Zero) {
      U256.Zero
    } else {
      callerBalance.getBalances(caller) match {
        case Some(balances) =>
          val ableToCover = Math.min(alphToCover, balances.attoAlphAmount)
          // It's safe to use `get` here because:
          // 1. We're only subtracting what's available (ableToCover ≤ balances.attoAlphAmount)
          // 2. We're only adding a positive value to contractBalance
          (for {
            _ <- balances.subAlph(ableToCover)
            _ <- targetBalance.addAlph(ableToCover)
          } yield alphToCover.subUnsafe(ableToCover)).get
        case None => alphToCover
      }
    }
  }
}

object StatefulContext {
  def apply(
      blockEnv: BlockEnv,
      txEnv: TxEnv,
      worldState: WorldState.Staging,
      gasRemaining: GasBox
  )(implicit
      networkConfig: NetworkConfig,
      logConfig: LogConfig,
      groupConfig: GroupConfig
  ): StatefulContext = {
    new Impl(blockEnv, txEnv, worldState, gasRemaining)
  }

  // scalastyle:off parameter.number
  def apply(
      blockEnv: BlockEnv,
      tx: TransactionAbstract,
      gasRemaining: GasBox,
      worldState: WorldState.Staging,
      preOutputs: AVector[AssetOutput],
      txIndex: Int
  )(implicit
      networkConfig: NetworkConfig,
      logConfig: LogConfig,
      groupConfig: GroupConfig
  ): StatefulContext = {
    val txEnv = TxEnv(tx, preOutputs, Stack.popOnly(tx.scriptSignatures), txIndex)
    apply(blockEnv, txEnv, worldState, gasRemaining)
  }
  // scalastyle:on parameter.number

  final class Impl(
      val blockEnv: BlockEnv,
      val txEnv: TxEnv,
      val worldState: WorldState.Staging,
      var gasRemaining: GasBox
  )(implicit
      val networkConfig: NetworkConfig,
      val logConfig: LogConfig,
      val groupConfig: GroupConfig
  ) extends StatefulContext {
    def preOutputs: AVector[AssetOutput] = txEnv.prevOutputs

    def nextOutputIndex: Int = txEnv.fixedOutputs.length + generatedOutputs.length

    /*
     * this should be used only when the tx has passed these checks in validation
     * 1. inputs are not empty
     * 2. gas fee bounds are validated
     */
    @SuppressWarnings(
      Array(
        "org.wartremover.warts.JavaSerializable",
        "org.wartremover.warts.Product",
        "org.wartremover.warts.Serializable"
      )
    )
    def getInitialBalances(): ExeResult[MutBalances] =
      if (txEnv.isEntryMethodPayable) {
        for {
          balances <- MutBalances
            .from(preOutputs, txEnv.fixedOutputs)
            .toRight(Right(InvalidBalances))
          _ <- balances
            .subAlph(preOutputs.head.lockupScript, txEnv.gasFeeUnsafe)
            .toRight(Right(UnableToPayGasFee))
        } yield balances
      } else {
        failed(ExpectNonPayableMethod)
      }

    val outputBalances: MutBalances = MutBalances.empty
  }
}
