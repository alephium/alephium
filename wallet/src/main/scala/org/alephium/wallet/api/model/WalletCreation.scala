package org.alephium.wallet.api.model

import org.alephium.crypto

final case class WalletCreation(password: String,
                                walletName: Option[String],
                                mnemonicPassphrase: Option[String],
                                mnemonicSize: Option[crypto.wallet.Mnemonic.Size])

object WalletCreation {
  final case class Result(walletName: String, mnemonic: Mnemonic)
}
