package org.alephium.wallet.api.model

import org.alephium.crypto.wallet.Mnemonic

final case class WalletRestore(password: String,
                               mnemonic: Mnemonic,
                               walletName: Option[String],
                               mnemonicPassphrase: Option[String])
object WalletRestore {
  final case class Result(walletName: String)
}
