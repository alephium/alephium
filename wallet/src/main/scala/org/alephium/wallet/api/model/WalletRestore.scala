package org.alephium.wallet.api.model

final case class WalletRestore(password: String,
                               mnemonic: String,
                               walletName: Option[String],
                               mnemonicPassphrase: Option[String])
object WalletRestore {
  final case class Result(walletName: String)
}
