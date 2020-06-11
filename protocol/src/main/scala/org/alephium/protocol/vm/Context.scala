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

case class StatelessContext(txHash: Hash, signatures: Stack[ED25519Signature]) extends Context
object StatelessContext {
  val test: StatelessContext = StatelessContext(Hash.zero, Stack.ofCapacity(0))
}

trait StatefulContext extends StatelessContext {
  def contractEnv: ContractEnv
}
