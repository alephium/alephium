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

import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.model._
import org.alephium.util.AVector

trait ChainEnv
trait BlockEnv
trait TxEnv
trait ContractEnv

trait Context extends CostStrategy {
  def txHash: Hash
  def signatures: Stack[Signature]
  def getInitialBalances: ExeResult[Frame.Balances]
}

trait StatelessContext extends Context

object StatelessContext {
  def apply(txHash: Hash, txGas: GasBox, signature: Signature): StatelessContext = {
    val stack = Stack.unsafe[Signature](mutable.ArraySeq(signature), 1)
    apply(txHash, txGas, stack)
  }

  def apply(txHash: Hash, txGas: GasBox, signatures: Stack[Signature]): StatelessContext =
    new Impl(txHash, signatures, txGas)

  final class Impl(val txHash: Hash, val signatures: Stack[Signature], var gasRemaining: GasBox)
      extends StatelessContext {
    override def getInitialBalances: ExeResult[Frame.Balances] = Left(NonPayableFrame)
  }
}

trait StatefulContext extends StatelessContext with ContractPool {
  var worldState: WorldState

  def outputBalances: Frame.Balances

  lazy val generatedOutputs: ArrayBuffer[TxOutput]        = ArrayBuffer.empty
  lazy val contractInputs: ArrayBuffer[ContractOutputRef] = ArrayBuffer.empty

  def nextOutputIndex: Int

  def nextContractOutputRef(output: ContractOutput): ContractOutputRef =
    ContractOutputRef.unsafe(txHash, output, nextOutputIndex)

  def createContract(code: StatefulContract,
                     initialBalances: Frame.BalancesPerLockup,
                     initialFields: AVector[Val]): ExeResult[Unit] = {
    val contractId = TxOutputRef.key(txHash, nextOutputIndex)
    val contractOutput = ContractOutput(initialBalances.alfAmount,
                                        LockupScript.p2c(contractId),
                                        initialBalances.tokenVector)
    val outputRef = nextContractOutputRef(contractOutput)
    worldState
      .createContract(code, initialFields, outputRef, contractOutput)
      .map { newWorldState =>
        worldState = newWorldState
        generatedOutputs.addOne(contractOutput)
        ()
      }
      .left
      .map(IOErrorUpdateState)
  }

  def useContractAsset(contractId: ContractId): ExeResult[Frame.BalancesPerLockup] = {
    worldState
      .useContractAsset(contractId)
      .map {
        case (contractOutputRef, contractAsset, newWorldState) =>
          updateWorldState(newWorldState)
          contractInputs.addOne(contractOutputRef)
          Frame.BalancesPerLockup.from(contractAsset)
      }
      .left
      .map(IOErrorLoadContract)
  }

  def updateContractAsset(contractId: ContractId,
                          outputRef: ContractOutputRef,
                          output: ContractOutput): ExeResult[Unit] = {
    worldState
      .updateContract(contractId, outputRef, output)
      .map(updateWorldState)
      .left
      .map(IOErrorUpdateState)
  }
}

object StatefulContext {
  def apply(tx: TransactionAbstract,
            gasRemaining: GasBox,
            worldState: WorldState): StatefulContext = {
    new Impl(tx, worldState, gasRemaining)
  }

  final class Impl(val tx: TransactionAbstract,
                   val initWorldState: WorldState,
                   var gasRemaining: GasBox)
      extends StatefulContext {
    override var worldState: WorldState = initWorldState

    override def txHash: Hash = tx.hash

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
          preOutputs <- initWorldState
            .getPreOutputsForVM(tx)
            .left
            .map[ExeFailure](IOErrorLoadOutputs)
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

    override val outputBalances: Frame.Balances = Frame.Balances.empty
  }
}
