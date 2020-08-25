package org.alephium.wallet.api.model

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Mnemonic(mnemonic: Seq[String]) {
  override def toString(): String = mnemonic.mkString(" ")
}

object Mnemonic {
  implicit val codec: Codec[Mnemonic] = deriveCodec[Mnemonic]
}
