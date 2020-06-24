package org.alephium.protocol.vm

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
  def apply(txHash: Hash, signatures: Stack[ED25519Signature]): StatelessContext =
    new StatelessContext(txHash, signatures)

  val test: StatelessContext = StatelessContext(Hash.zero, Stack.ofCapacity(0))
}

trait StatefulContext extends StatelessContext {
  def contractEnv: ContractEnv
}
