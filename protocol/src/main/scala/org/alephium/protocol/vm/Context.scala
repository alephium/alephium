package org.alephium.protocol.vm

import scala.collection.mutable.ArrayBuffer

import org.alephium.protocol.{ALFSignature, Hash}
import org.alephium.util.AVector

trait ChainEnv
trait BlockEnv
trait TxEnv
trait ContractEnv

trait Context {
  def txHash: Hash
  def signatures: Stack[ALFSignature]
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

class StatelessContext(val txHash: Hash,
                       val signatures: Stack[ALFSignature],
                       var worldState: WorldState)
    extends Context {
  override def updateWorldState(newWorldState: WorldState): Unit = worldState = newWorldState
}

object StatelessContext {
  def apply(txHash: Hash, signature: ALFSignature, worldState: WorldState): StatelessContext = {
    val stack = Stack.unsafe[ALFSignature](ArrayBuffer(signature), 1)
    apply(txHash, stack, worldState)
  }

  def apply(txHash: Hash, worldState: WorldState): StatelessContext =
    apply(txHash, Stack.ofCapacity[ALFSignature](0), worldState)

  def apply(txHash: Hash,
            signatures: Stack[ALFSignature],
            worldState: WorldState): StatelessContext =
    new StatelessContext(txHash, signatures, worldState)
}

class StatefulContext(override val txHash: Hash, private val _worldState: WorldState)
    extends StatelessContext(txHash, Stack.ofCapacity(0), _worldState)

object StatefulContext {
  def apply(txHash: Hash, worldState: WorldState): StatefulContext =
    new StatefulContext(txHash, worldState)
}
