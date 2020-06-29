package org.alephium.protocol.vm

import scala.collection.mutable.ArrayBuffer

import org.alephium.crypto.ED25519Signature
import org.alephium.protocol.ALF.Hash

trait ChainEnv
trait BlockEnv
trait TxEnv
trait ContractEnv

trait Context {
  def txHash: Hash
  def signatures: Stack[ED25519Signature]
}

class StatelessContext(val txHash: Hash, val signatures: Stack[ED25519Signature]) extends Context

object StatelessContext {
  def apply(txHash: Hash, signature: ED25519Signature): StatelessContext = {
    val stack = Stack.unsafe[ED25519Signature](ArrayBuffer(signature), 1)
    apply(txHash, stack)
  }

  def apply(txHash: Hash, signatures: Stack[ED25519Signature]): StatelessContext =
    new StatelessContext(txHash, signatures)

  val test: StatelessContext = StatelessContext(Hash.zero, Stack.ofCapacity[ED25519Signature](0))
}

trait StatefulContext extends StatelessContext {
  def contractEnv: ContractEnv
}
