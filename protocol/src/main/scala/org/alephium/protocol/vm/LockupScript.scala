package org.alephium.protocol.vm

import akka.util.ByteString

import org.alephium.protocol.{Hash, HashSerde, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{GroupIndex, Hint, ScriptHint}
import org.alephium.serde._
import org.alephium.util.Bytes

sealed trait LockupScript extends HashSerde[LockupScript] {
  override lazy val hash: Hash = _getHash

  lazy val scriptHint: ScriptHint = ScriptHint.fromHash(hash)

  def assetHintBytes: ByteString = serialize(Hint.ofAsset(scriptHint))

  def groupIndex(implicit config: GroupConfig): GroupIndex = scriptHint.groupIndex
}

object LockupScript {

  def withPrefix(data: ByteString)(f: Byte => Byte): ByteString = {
    data.headOption match {
      case Some(prefix) =>
        ByteString.fromArrayUnsafe(data.updated(0, f(prefix).toByte).toArray)
      case None => data
    }
  }

  def withoutPrefix(data: ByteString, byte: Byte): ByteString = {
    data.headOption match {
      case Some(addressType) =>
        ByteString.fromArrayUnsafe(data.updated(0, (addressType - byte).toByte).toArray)
      case None => data
    }
  }
  implicit val serde: Serde[LockupScript] = new Serde[LockupScript] {
    override def serialize(input: LockupScript): ByteString = {
      input match {
        case s: P2PKH => ByteString(0) ++ serdeImpl[Hash].serialize(s.pkHash)
        case s: P2SH  => ByteString(1) ++ serdeImpl[Hash].serialize(s.scriptHash)
        case s: P2S   => ByteString(2) ++ serdeImpl[StatelessScript].serialize(s.script)
      }
    }

    override def _deserialize(input: ByteString): SerdeResult[(LockupScript, ByteString)] = {
      byteSerde._deserialize(input).flatMap {
        case (0, content) =>
          serdeImpl[Hash]._deserialize(content).map { case (pkHash, rest) => P2PKH(pkHash) -> rest }
        case (1, content) =>
          serdeImpl[Hash]._deserialize(content).map { case (sHash, rest) => P2SH(sHash) -> rest }
        case (2, content) =>
          serdeImpl[StatelessScript]._deserialize(content).map { case (s, rest) => P2S(s) -> rest }
        case (n, _) =>
          Left(SerdeError.wrongFormat(s"Invalid lockupScript prefix $n"))
      }
    }
  }

  def p2pkh(key: PublicKey): P2PKH      = p2pkh(Hash.hash(key.bytes))
  def p2pkh(pkHash: Hash): P2PKH        = new P2PKH(pkHash)
  def p2sh(scriptHash: Hash): P2SH      = new P2SH(scriptHash)
  def p2s(script: StatelessScript): P2S = new P2S(script)

  final case class P2PKH(pkHash: Hash)          extends LockupScript
  final case class P2SH(scriptHash: Hash)       extends LockupScript
  final case class P2S(script: StatelessScript) extends LockupScript

  def groupIndex(shortKey: Int)(implicit config: GroupConfig): GroupIndex = {
    val hash = Bytes.toPosInt(Bytes.xorByte(shortKey))
    GroupIndex.unsafe(hash % config.groups)
  }
}
