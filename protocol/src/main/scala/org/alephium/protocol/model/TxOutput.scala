package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.protocol.ALF
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde._
import org.alephium.util.{AVector, U64}

sealed trait TxOutput {
  def amount: U64
  def createdHeight: Int
  def scriptHint: Int
}

object TxOutput {
  implicit val serde: Serde[TxOutput] = eitherSerde[AssetOutput, ContractOutput].xmap(
    {
      case Left(assetOutput)     => assetOutput
      case Right(contractOutput) => contractOutput
    }, {
      case output: AssetOutput    => Left(output)
      case output: ContractOutput => Right(output)
    }
  )

  def asset(amount: U64, createdHeight: Int, lockupScript: LockupScript): AssetOutput = {
    AssetOutput(amount, AVector.empty, createdHeight, lockupScript, ByteString.empty)
  }

  def genesis(amount: U64, lockupScript: LockupScript): AssetOutput = {
    asset(amount, ALF.GenesisHeight, lockupScript)
  }

  def burn(amount: U64): TxOutput = {
    asset(amount, ALF.GenesisHeight, LockupScript.p2pkh(ALF.Hash.zero))
  }
}

final case class AssetOutput(amount: U64,
                             tokens: AVector[(TokenId, U64)],
                             createdHeight: Int,
                             lockupScript: LockupScript,
                             additionalData: ByteString)
    extends TxOutput {
  def scriptHint: Int = lockupScript.shortKey

  def toGroup(implicit config: GroupConfig): GroupIndex = lockupScript.groupIndex
}

object AssetOutput {
  private implicit val tokenSerde: Serde[(TokenId, U64)] = Serde.tuple2[TokenId, U64]
  implicit val serde: Serde[AssetOutput] =
    Serde.forProduct5(AssetOutput.apply,
                      t => (t.amount, t.tokens, t.createdHeight, t.lockupScript, t.additionalData))
}

final case class ContractOutput(amount: U64, createdHeight: Int, state: ByteString)
    extends TxOutput {
  def scriptHint: Int = 0
}

object ContractOutput {
  implicit val serde: Serde[ContractOutput] =
    Serde.forProduct3(ContractOutput.apply, t => (t.amount, t.createdHeight, t.state))
}
