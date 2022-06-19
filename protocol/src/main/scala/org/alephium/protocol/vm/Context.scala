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

import org.alephium.io.IOError
import org.alephium.protocol.{BlockHash, Hash, Signature}
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model._
import org.alephium.util.{discard, AVector, TimeStamp, U256}

final case class BlockEnv(
    networkId: NetworkId,
    timeStamp: TimeStamp,
    target: Target,
    blockId: Option[BlockHash],
    hardFork: HardFork
) {
  @inline def getHardFork(): HardFork = hardFork
}
object BlockEnv {
  def apply(
      networkId: NetworkId,
      timeStamp: TimeStamp,
      target: Target,
      blockId: Option[BlockHash]
  )(implicit networkConfig: NetworkConfig): BlockEnv =
    BlockEnv(networkId, timeStamp, target, blockId, networkConfig.getHardFork(timeStamp))

  def from(header: BlockHeader)(implicit networkConfig: NetworkConfig): BlockEnv =
    BlockEnv(
      networkConfig.networkId,
      header.timestamp,
      header.target,
      Some(header.hash),
      networkConfig.getHardFork(header.timestamp)
    )
}

sealed trait TxEnv {
  def txId: Hash
  def signatures: Stack[Signature]
  def prevOutputs: AVector[AssetOutput]
  def fixedOutputs: AVector[AssetOutput]
  def gasFeeUnsafe: U256

  def isEntryMethodPayable: Boolean
}

object TxEnv {
  def apply(
      tx: TransactionAbstract,
      prevOutputs: AVector[AssetOutput],
      signatures: Stack[Signature]
  ): TxEnv = Default(tx, prevOutputs, signatures)

  def mockup(
      txId: Hash,
      signatures: Stack[Signature],
      prevOutputs: AVector[AssetOutput],
      fixedOutputs: AVector[AssetOutput],
      gasFeeUnsafe: U256,
      isEntryMethodPayable: Boolean
  ): TxEnv =
    Mockup(txId, signatures, prevOutputs, fixedOutputs, gasFeeUnsafe, isEntryMethodPayable)

  final case class Default(
      tx: TransactionAbstract,
      prevOutputs: AVector[AssetOutput],
      signatures: Stack[Signature]
  ) extends TxEnv {
    def txId: Hash                         = tx.id
    def fixedOutputs: AVector[AssetOutput] = tx.unsigned.fixedOutputs
    def gasFeeUnsafe: U256                 = tx.gasFeeUnsafe
    def isEntryMethodPayable: Boolean      = tx.isEntryMethodPayable
  }

  final case class Mockup(
      txId: Hash,
      signatures: Stack[Signature],
      prevOutputs: AVector[AssetOutput],
      fixedOutputs: AVector[AssetOutput],
      gasFeeUnsafe: U256,
      isEntryMethodPayable: Boolean
  ) extends TxEnv
}

final case class LogConfig(
    enabled: Boolean,
    indexByTxId: Boolean,
    contractAddresses: Option[AVector[Address.Contract]]
) {
  def logContractEnabled(contractAddress: Address.Contract): Boolean = {
    val allowAllContracts = contractAddresses.isEmpty
    val allowThisContract = contractAddresses.exists(_.contains(contractAddress))
    enabled && (allowAllContracts || allowThisContract)
  }
}

object LogConfig {
  def disabled(): LogConfig = {
    LogConfig(enabled = false, indexByTxId = false, contractAddresses = None)
  }

  def allEnabled(): LogConfig = {
    LogConfig(enabled = true, indexByTxId = true, contractAddresses = None)
  }
}

trait StatelessContext extends CostStrategy {
  def networkConfig: NetworkConfig
  def blockEnv: BlockEnv

  @inline def getHardFork(): HardFork = blockEnv.getHardFork()
  def checkLemanHardFork[C <: StatelessContext](instr: Instr[C]): ExeResult[Unit] = {
    if (getHardFork().isLemanEnabled()) {
      okay
    } else {
      failed(InactiveInstr(instr))
    }
  }

