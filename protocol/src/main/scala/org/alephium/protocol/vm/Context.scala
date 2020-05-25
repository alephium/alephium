package org.alephium.protocol.vm

trait ChainEnv
trait BlockEnv
trait TxEnv
trait ContractEnv

trait Context {
  def txEnv: TxEnv
  def blockEnv: BlockEnv
  def chainEnv: ChainEnv
}

trait StatelessContext extends Context

trait StatefulContext extends StatelessContext {
  def contractEnv: ContractEnv
}
