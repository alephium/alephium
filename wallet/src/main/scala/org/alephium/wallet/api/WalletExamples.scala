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

package org.alephium.wallet.api

import sttp.tapir.EndpointIO.Example

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, U256}
import org.alephium.wallet.api.model._

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
trait WalletExamples {

  private val networkType = NetworkType.Mainnet
  private val lockupScript =
    LockupScript.fromBase58("1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n").get
  private val address            = Address(networkType, lockupScript)
  private val password           = "my-secret-password"
  private val mnemonic           = Mnemonic.generate(Mnemonic.Size.list.last)
  private val walletName         = "wallet-super-name"
  private val mnemonicPassphrase = "optional-mnemonic-passphrase"
  private val fromGroup          = 2
  private val toGroup            = 1

  val mnemonicSizes: String = Mnemonic.Size.list.toSeq.map(_.value).mkString(", ")

  def simpleExample[T](t: T): List[Example[T]] = List(Example(t, None, None))

  implicit val walletCreationExamples: List[Example[WalletCreation]] = List(
    Example(
      WalletCreation(password, None, None, None, None),
      name = None,
      summary = Some("Default")
    ),
    Example(
      WalletCreation(
        password,
        Some(walletName),
        Some(true),
        Some(mnemonicPassphrase),
        Some(Mnemonic.Size.list.head)
      ),
      name = None,
      summary = Some("More settings")
    )
  )
  implicit val walletCreationResultExamples: List[Example[WalletCreation.Result]] =
    simpleExample(WalletCreation.Result(walletName, mnemonic))

  implicit val walletRestoreExamples: List[Example[WalletRestore]] =
    simpleExample(WalletRestore(password, mnemonic, None, None, None))

  implicit val walletRestoreResultExamples: List[Example[WalletRestore.Result]] =
    simpleExample(WalletRestore.Result(walletName))

  implicit val walletStatusExamples: List[Example[AVector[WalletStatus]]] =
    simpleExample(AVector(WalletStatus(walletName, locked = true)))

  implicit val walletUnlockExamples: List[Example[WalletUnlock]] =
    simpleExample(WalletUnlock(password))

  implicit val balancesExamples: List[Example[Balances]] =
    simpleExample(Balances(U256.Million, AVector(Balances.AddressBalance(address, U256.Million))))

  implicit val transferExamples: List[Example[Transfer]] =
    simpleExample(Transfer(address, U256.Million))

  implicit val transferResultExamples: List[Example[Transfer.Result]] =
    simpleExample(Transfer.Result(Hash.generate, fromGroup, toGroup))

  implicit val addressesExamples: List[Example[Addresses]] =
    simpleExample(Addresses(address, AVector(address)))

  implicit val addressExamples: List[Example[Address]] =
    simpleExample(address)

  implicit val addressInfoExamples: List[Example[AddressInfo]] =
    simpleExample(AddressInfo(address, fromGroup))

  implicit val minerAddressesInfoExample: List[Example[AVector[MinerAddressesInfo]]] =
    simpleExample(AVector(MinerAddressesInfo(AVector(AddressInfo(address, fromGroup)))))

  implicit val addressessInfoExamples: List[Example[AVector[AddressInfo]]] =
    simpleExample(AVector(AddressInfo(address, fromGroup)))

  implicit val changeActiveAddressExamples: List[Example[ChangeActiveAddress]] =
    simpleExample(ChangeActiveAddress(address))

  implicit val badRequestExamples: List[Example[WalletApiError.BadRequest]] =
    simpleExample(WalletApiError.BadRequest("Something bad in the request"))

  implicit val notFoundExamples: List[Example[WalletApiError.NotFound]] =
    simpleExample(WalletApiError.NotFound("wallet-name"))

  implicit val unauthorizedExamples: List[Example[WalletApiError.Unauthorized]] =
    simpleExample(WalletApiError.Unauthorized("You shall not pass"))
}