  def txEnv: TxEnv
  def getInitialBalances(): ExeResult[MutBalances]

  def writeLog(contractIdOpt: Option[ContractId], fields: AVector[Val]): ExeResult[Unit]

  def txId: Hash                   = txEnv.txId
  def signatures: Stack[Signature] = txEnv.signatures

  def getTxPrevOutput(indexRaw: Val.U256): ExeResult[AssetOutput] = {
    indexRaw.v.toInt.flatMap(txEnv.prevOutputs.get).toRight(Right(InvalidTxInputIndex))
  }

  def getTxInputAddressAt(indexRaw: Val.U256): ExeResult[Val.Address] = {
    getTxPrevOutput(indexRaw).map(output => Val.Address(output.lockupScript))
  }

  def getUniqueTxInputAddress(): ExeResult[Val.Address] = {
    for {
      _ <-
        if (getHardFork().isLemanEnabled()) okay else failed(PartiallyEnabledInstr(CallerAddress))
      _       <- chargeGas(GasUniqueAddress.gas(txEnv.prevOutputs.length))
      address <- _getUniqueTxInputAddress()
    } yield address
  }

  private def _getUniqueTxInputAddress(): ExeResult[Val.Address] = {
    txEnv.prevOutputs.headOption match {
      case Some(firstInput) =>
        if (txEnv.prevOutputs.tail.forall(_.lockupScript == firstInput.lockupScript)) {
          Right(Val.Address(firstInput.lockupScript))
        } else {
          failed(TxInputAddressesAreNotIdentical)
        }
      case None =>
        failed(NoTxInput)
    }
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
  )(implicit networkConfig: NetworkConfig): StatelessContext =
    new Impl(blockEnv, txEnv, txGas)

  final class Impl(
      val blockEnv: BlockEnv,
      val txEnv: TxEnv,
      var gasRemaining: GasBox
  )(implicit val networkConfig: NetworkConfig)
      extends StatelessContext {
    def getInitialBalances(): ExeResult[MutBalances] = failed(ExpectNonPayableMethod)

    def writeLog(contractIdOpt: Option[ContractId], fields: AVector[Val]): ExeResult[Unit] = okay
  }
}

trait StatefulContext extends StatelessContext with ContractPool {
  def worldState: WorldState.Staging

  def outputBalances: MutBalances

  def logConfig: LogConfig

  lazy val generatedOutputs: ArrayBuffer[TxOutput] = ArrayBuffer.empty

  def nextOutputIndex: Int

  def nextContractOutputRef(contractId: Hash, output: ContractOutput): ContractOutputRef =
    ContractOutputRef.unsafe(output.hint, contractId)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def generateOutput(output: TxOutput): ExeResult[Unit] = {
    output match {
      case contractOutput @ ContractOutput(_, LockupScript.P2C(contractId), _) =>
        val outputRef = nextContractOutputRef(contractId, contractOutput)
        for {
          _ <- chargeGeneratedOutput()
          _ <- updateContractAsset(contractId, outputRef, contractOutput)
        } yield {
          generatedOutputs.addOne(output)
          ()
        }
      case _ =>
        generatedOutputs.addOne(output)
        chargeGeneratedOutput()
    }
  }

