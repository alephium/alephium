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

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.alephium.io.IOError
import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.model._
import org.alephium.util.{discard, AVector}

trait ChainEnv
trait BlockEnv
trait TxEnv
trait ContractEnv

trait Context extends CostStrategy {
  def txId: Hash
  def signatures: Stack[Signature]
  def getInitialBalances: ExeResult[Frame.Balances]
}

trait StatelessContext extends Context

object StatelessContext {
  def apply(txId: Hash, txGas: GasBox, signature: Signature): StatelessContext = {
    val stack = Stack.unsafe[Signature](mutable.ArraySeq(signature), 1)
    apply(txId, txGas, stack)
  }

  def apply(txId: Hash, txGas: GasBox, signatures: Stack[Signature]): StatelessContext =
    new Impl(txId, signatures, txGas)

  final class Impl(val txId: Hash, val signatures: Stack[Signature], var gasRemaining: GasBox)
      extends StatelessContext {
    override def getInitialBalances: ExeResult[Frame.Balances] = Left(NonPayableFrame)
  }
}

trait StatefulContext extends StatelessContext with ContractPool {
  def worldState: WorldState.Staging

  def outputBalances: Frame.Balances

  lazy val generatedOutputs: ArrayBuffer[TxOutput]        = ArrayBuffer.empty
  lazy val contractInputs: ArrayBuffer[ContractOutputRef] = ArrayBuffer.empty

  def nextOutputIndex: Int

  def nextContractOutputRef(output: ContractOutput): ContractOutputRef =
    ContractOutputRef.unsafe(txId, output, nextOutputIndex)

  def createContract(
      code: StatefulContract,
      initialBalances: Frame.BalancesPerLockup,
      initialFields: AVector[Val]
  ): ExeResult[Unit] = {
    val contractId = TxOutputRef.key(txId, nextOutputIndex)
    val contractOutput = ContractOutput(
      initialBalances.alfAmount,
      LockupScript.p2c(contractId),
      initialBalances.tokenVector
    )
    val outputRef = nextContractOutputRef(contractOutput)
    worldState
      .createContract(code, initialFields, outputRef, contractOutput)
      .map(_ => discard(generatedOutputs.addOne(contractOutput)))
      .left
      .map(IOErrorUpdateState)
  }

  def useContractAsset(contractId: ContractId): ExeResult[Frame.BalancesPerLockup] = {
    worldState
      .useContractAsset(contractId)
      .map { case (contractOutputRef, contractAsset) =>
        contractInputs.addOne(contractOutputRef)
        Frame.BalancesPerLockup.from(contractAsset)
      }
      .left
      .map(IOErrorLoadContract)
  }

  def updateContractAsset(
      contractId: ContractId,
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): ExeResult[Unit] = {
    worldState
      .updateContract(contractId, outputRef, output)
      .left
      .map(IOErrorUpdateState)
  }
}

object StatefulContext {
  def apply(
      tx: TransactionAbstract,
      gasRemaining: GasBox,
      worldState: WorldState.Cached,
      preOutputsOpt: Option[AVector[TxOutput]]
  ): StatefulContext = {
    new Impl(tx, worldState, preOutputsOpt, gasRemaining)
  }

  final class Impl(
      val tx: TransactionAbstract,
      val initWorldState: WorldState.Cached,
      val preOutputsOpt: Option[AVector[TxOutput]],
      var gasRemaining: GasBox
  ) extends StatefulContext {
    override val worldState: WorldState.Staging = initWorldState.staging()

    override def txId: Hash = tx.id

    override val signatures: Stack[Signature] = Stack.popOnly(tx.contractSignatures)

    override def nextOutputIndex: Int = tx.unsigned.fixedOutputs.length + generatedOutputs.length

    /*
     * this should be used only when the tx has passed these checks in validation
     * 1. inputs are not empty
     * 2. gas fee bounds are validated
     */
    override def getInitialBalances: ExeResult[Frame.Balances] =
      if (tx.unsigned.scriptOpt.exists(_.entryMethod.isPayable)) {
        for {
          preOutputs <- getPreOutputs()
          balances <- Frame.Balances
            .from(preOutputs, tx.unsigned.fixedOutputs)
            .toRight[ExeFailure](InvalidBalances)
          _ <- balances
            .subAlf(preOutputs.head.lockupScript, tx.gasFeeUnsafe)
            .toRight[ExeFailure](UnableToPayGasFee)
        } yield balances
      } else {
        Left(NonPayableFrame)
      }

    private def getPreOutputs(): ExeResult[AVector[TxOutput]] = preOutputsOpt match {
      case Some(outputs) =>
        Right(outputs.filter(_.isInstanceOf[AssetOutput]))
      case None =>
        initWorldState.getPreOutputsForVM(tx) match {
          case Right(outputs)                      => Right(outputs)
          case Left(error: IOError.KeyNotFound[_]) => Left(NonExistTxInput(error))
          case Left(error)                         => Left(IOErrorLoadOutputs(error))
        }
    }

    override val outputBalances: Frame.Balances = Frame.Balances.empty
  }
}
