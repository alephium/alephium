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

import org.alephium.protocol.{BlockHash, Hash, Signature}
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model._
import org.alephium.util.{discard, AVector, TimeStamp, U256}

final case class BlockEnv(
    networkId: NetworkId,
    timeStamp: TimeStamp,
    target: Target,
    blockId: Option[BlockHash]
)
object BlockEnv {
  def from(header: BlockHeader)(implicit networkConfig: NetworkConfig): BlockEnv =
    BlockEnv(networkConfig.networkId, header.timestamp, header.target, Some(header.hash))
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
    def isEntryMethodPayable: Boolean      = tx.unsigned.scriptOpt.exists(_.entryMethod.isPayable)
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
    LogConfig(enabled = false, contractAddresses = None)
  }
}

trait StatelessContext extends CostStrategy {
  def networkConfig: NetworkConfig
  def blockEnv: BlockEnv
  def getHardFork(): HardFork = networkConfig.getHardFork(blockEnv.timeStamp)

  def txEnv: TxEnv
  def getInitialBalances(): ExeResult[Balances]

  def writeLog(contractIdOpt: Option[ContractId], fields: AVector[Val]): ExeResult[Unit]

  def txId: Hash                   = txEnv.txId
  def signatures: Stack[Signature] = txEnv.signatures

  def getTxPrevOutput(indexRaw: Val.U256): ExeResult[AssetOutput] = {
    indexRaw.v.toInt.flatMap(txEnv.prevOutputs.get).toRight(Right(InvalidTxInputIndex))
  }

  def getTxCaller(indexRaw: Val.U256): ExeResult[Val.Address] = {
    getTxPrevOutput(indexRaw).map(output => Val.Address(output.lockupScript))
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
    def getInitialBalances(): ExeResult[Balances] = failed(ExpectNonPayableMethod)

    def writeLog(contractIdOpt: Option[ContractId], fields: AVector[Val]): ExeResult[Unit] = okay
  }
}

trait StatefulContext extends StatelessContext with ContractPool {
  def worldState: WorldState.Staging

  def outputBalances: Balances

  def logConfig: LogConfig

  lazy val generatedOutputs: ArrayBuffer[TxOutput] = ArrayBuffer.empty

  def nextOutputIndex: Int

  def nextContractOutputRef(output: ContractOutput): ContractOutputRef =
    ContractOutputRef.unsafe(txId, output, nextOutputIndex)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def generateOutput(output: TxOutput): ExeResult[Unit] = {
    output match {
      case contractOutput @ ContractOutput(_, LockupScript.P2C(contractId), _) =>
        val outputRef = nextContractOutputRef(contractOutput)
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
      code: StatefulContract.HalfDecoded,
      initialBalances: BalancesPerLockup,
      initialFields: AVector[Val],
      tokenAmount: Option[Val.U256]
  ): ExeResult[Hash] = {
    val contractId = TxOutputRef.key(txId, nextOutputIndex)
    tokenAmount.foreach(amount => initialBalances.addToken(contractId, amount.v))
    val contractOutput = ContractOutput(
      initialBalances.alphAmount,
      LockupScript.p2c(contractId),
      initialBalances.tokenVector
    )
    val outputRef = nextContractOutputRef(contractOutput)
    for {
      _ <- code.check(initialFields)
      _ <-
        worldState
          .createContractUnsafe(code, initialFields, outputRef, contractOutput)
          .map(_ => discard(generatedOutputs.addOne(contractOutput)))
          .left
          .map(e => Left(IOErrorUpdateState(e)))
    } yield contractId
  }

  def destroyContract(
      contractId: ContractId,
      contractAssets: BalancesPerLockup,
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

  def updateContractAsset(
      contractId: ContractId,
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): ExeResult[Unit] = {
    for {
      _ <- worldState
        .updateContract(contractId, outputRef, output)
        .left
        .map(e => Left(IOErrorUpdateState(e)))
      _ <- markAssetFlushed(contractId)
    } yield ()
  }

  def writeLog(contractIdOpt: Option[ContractId], fields: AVector[Val]): ExeResult[Unit] = {
    val blockId = blockEnv.blockId
    val result = contractIdOpt match {
      case Some(contractId) =>
        worldState.writeLogForContract(blockId, txId, contractId, fields, logConfig)
      case None =>
        worldState.writeLogForTxScript(blockId, txId, fields)
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

  def build(
      blockEnv: BlockEnv,
      tx: TransactionAbstract,
      gasRemaining: GasBox,
      worldState: WorldState.Staging,
      preOutputsOpt: Option[AVector[AssetOutput]]
  )(implicit networkConfig: NetworkConfig, logConfig: LogConfig): ExeResult[StatefulContext] = {
    preOutputsOpt match {
      case Some(outputs) => Right(apply(blockEnv, tx, gasRemaining, worldState, outputs))
      case None =>
        worldState.getPreOutputsForAssetInputs(tx) match {
          case Right(Some(outputs)) => Right(apply(blockEnv, tx, gasRemaining, worldState, outputs))
          case Right(None)          => failed(NonExistTxInput)
          case Left(error)          => ioFailed(IOErrorLoadOutputs(error))
        }
    }
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
    def getInitialBalances(): ExeResult[Balances] =
      if (txEnv.isEntryMethodPayable) {
        for {
          balances <- Balances
            .from(preOutputs, txEnv.fixedOutputs)
            .toRight(Right(InvalidBalances))
          _ <- balances
            .subAlph(preOutputs.head.lockupScript, txEnv.gasFeeUnsafe)
            .toRight(Right(UnableToPayGasFee))
        } yield balances
      } else {
        failed(ExpectNonPayableMethod)
      }

    val outputBalances: Balances = Balances.empty
  }
}
