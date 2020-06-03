package org.alephium.protocol.script

import akka.util.ByteString

import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.ALF.{Hash, HashSerde}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.serde._
import org.alephium.util.{AVector, Bytes, DjbHash}

final case class PubScript(instructions: AVector[Instruction]) extends HashSerde[PubScript] {
  override lazy val hash: Hash = _getHash

  lazy val shortKey: Int = DjbHash.intHash(hash.bytes)

  def shortKeyBytes: ByteString = serialize(shortKey)

  def groupIndex(implicit config: GroupConfig): GroupIndex = {
    PubScript.groupIndex(shortKey)
  }
}

object PubScript {
  implicit val serde: Serde[PubScript] = Serde.forProduct1(PubScript(_), t => t.instructions)

  def empty: PubScript = PubScript(AVector.empty)

  def build(payTo: PayTo, publicKey: ED25519PublicKey): PubScript = payTo match {
    case PayTo.PKH => p2pkh(publicKey)
    case PayTo.SH  => p2sh(publicKey)
  }

  // TODO: optimize this using cache
  private def p2pkh(publicKey: ED25519PublicKey): PubScript = {
    val pkHash = Hash.hash(publicKey.bytes)
    val instructions = AVector[Instruction](OP_DUP.unsafe(1),
                                            OP_KECCAK256,
                                            OP_PUSH.unsafe(pkHash.bytes),
                                            OP_EQUALVERIFY,
                                            OP_CHECKSIGVERIFY)
    PubScript(instructions)
  }

  private def p2sh(publicKey: ED25519PublicKey): PubScript = {
    val script    = AVector[Instruction](OP_PUSH.unsafe(publicKey.bytes), OP_CHECKSIGVERIFY)
    val scriptRaw = Instruction.serializeScript(script)
    p2sh(scriptRaw)
  }

  def p2sh(scriptRaw: ByteString): PubScript = {
    val scriptHash = Hash.hash(scriptRaw)
    PubScript(AVector[Instruction](OP_SCRIPTKECCAK256.from(scriptHash)))
  }

  def groupIndex(shortKey: Int)(implicit config: GroupConfig): GroupIndex = {
    val hash = Bytes.toPosInt(Bytes.xorByte(shortKey))
    GroupIndex.unsafe(hash % config.groups)
  }
}
