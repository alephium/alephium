package org.alephium.wallet.api.model

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class WalletUnlock(password: String)

object WalletUnlock {
  implicit val codec: Codec[WalletUnlock] = deriveCodec[WalletUnlock]
}
