package org.alephium.wallet.api.model

final case class WalletCreation(password: String,
                                mnemonicPassphrase: Option[String],
                                mnemonicSize: Option[Int])
