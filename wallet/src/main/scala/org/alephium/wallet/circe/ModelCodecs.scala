// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

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

  implicit val walletSatusCodec: Codec[WalletStatus] =
    deriveCodec[WalletStatus]
}
