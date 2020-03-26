package org.alephium.protocol.script

import akka.util.ByteString

import org.alephium.crypto.{ED25519PublicKey, Keccak256, Keccak256Hash}
import org.alephium.serde._
import org.alephium.util.{AVector, DjbHash}

final case class PubScript(instructions: AVector[Instruction]) extends Keccak256Hash[PubScript] {
  override lazy val hash: Keccak256 = _getHash

  lazy val shortKey: Int = DjbHash.intHash(hash.bytes)
}

object PubScript {
  implicit val serde: Serde[PubScript] = Serde.forProduct1(PubScript(_), t => t.instructions)

  def empty: PubScript = PubScript(AVector.empty)

  // TODO: optimize this using cache
  def p2pkh(publicKey: ED25519PublicKey): PubScript = {
    val pkHash = Keccak256.hash(publicKey.bytes)
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
    val scriptHash = Keccak256.hash(scriptRaw)
    PubScript(AVector[Instruction](OP_SCRIPTKECCAK256.from(scriptHash)))
  }
}
