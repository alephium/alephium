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

import org.alephium.api.model.{Amount, Destination}
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.json.Json._
import org.alephium.protocol.{ALPH, Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util._
import org.alephium.wallet.api.model._

class ModelCodecsSpec extends AlephiumSpec with ModelCodecs {

  implicit val groupConfig =
    new GroupConfig {
      override def groups: Int = 4
    }

  val blockflowFetchMaxAge = Duration.unsafe(1000)
  val address              = Address.p2pkh(PublicKey.generate)
  val group                = GroupIndex.unsafe(1)
  val balance              = Amount(ALPH.oneAlph)
  val lockedBalance        = Amount(ALPH.alph(2))
  val hash                 = Hash.generate
  val password             = "password"
  val walletName           = "wallet-name"
  val mnemonicPassphrase   = "mnemonic-passphrase"
  val mnemonicSize         = Mnemonic.Size.list.last
  val mnemonic             = Mnemonic.generate(mnemonicSize)
  val bool                 = true
  val publicKey = PublicKey
    .from(Hex.unsafe("0362a56b41565582ec52c78f6adf76d7afdcf4b7584682011b0caa6846c3f44819"))
    .get
  val path = "m/44'/1234'/0'/0/0"

  def check[T: ReadWriter](input: T, rawJson: String): Assertion = {
    write(input) is rawJson
    read[T](rawJson) is input
  }

  def parseFail[A: Reader](jsonRaw: String): String = {
    scala.util.Try(read[A](jsonRaw)).toEither.swap.rightValue.getMessage
  }

  it should "Addresses" in {
    val json =
      s"""{"activeAddress":"$address","addresses":[{"address":"$address","publicKey":"${publicKey.toHexString}","group":${group.value},"path":"$path"}]}"""
    val addresses = Addresses(address, AVector(AddressInfo(address, publicKey, group, path)))
    check(addresses, json)
  }

  it should "AddressInfo" in {
    val json =
      s"""{"address":"$address","publicKey":"${publicKey.toHexString}","group":${group.value},"path":"$path"}"""
    val addressInfo = AddressInfo(address, publicKey, group, path)
    check(addressInfo, json)
  }

  it should "Balances.AddressBalance" in {
    val json =
      s"""{"address":"$address","balance":"$balance","balanceHint":"1 ALPH","lockedBalance":"$lockedBalance","lockedBalanceHint":"2 ALPH"}"""
    val addressBalance = Balances.AddressBalance.from(address, balance, lockedBalance)
    check(addressBalance, json)
  }

  it should "Balances" in {
    val json =
      s"""{"totalBalance":"$balance","totalBalanceHint":"1 ALPH","balances":[{"address":"$address","balance":"$balance","balanceHint":"1 ALPH","lockedBalance":"$lockedBalance","lockedBalanceHint":"2 ALPH"}]}"""
    val balances =
      Balances.from(balance, AVector(Balances.AddressBalance.from(address, balance, lockedBalance)))
    check(balances, json)
  }

  it should "ChangeActiveAddress" in {
    val json                = s"""{"address":"$address"}"""
    val changeActiveAddress = ChangeActiveAddress(address)
    check(changeActiveAddress, json)
  }

  it should "Transfer" in {
    val json     = s"""{"destinations":[{"address":"$address","alphAmount":"$balance"}]}"""
    val transfer = Transfer(AVector(Destination(address, balance)))
    check(transfer, json)
  }

  it should "TransferResult" in {
    val json =
      s"""{"txId":"${hash.toHexString}","fromGroup":${group.value},"toGroup":${group.value}}"""
    val transfer = TransferResult(hash, group, group)
    check(transfer, json)
  }

  it should "Mnemonic" in {
    val json = s""""${mnemonic.toLongString}""""
    check(mnemonic, json)

    val wrongMnemonic = "two words"
    parseFail[Mnemonic](
      s""""$wrongMnemonic""""
    ) is s"Cannot validate mnemonic: $wrongMnemonic at index 0"
  }

  it should "WalletUnlock" in {
    val json         = s"""{"password":"$password"}"""
    val walletUnlock = WalletUnlock(password, None)
    check(walletUnlock, json)

    val json2         = s"""{"password":"$password","mnemonicPassphrase":"$mnemonicPassphrase"}"""
    val walletUnlock2 = WalletUnlock(password, Some(mnemonicPassphrase))
    check(walletUnlock2, json2)
  }

  it should "WalletDeletion" in {
    val json           = s"""{"password":"$password"}"""
    val walletDeletion = WalletDeletion(password)
    check(walletDeletion, json)
  }

  it should "WalletRestore" in {
    val json1 =
      s"""{"password":"$password","mnemonic":"${mnemonic.toLongString}","walletName":"$walletName"}"""
    val walletRestore1 = WalletRestore(password, mnemonic, walletName)
    check(walletRestore1, json1)

    val json2 =
      s"""{"password":"$password","mnemonic":"${mnemonic.toLongString}","walletName":"$walletName","isMiner":$bool,"mnemonicPassphrase":"$mnemonicPassphrase"}"""
    val walletRestore2 =
      WalletRestore(password, mnemonic, walletName, Some(bool), Some(mnemonicPassphrase))
    check(walletRestore2, json2)
  }

  it should "WalletRestoreResult" in {
    val json                = s"""{"walletName":"$walletName"}"""
    val walletRestoreResult = WalletRestoreResult(walletName)
    check(walletRestoreResult, json)
  }

  it should "WalletCreation" in {
    val json1           = s"""{"password":"$password","walletName":"$walletName"}"""
    val walletCreation1 = WalletCreation(password, walletName)
    check(walletCreation1, json1)

    val json2 =
      s"""{"password":"$password","walletName":"$walletName","isMiner":$bool,"mnemonicPassphrase":"$mnemonicPassphrase","mnemonicSize":${mnemonicSize.value}}"""
    val walletCreation2 = WalletCreation(
      password,
      walletName,
      Some(bool),
      Some(mnemonicPassphrase),
      Some(mnemonicSize)
    )
    check(walletCreation2, json2)
  }

  it should "WalletCreationResult" in {
    val json = s"""{"walletName":"$walletName","mnemonic":"${mnemonic.toLongString}"}"""
    val walletCreationResult = WalletCreationResult(walletName, mnemonic)
    check(walletCreationResult, json)
  }

  it should "WalletStatus" in {
    val json         = s"""{"walletName":"$walletName","locked":$bool}"""
    val walletStatus = WalletStatus(walletName, bool)
    check(walletStatus, json)
  }
}
