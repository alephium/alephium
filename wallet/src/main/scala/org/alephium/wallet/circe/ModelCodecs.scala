package org.alephium.wallet.circe

import io.circe._
import io.circe.generic.semiauto.deriveCodec

import org.alephium.wallet.api.model._

trait ModelCodecs extends ProtocolCodecs {

  implicit val addressesCodec: Codec[Addresses] = deriveCodec[Addresses]

  implicit val addressBalanceCodec: Codec[Balances.AddressBalance] =
    deriveCodec[Balances.AddressBalance]

  implicit val balancesCodec: Codec[Balances] = deriveCodec[Balances]

  implicit val changeActiveAddressCodec: Codec[ChangeActiveAddress] =
    deriveCodec[ChangeActiveAddress]

  implicit val transferCodec: Codec[Transfer] = deriveCodec[Transfer]

  implicit val transferResultCodec: Codec[Transfer.Result] = deriveCodec[Transfer.Result]

  implicit val MnemonicCodec: Codec[Mnemonic] = deriveCodec[Mnemonic]

  implicit val walletUnlockCodec: Codec[WalletUnlock] = deriveCodec[WalletUnlock]

  implicit val walletRestoreCodec: Codec[WalletRestore] = deriveCodec[WalletRestore]

  implicit val walletCreationCodec: Codec[WalletCreation] = deriveCodec[WalletCreation]
}
