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

package org.alephium.wallet.json

import org.scalatest.Assertion

import org.alephium.api.model.{AddressInfo, Destination}
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.json.Json._
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.model._
import org.alephium.util._
import org.alephium.wallet.api.model._

class ModelCodecsSpec extends AlephiumSpec with ModelCodecs {

  val blockflowFetchMaxAge = Duration.unsafe(1000)
  val networkType          = NetworkType.Mainnet
  val address              = Address.p2pkh(networkType, PublicKey.generate)
  val group                = 1
  val balance              = U256.One
  val hash                 = Hash.generate
  val password             = "password"
  val walletName           = "wallet-name"
  val mnemonicPassphrase   = "mnemonic-passphrase"
  val mnemonicSize         = Mnemonic.Size.list.last
  val mnemonic             = Mnemonic.generate(mnemonicSize)
  val bool                 = true

  def check[T: ReadWriter](input: T, rawJson: String): Assertion = {
    write(input) is rawJson
    read[T](rawJson) is input
  }

  it should "Addresses" in {
    val json      = s"""{"activeAddress":"$address","addresses":["$address"]}"""
    val addresses = Addresses(address, AVector(address))
    check(addresses, json)
  }

  it should "MinerAddressesInfo" in {
    val json               = s"""{"addresses":[{"address":"$address","group":$group}]}"""
    val minerAddressesInfo = MinerAddressesInfo(AVector(AddressInfo(address, group)))
    check(minerAddressesInfo, json)
  }

  it should "Balances.AddressBalance" in {
    val json           = s"""{"address":"$address","balance":"$balance"}"""
    val addressBalance = Balances.AddressBalance(address, balance)
    check(addressBalance, json)
  }

  it should "Balances" in {
    val json =
      s"""{"totalBalance":"$balance","balances":[{"address":"$address","balance":"$balance"}]}"""
    val balances = Balances(balance, AVector(Balances.AddressBalance(address, balance)))
    check(balances, json)
  }

  it should "ChangeActiveAddress" in {
    val json                = s"""{"address":"$address"}"""
    val changeActiveAddress = ChangeActiveAddress(address)
    check(changeActiveAddress, json)
  }

  it should "DeriveNextAddress.Result" in {
    val json                    = s"""{"address":"$address"}"""
    val deriveNextAddressResult = DeriveNextAddress.Result(address)
    check(deriveNextAddressResult, json)
  }

  it should "Transfer" in {
    val json     = s"""{"destinations":[{"address":"$address","amount":"$balance"}]}"""
    val transfer = Transfer(AVector(Destination(address, balance)))
    check(transfer, json)
  }

  it should "Transfer.Result" in {
    val json     = s"""{"txId":"${hash.toHexString}","fromGroup":$group,"toGroup":$group}"""
    val transfer = Transfer.Result(hash, group, group)
    check(transfer, json)
  }

  it should "Mnemonic" in {
    val json = s""""${mnemonic.toLongString}""""
    check(mnemonic, json)
  }

  it should "WalletUnlock" in {
    val json         = s"""{"password":"$password"}"""
    val walletUnlock = WalletUnlock(password)
    check(walletUnlock, json)
  }

  it should "WalletDeletion" in {
    val json           = s"""{"password":"$password"}"""
    val walletDeletion = WalletDeletion(password)
    check(walletDeletion, json)
  }

  it should "WalletRestore" in {
    val json1          = s"""{"password":"$password","mnemonic":"${mnemonic.toLongString}"}"""
    val walletRestore1 = WalletRestore(password, mnemonic)
    check(walletRestore1, json1)

    val json2 =
      s"""{"password":"$password","mnemonic":"${mnemonic.toLongString}","isMiner":$bool,"walletName":"$walletName","mnemonicPassphrase":"$mnemonicPassphrase"}"""
    val walletRestore2 =
      WalletRestore(password, mnemonic, Some(bool), Some(walletName), Some(mnemonicPassphrase))
    check(walletRestore2, json2)
  }

  it should "WalletRestore.Result" in {
    val json                = s"""{"walletName":"$walletName"}"""
    val walletRestoreResult = WalletRestore.Result(walletName)
    check(walletRestoreResult, json)
  }

  it should "WalletCreation" in {
    val json1           = s"""{"password":"$password"}"""
    val walletCreation1 = WalletCreation(password)
    check(walletCreation1, json1)

    val json2 =
      s"""{"password":"$password","walletName":"$walletName","isMiner":$bool,"mnemonicPassphrase":"$mnemonicPassphrase","mnemonicSize":${mnemonicSize.value}}"""
    val walletCreation2 = WalletCreation(
      password,
      Some(walletName),
      Some(bool),
      Some(mnemonicPassphrase),
      Some(mnemonicSize)
    )
    check(walletCreation2, json2)
  }

  it should "WalletCreation.Result" in {
    val json                 = s"""{"walletName":"$walletName","mnemonic":"${mnemonic.toLongString}"}"""
    val walletCreationResult = WalletCreation.Result(walletName, mnemonic)
    check(walletCreationResult, json)
  }

  it should "WalletStatus" in {
    val json         = s"""{"walletName":"$walletName","locked":$bool}"""
    val walletStatus = WalletStatus(walletName, bool)
    check(walletStatus, json)
  }
}
