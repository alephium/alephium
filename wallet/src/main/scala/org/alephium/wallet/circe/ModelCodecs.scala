package org.alephium.wallet.circe

import io.circe._
import io.circe.generic.semiauto.deriveCodec

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.util.AVector
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

  implicit val mnemonicDecoder: Decoder[Mnemonic] = Decoder.decodeString.emap { input =>
    val words = AVector.from(input.split(" "))
    Mnemonic.fromWords(words).toRight(s"Cannot validate mnemonic: $input")
  }

  implicit val mnemonicEncoder: Encoder[Mnemonic] = Encoder[String].contramap(_.words.mkString(" "))

  implicit val mnemonicCodec: Codec[Mnemonic] = Codec.from(mnemonicDecoder, mnemonicEncoder)

  implicit val walletUnlockCodec: Codec[WalletUnlock] = deriveCodec[WalletUnlock]

  implicit val walletRestoreCodec: Codec[WalletRestore] = deriveCodec[WalletRestore]

  implicit val walletResultResultCodec: Codec[WalletRestore.Result] =
    deriveCodec[WalletRestore.Result]

  implicit val walletCreationCodec: Codec[WalletCreation] = deriveCodec[WalletCreation]

  implicit val walletCreationResultCodec: Codec[WalletCreation.Result] =
    deriveCodec[WalletCreation.Result]
}
