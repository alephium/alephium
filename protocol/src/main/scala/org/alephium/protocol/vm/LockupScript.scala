package org.alephium.protocol.vm

import akka.util.ByteString

import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.ALF.{Hash, HashSerde}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.serde._
import org.alephium.util.{Bytes, DjbHash}

sealed trait LockupScript extends HashSerde[LockupScript] {
  override lazy val hash: Hash = _getHash

  lazy val shortKey: Int = DjbHash.intHash(hash.bytes)

  def shortKeyBytes: ByteString = serialize(shortKey)

  def groupIndex(implicit config: GroupConfig): GroupIndex = {
    LockupScript.groupIndex(shortKey)
  }
}

object LockupScript {
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
          serdeImpl[Hash]._deserialize(content).map(t => new P2PKH(t._1) -> t._2)
        case (1, content) =>
          serdeImpl[Hash]._deserialize(content).map(t => new P2SH(t._1) -> t._2)
        case (2, content) =>
          serdeImpl[StatelessScript]._deserialize(content).map(t => new P2S(t._1) -> t._2)
        case (n, _) =>
          Left(SerdeError.wrongFormat(s"Invalid lockupScript prefix $n"))
      }
    }
  }

  def p2pkh(key: ED25519PublicKey): P2PKH = p2pkh(Hash.hash(key.bytes))
  def p2pkh(pkHash: Hash): P2PKH          = new P2PKH(pkHash)
  def p2sh(scriptHash: Hash): P2SH        = new P2SH(scriptHash)
  def p2s(script: StatelessScript): P2S   = new P2S(script)

  final case class P2PKH(pkHash: Hash)          extends LockupScript
  final case class P2SH(scriptHash: Hash)       extends LockupScript
  final case class P2S(script: StatelessScript) extends LockupScript

  def groupIndex(shortKey: Int)(implicit config: GroupConfig): GroupIndex = {
    val hash = Bytes.toPosInt(Bytes.xorByte(shortKey))
    GroupIndex.unsafe(hash % config.groups)
  }
}
