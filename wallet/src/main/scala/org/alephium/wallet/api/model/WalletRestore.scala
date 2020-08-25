package org.alephium.wallet.api.model

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class WalletRestore(password: String,
                               mnemonic: String,
                               mnemonicPassphrase: Option[String])

object WalletRestore {
  implicit val codec: Codec[WalletRestore] = deriveCodec[WalletRestore]
}
