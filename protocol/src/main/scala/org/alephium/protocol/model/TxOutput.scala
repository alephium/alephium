package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.ALF
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.script.{PayTo, PubScript}
import org.alephium.serde._
import org.alephium.util.U64

sealed trait TxOutput {
  def tokenIdOpt: Option[TokenId]
  def amount: U64
  def alfAmount: U64
  def data: ByteString

  def createdHeight: U64
  def lockupHeight: Option[U64]

  def lockupScript: PubScript
  def scriptHint: Int =
    lockupScript.shortKey // Short index for locating outputs locked by specific scripts

  def toGroup(implicit config: GroupConfig): GroupIndex = lockupScript.groupIndex
}

object TxOutput {
  private val serdeEither = eitherSerde[AlfOutput, TokenOutput](AlfOutput.serde, TokenOutput.serde)
  implicit val serde: Serde[TxOutput] = serdeEither.xmap({
    case Left(output)  => output
    case Right(output) => output
  }, {
    case output: AlfOutput   => Left(output)
    case output: TokenOutput => Right(output)
  })
}

final case class AlfOutput(amount: U64,
                           data: ByteString,
                           createdHeight: U64,
                           lockupHeight: Option[U64],
                           lockupScript: PubScript)
    extends TxOutput {
  override def tokenIdOpt: Option[TokenId] = None
  override def alfAmount: U64              = amount
}

object AlfOutput {
  implicit val serde: Serde[AlfOutput] = Serde.forProduct5(
    AlfOutput.apply,
    t => (t.amount, t.data, t.createdHeight, t.lockupHeight, t.lockupScript))

  def build(amount: U64, lockupScript: PubScript): AlfOutput = {
    build(amount, lockupScript, ByteString.empty)
  }

  def build(amount: U64, lockupScript: PubScript, data: ByteString): AlfOutput = {
    new AlfOutput(amount, data, createdHeight = U64.Zero, lockupHeight = None, lockupScript)
  }

  def build(payTo: PayTo, amount: U64, publicKey: ED25519PublicKey): AlfOutput = {
    val lockupScript = PubScript.build(payTo, publicKey)
    build(amount, lockupScript)
  }

  // TODO: use proper op_code when it's ready
  def burn(amount: U64): AlfOutput = {
    build(amount, PubScript.empty)
  }
}

final case class TokenOutput(tokenId: TokenId,
                             amount: U64,
                             alfAmount: U64,
                             data: ByteString,
                             createdHeight: U64,
                             lockupHeight: Option[U64],
                             lockupScript: PubScript)
    extends TxOutput {
  override def tokenIdOpt: Option[TokenId] = Some(tokenId)
}

object TokenOutput {
  implicit val serde: Serde[TokenOutput] = Serde.forProduct7(
    TokenOutput.apply,
    t =>
      (t.tokenId, t.amount, t.alfAmount, t.data, t.createdHeight, t.lockupHeight, t.lockupScript))

  def build(tokenId: TokenId, amount: U64, alfAmount: U64, lockupScript: PubScript): TokenOutput = {
    new TokenOutput(tokenId,
                    amount,
                    alfAmount,
                    data          = ByteString.empty,
                    createdHeight = U64.Zero,
                    lockupHeight  = None,
                    lockupScript)
  }
  def build(tokenId: TokenId,
            payTo: PayTo,
            amount: U64,
            alfAmount: U64,
            publicKey: ED25519PublicKey): TokenOutput = {
    val lockupScript = PubScript.build(payTo, publicKey)
    build(tokenId, amount, alfAmount, lockupScript)
  }

  // TODO: use proper op_code when it's ready
  def burn(amount: U64): TokenOutput = {
    build(ALF.Hash.zero, amount, U64.Zero, PubScript.empty)
  }
}
