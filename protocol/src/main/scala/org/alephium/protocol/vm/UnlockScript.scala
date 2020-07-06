package org.alephium.protocol.vm

import akka.util.ByteString

import org.alephium.crypto.ED25519PublicKey
import org.alephium.serde._
import org.alephium.util.AVector

sealed trait UnlockScript

object UnlockScript {
  val p2shSerde: Serde[P2SH] = Serde.forProduct2(new P2SH(_, _), t => (t.script, t.params))
  val p2sSerde: Serde[P2S]   = Serde.forProduct1(new P2S(_), t     => t.params)
  implicit val serde: Serde[UnlockScript] = new Serde[UnlockScript] {
    override def serialize(input: UnlockScript): ByteString = {
      input match {
        case ppkh: P2PKH => ByteString(0) ++ serdeImpl[ED25519PublicKey].serialize(ppkh.publicKey)
        case psh: P2SH   => ByteString(1) ++ p2shSerde.serialize(psh)
        case ps: P2S     => ByteString(2) ++ p2sSerde.serialize(ps)
      }
    }

    override def _deserialize(input: ByteString): SerdeResult[(UnlockScript, ByteString)] = {
      byteSerde._deserialize(input).flatMap {
        case (0, content) =>
          serdeImpl[ED25519PublicKey]._deserialize(content).map(t => (new P2PKH(t._1), t._2))
        case (1, content) => p2shSerde._deserialize(content)
        case (2, content) => p2sSerde._deserialize(content)
        case (n, _)       => Left(SerdeError.wrongFormat(s"Invalid unlock script prefix $n"))
      }
    }
  }

  def p2pkh(publicKey: ED25519PublicKey): P2PKH                 = P2PKH(publicKey)
  def p2sh(script: StatelessScript, params: AVector[Val]): P2SH = P2SH(script, params)
  def p2s(params: AVector[Val]): P2S                            = P2S(params)

  final case class P2PKH(publicKey: ED25519PublicKey)                      extends UnlockScript
  final case class P2SH(script: StatelessScript, val params: AVector[Val]) extends UnlockScript
  final case class P2S(params: AVector[Val])                               extends UnlockScript
}
