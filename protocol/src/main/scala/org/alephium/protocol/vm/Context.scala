package org.alephium.protocol.vm

import scala.collection.mutable.ArrayBuffer

import org.alephium.crypto.ED25519Signature
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.io.IOResult
import org.alephium.protocol.model.{TxOutput, TxOutputRef}

trait ChainEnv
trait BlockEnv
trait TxEnv
trait ContractEnv

trait WorldStateT {
  def get(outputRef: TxOutputRef): IOResult[TxOutput]

  def put(outputRef: TxOutputRef, output: TxOutput): IOResult[WorldStateT]

  def put(key: Hash, contract: StatelessScript): IOResult[WorldStateT]

  def remove(outputRef: TxOutputRef): IOResult[WorldStateT]

  def remove(key: Hash): IOResult[WorldStateT]
}

object WorldStateT {
  val mock: WorldStateT = new WorldStateT {
    override def get(outputRef: TxOutputRef): IOResult[TxOutput]                      = ???
    override def put(outputRef: TxOutputRef, output: TxOutput): IOResult[WorldStateT] = ???
    override def put(key: Hash, contract: StatelessScript): IOResult[WorldStateT]     = ???
    override def remove(outputRef: TxOutputRef): IOResult[WorldStateT]                = ???
    override def remove(key: Hash): IOResult[WorldStateT]                             = ???
  }
}

trait Context {
  def txHash: Hash
  def signatures: Stack[ED25519Signature]
  def worldState: WorldStateT
}

class StatelessContext(val txHash: Hash,
                       val signatures: Stack[ED25519Signature],
                       val worldState: WorldStateT)
    extends Context

object StatelessContext {
  def apply(txHash: Hash,
            signature: ED25519Signature,
            worldStateT: WorldStateT): StatelessContext = {
    val stack = Stack.unsafe[ED25519Signature](ArrayBuffer(signature), 1)
    apply(txHash, stack, worldStateT)
  }

  def apply(txHash: Hash,
            signatures: Stack[ED25519Signature],
            worldStateT: WorldStateT): StatelessContext =
    new StatelessContext(txHash, signatures, worldStateT)

  val mock: StatelessContext =
    StatelessContext(Hash.zero, Stack.ofCapacity[ED25519Signature](0), WorldStateT.mock)
}

trait StatefulContext extends StatelessContext {
  def contractEnv: ContractEnv
}
