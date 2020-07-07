package org.alephium.protocol.vm

import scala.collection.mutable.ArrayBuffer

import org.alephium.crypto.ED25519Signature
import org.alephium.protocol.ALF.Hash
import org.alephium.util.AVector

trait ChainEnv
trait BlockEnv
trait TxEnv
trait ContractEnv

trait Context {
  def txHash: Hash
  def signatures: Stack[ED25519Signature]
  var worldState: WorldState

  def updateState(key: Hash, state: AVector[Val]): ExeResult[Unit] = {
    worldState.put(key, state) match {
      case Left(error) =>
        Left(IOErrorUpdateState(error))
      case Right(state) =>
        this.worldState = state
        Right(())
    }
  }
}

class StatelessContext(val txHash: Hash,
                       val signatures: Stack[ED25519Signature],
                       var worldState: WorldState)
    extends Context

object StatelessContext {
  def apply(txHash: Hash, signature: ED25519Signature, worldState: WorldState): StatelessContext = {
    val stack = Stack.unsafe[ED25519Signature](ArrayBuffer(signature), 1)
    apply(txHash, stack, worldState)
  }

  def apply(txHash: Hash,
            signatures: Stack[ED25519Signature],
            worldState: WorldState): StatelessContext =
    new StatelessContext(txHash, signatures, worldState)

  val mock: StatelessContext =
    StatelessContext(Hash.zero, Stack.ofCapacity[ED25519Signature](0), WorldState.mock)
}

trait StatefulContext extends StatelessContext {
  def contractEnv: ContractEnv
}
