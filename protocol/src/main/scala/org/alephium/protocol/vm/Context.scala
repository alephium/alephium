package org.alephium.protocol.vm

import scala.collection.mutable

import org.alephium.protocol.{Hash, Signature}
import org.alephium.util.AVector

trait ChainEnv
trait BlockEnv
trait TxEnv
trait ContractEnv

trait Context {
  def txHash: Hash
  def signatures: Stack[Signature]
}

trait StatelessContext extends Context

object StatelessContext {
  def apply(txHash: Hash, signature: Signature): StatelessContext = {
    val stack = Stack.unsafe[Signature](mutable.ArraySeq(signature), 1)
    apply(txHash, stack)
  }

  def apply(txHash: Hash, signatures: Stack[Signature]): StatelessContext =
    new Impl(txHash, signatures)

  final class Impl(val txHash: Hash, val signatures: Stack[Signature]) extends StatelessContext
}

trait StatefulContext extends StatelessContext {
  def worldState: WorldState

  def updateWorldState(newWorldState: WorldState): Unit

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
  def apply(txHash: Hash, worldState: WorldState): StatefulContext =
    new Impl(txHash, Stack.ofCapacity(0), worldState)

  final class Impl(val txHash: Hash, val signatures: Stack[Signature], var worldState: WorldState)
      extends StatefulContext {
    override def updateWorldState(newWorldState: WorldState): Unit = worldState = newWorldState
  }
}
