package org.alephium.wallet.api.model

final case class WalletRestore(password: String,
                               mnemonic: String,
                               mnemonicPassphrase: Option[String])
