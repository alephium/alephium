package org.alephium.protocol.vm

import akka.util.ByteString

import org.alephium.protocol.{Hash, HashSerde, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{GroupIndex, Hint, ScriptHint}
import org.alephium.serde._
import org.alephium.util.{Base58, Bytes}

sealed trait LockupScript extends HashSerde[LockupScript] {
  override lazy val hash: Hash = _getHash

  lazy val scriptHint: ScriptHint = ScriptHint.fromHash(hash)

  def assetHintBytes: ByteString = serialize(Hint.ofAsset(scriptHint))

  def groupIndex(implicit config: GroupConfig): GroupIndex = scriptHint.groupIndex

  def toBase58: String = Base58.encode(serialize(this))
}

object LockupScript {
  implicit val serde: Serde[LockupScript] = new Serde[LockupScript] {
    override def serialize(input: LockupScript): ByteString = {
      input match {
        case s: P2PKH => ByteString(0) ++ serdeImpl[Hash].serialize(s.pkHash)
        case s: P2SH  => ByteString(1) ++ serdeImpl[Hash].serialize(s.scriptHash)
        case s: P2S   => ByteString(2) ++ serdeImpl[StatelessScript].serialize(s.script)
        case s: P2C   => ByteString(3) ++ serdeImpl[Hash].serialize(s.contractId)
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
        case (3, content) =>
          serdeImpl[Hash]._deserialize(content).map { case (cHash, rest) => P2C(cHash) -> rest }
        case (n, _) =>
          Left(SerdeError.wrongFormat(s"Invalid lockupScript prefix $n"))
      }
    }
  }

  val vmDefault: LockupScript = p2pkh(Hash.zero)

  def fromBase58(input: String): Option[LockupScript] = {
    Base58.decode(input).flatMap(deserialize[LockupScript](_).toOption)
  }

  def p2pkh(key: PublicKey): P2PKH      = p2pkh(Hash.hash(key.bytes))
  def p2pkh(pkHash: Hash): P2PKH        = P2PKH(pkHash)
  def p2sh(scriptHash: Hash): P2SH      = P2SH(scriptHash)
  def p2s(script: StatelessScript): P2S = P2S(script)
  def p2c(contractId: Hash): P2C        = P2C(contractId)

  final case class P2PKH(pkHash: Hash)          extends LockupScript
  final case class P2SH(scriptHash: Hash)       extends LockupScript
  final case class P2S(script: StatelessScript) extends LockupScript
  final case class P2C(contractId: Hash)        extends LockupScript

  def groupIndex(shortKey: Int)(implicit config: GroupConfig): GroupIndex = {
    val hash = Bytes.toPosInt(Bytes.xorByte(shortKey))
    GroupIndex.unsafe(hash % config.groups)
  }
}
