package org.alephium.protocol.script

import akka.util.ByteString

import org.alephium.crypto.ED25519PublicKey
import org.alephium.util.AVector

object PriScript {
  def build(payTo: PayTo, publicKey: ED25519PublicKey): AVector[Instruction] = payTo match {
    case PayTo.PKH => p2pkh(publicKey)
    case PayTo.SH  => p2sh(publicKey)
  }

  private def p2pkh(publicKey: ED25519PublicKey): AVector[Instruction] = {
    AVector[Instruction](OP_PUSH.unsafe(publicKey.bytes))
  }

  private def p2sh(publicKey: ED25519PublicKey): AVector[Instruction] = {
    val scriptToHash = AVector[Instruction](OP_PUSH.unsafe(publicKey.bytes), OP_CHECKSIGVERIFY)
    val scriptRaw    = Instruction.serializeScript(scriptToHash)
    p2sh(scriptRaw)
  }

  def p2sh(scriptRaw: ByteString): AVector[Instruction] = {
    AVector[Instruction](OP_PUSH.unsafe(scriptRaw))
  }
}
