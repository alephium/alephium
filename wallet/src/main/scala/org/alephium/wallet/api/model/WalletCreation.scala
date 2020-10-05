package org.alephium.wallet.api.model

import org.alephium.crypto.wallet.Mnemonic

final case class WalletCreation(password: String,
                                walletName: Option[String],
                                mnemonicPassphrase: Option[String],
                                mnemonicSize: Option[Mnemonic.Size])

object WalletCreation {
  final case class Result(walletName: String, mnemonic: Mnemonic)
}
