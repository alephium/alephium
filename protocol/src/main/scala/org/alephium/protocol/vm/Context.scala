package org.alephium.protocol.vm

import scala.collection.mutable

import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.model.Transaction
import org.alephium.util.AVector

trait ChainEnv
trait BlockEnv
trait TxEnv
trait ContractEnv

trait Context {
  def txHash: Hash
  def signatures: Stack[Signature]
  def getInitialBalances: ExeResult[Frame.Balances]
  def outputBalances: Frame.Balances
}

trait StatelessContext extends Context

object StatelessContext {
  def apply(txHash: Hash, signature: Signature): StatelessContext = {
    val stack = Stack.unsafe[Signature](mutable.ArraySeq(signature), 1)
    apply(txHash, stack)
  }

  def apply(txHash: Hash, signatures: Stack[Signature]): StatelessContext =
    new Impl(txHash, signatures)

  final class Impl(val txHash: Hash, val signatures: Stack[Signature]) extends StatelessContext {
    override def getInitialBalances: ExeResult[Frame.Balances] = Left(NonPayableFrame)
    override def outputBalances: Frame.Balances                = ??? // should not be used
  }
}

trait StatefulContext extends StatelessContext {
  var worldState: WorldState

  def updateWorldState(newWorldState: WorldState): Unit = worldState = newWorldState

  def updateState(key: Hash, state: AVector[Val]): ExeResult[Unit] = {
    worldState.updateContract(key, state) match {
      case Left(error) =>
        Left(IOErrorUpdateState(error))
      case Right(state) =>
        updateWorldState(state)
        Right(())
    }
  }
}

object StatefulContext {
  def payable(tx: Transaction, worldState: WorldState): StatefulContext =
    new Payable(tx, worldState)

  def nonPayable(txHash: Hash, worldState: WorldState): StatefulContext =
    new NonPayable(txHash, Stack.ofCapacity(0), worldState)

  final class NonPayable(val txHash: Hash,
                         val signatures: Stack[Signature],
                         var worldState: WorldState)
      extends StatefulContext {
    override def getInitialBalances: ExeResult[Frame.Balances] = Left(NonPayableFrame)
    override def outputBalances: Frame.Balances                = ??? // should not be used
  }

  final class Payable(val tx: Transaction, val initWorldState: WorldState) extends StatefulContext {
    override var worldState: WorldState = initWorldState

    override def txHash: Hash = tx.hash

    override val signatures: Stack[Signature] = Stack.popOnly(tx.signatures)

    override def getInitialBalances: ExeResult[Frame.Balances] = {
      for {
        preOutputs <- initWorldState.getPreOutputs(tx).left.map[ExeFailure](IOErrorLoadOutputs)
        balances <- Frame.Balances
          .from(preOutputs, tx.unsigned.fixedOutputs)
          .toRight(InvalidBalances)
      } yield balances
    }

    override val outputBalances: Frame.Balances = Frame.Balances.empty
  }
}
