package org.alephium.wallet.api.model

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class WalletCreation(password: String,
                                mnemonicPassphrase: Option[String],
                                mnemonicSize: Option[Int])

object WalletCreation {
  implicit val codec: Codec[WalletCreation] = deriveCodec[WalletCreation]
}
