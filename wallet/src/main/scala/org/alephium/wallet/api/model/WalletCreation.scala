package org.alephium.wallet.api.model

import org.alephium.crypto.wallet.Mnemonic

final case class WalletCreation(password: String,
                                mnemonicPassphrase: Option[String],
                                mnemonicSize: Option[Mnemonic.Size])
