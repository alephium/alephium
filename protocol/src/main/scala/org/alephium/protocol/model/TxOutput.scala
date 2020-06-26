package org.alephium.protocol.model

import org.alephium.protocol.ALF
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde._
import org.alephium.util.{AVector, U64}

final case class TxOutput(amount: U64,
                          tokens: AVector[(TokenId, U64)],
                          createdHeight: Int,
                          lockupScript: LockupScript) {
  def scriptHint: Int = lockupScript.shortKey

  def toGroup(implicit config: GroupConfig): GroupIndex = lockupScript.groupIndex
}

object TxOutput {
  private implicit val tokenSerde: Serde[(TokenId, U64)] = Serde.tuple2[TokenId, U64]
  implicit val serde: Serde[TxOutput] =
    Serde.forProduct4(TxOutput.apply, t => (t.amount, t.tokens, t.createdHeight, t.lockupScript))

  def build(amount: U64, createdHeight: Int, lockupScript: LockupScript): TxOutput = {
    TxOutput(amount, AVector.empty, createdHeight, lockupScript)
  }

  def genesis(amount: U64, lockupScript: LockupScript): TxOutput = {
    build(amount, ALF.GenesisHeight, lockupScript)
  }

  // TODO: use proper op_code when it's ready
  def burn(amount: U64): TxOutput = {
    build(amount, ALF.GenesisHeight, LockupScript.p2pkh(ALF.Hash.zero))
  }
}