  def createContract(
      contractId: Hash,
      code: StatefulContract.HalfDecoded,
      initialBalances: MutBalancesPerLockup,
      initialFields: AVector[Val],
      tokenAmount: Option[Val.U256]
  ): ExeResult[Hash] = {
    tokenAmount.foreach(amount => initialBalances.addToken(contractId, amount.v))
    val contractOutput = ContractOutput(
      initialBalances.attoAlphAmount,
      LockupScript.p2c(contractId),
      initialBalances.tokenVector
    )
    val outputRef = nextContractOutputRef(contractId, contractOutput)

    for {
      _ <-
        worldState.getContractState(contractId) match {
          case Left(_: IOError.KeyNotFound) =>
            okay
          case Left(otherIOError) =>
            ioFailed(IOErrorLoadContract(otherIOError))
          case Right(_) =>
            Left(Right(ContractAlreadyExists(contractId)))
        }
      _ <- code.check(initialFields)
      _ <-
        worldState
          .createContractUnsafe(code, initialFields, outputRef, contractOutput)
          .map(_ => discard(generatedOutputs.addOne(contractOutput)))
          .left
          .map(e => Left(IOErrorUpdateState(e)))
    } yield {
      blockContractLoad(contractId)
      contractId
    }
  }

  def destroyContract(
      contractId: ContractId,
      contractAssets: MutBalancesPerLockup,
      address: LockupScript
  ): ExeResult[Unit] = {
    for {
      _ <- address match {
        case _: LockupScript.Asset => okay
        case _: LockupScript.P2C   => failed(InvalidAddressTypeInContractDestroy)
      }
      _ <- outputBalances.add(address, contractAssets).toRight(Right(InvalidBalances))
      _ <- removeContract(contractId)
    } yield ()
  }

  def migrateContract(
      contractId: ContractId,
      obj: ContractObj[StatefulContext],
      newContractCode: StatefulContract,
      newFieldsOpt: Option[AVector[Val]]
  ): ExeResult[Unit] = {
    val newFields = newFieldsOpt.getOrElse(AVector.from(obj.fields))
    for {
      _ <-
        if (newFields.length == newContractCode.fieldLength) { okay }
        else {
          failed(InvalidFieldLength)
        }
      _ <- chargeFieldSize(newFields.toIterable)
      _ <- worldState
        .migrateContractUnsafe(contractId, newContractCode, newFields)
        .left
        .map(e => Left(IOErrorMigrateContract(e)))
    } yield {
      blockContractLoad(contractId)
      removeContractFromCache(contractId)
    }
  }

  def updateContractAsset(
      contractId: ContractId,
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): ExeResult[Unit] = {
    for {
      _ <- markAssetFlushed(contractId)
      _ <- worldState
        .updateContract(contractId, outputRef, output)
        .left
        .map(e => Left(IOErrorUpdateState(e)))
    } yield ()
  }

  def writeLog(contractIdOpt: Option[ContractId], fields: AVector[Val]): ExeResult[Unit] = {
    val result = (blockEnv.blockId, contractIdOpt) match {
      case (Some(blockId), Some(contractId))
          if logConfig.logContractEnabled(Address.contract(contractId)) =>
        worldState.writeLogForContract(blockId, txId, contractId, fields, logConfig.indexByTxId)
      case _ => Right(())
    }

    result.left.map(e => Left(IOErrorWriteLog(e)))
  }
}

object StatefulContext {
  def apply(
      blockEnv: BlockEnv,
      txEnv: TxEnv,
      worldState: WorldState.Staging,
      gasRemaining: GasBox
  )(implicit networkConfig: NetworkConfig, logConfig: LogConfig): StatefulContext = {
    new Impl(blockEnv, txEnv, worldState, gasRemaining)
  }

  def apply(
      blockEnv: BlockEnv,
      tx: TransactionAbstract,
      gasRemaining: GasBox,
      worldState: WorldState.Staging,
      preOutputs: AVector[AssetOutput]
  )(implicit networkConfig: NetworkConfig, logConfig: LogConfig): StatefulContext = {
    val txEnv = TxEnv(tx, preOutputs, Stack.popOnly(tx.scriptSignatures))
    apply(blockEnv, txEnv, worldState, gasRemaining)
  }

  final class Impl(
      val blockEnv: BlockEnv,
      val txEnv: TxEnv,
      val worldState: WorldState.Staging,
      var gasRemaining: GasBox
  )(implicit val networkConfig: NetworkConfig, val logConfig: LogConfig)
      extends StatefulContext {
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
