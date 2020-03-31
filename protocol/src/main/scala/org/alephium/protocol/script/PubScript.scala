package org.alephium.protocol.script

import akka.util.ByteString

import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.ALF.{Hash, HashSerde}
import org.alephium.serde._
import org.alephium.util.{AVector, DjbHash}

final case class PubScript(instructions: AVector[Instruction]) extends HashSerde[PubScript] {
  override lazy val hash: Hash = _getHash

  lazy val shortKey: Int = DjbHash.intHash(hash.bytes)
}

object PubScript {
  implicit val serde: Serde[PubScript] = Serde.forProduct1(PubScript(_), t => t.instructions)

  def empty: PubScript = PubScript(AVector.empty)

  // TODO: optimize this using cache
  def p2pkh(publicKey: ED25519PublicKey): PubScript = {
    val pkHash = Hash.hash(publicKey.bytes)
    val instructions = AVector[Instruction](OP_DUP.unsafe(1),
                                            OP_KECCAK256,
                                            OP_PUSH.unsafe(pkHash.bytes),
                                            OP_EQUALVERIFY,
                                            OP_CHECKSIGVERIFY)
    PubScript(instructions)
  }

  def p2sh(publicKey: ED25519PublicKey): PubScript = {
    val script    = AVector[Instruction](OP_PUSH.unsafe(publicKey.bytes), OP_CHECKSIGVERIFY)
    val scriptRaw = Instruction.serializeScript(script)
    p2sh(scriptRaw)
  }

  def p2sh(scriptRaw: ByteString): PubScript = {
    val scriptHash = Hash.hash(scriptRaw)
    PubScript(AVector[Instruction](OP_SCRIPTKECCAK256.from(scriptHash)))
  }
}
