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

package org.alephium.wallet.service

import java.io.File
import java.nio.file.Paths

import scala.util.Random

import akka.actor.ActorSystem

import org.alephium.api.model.Destination
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AlephiumFutureSpec, AVector, Duration, U256}
import org.alephium.wallet.config.WalletConfigFixture
import org.alephium.wallet.web.BlockFlowClient

class WalletServiceSpec extends AlephiumFutureSpec {

  it should "handle a miner wallet" in new Fixure {

    val (walletName, _) =
      walletService.createWallet(password, mnemonicSize, true, None, None).rightValue

    val (_, addresses) = walletService.getAddresses(walletName).rightValue

    addresses.length is groupNum

    val minerAddressesWithGroup = walletService.getMinerAddresses(walletName).rightValue

    val groups         = minerAddressesWithGroup.flatMap(_.map { case (groups, _) => groups.value })
    val minerAddresses = minerAddressesWithGroup.flatMap(_.map { case (_, addresses) => addresses })

    groups.length is groupNum
    minerAddresses.length is addresses.length

    (0 to (groupNum - 1)).foreach { group => groups.contains(group) }
    minerAddresses.foreach { address => addresses.contains(address) }

    walletService.deriveNextAddress(walletName) is Left(WalletService.MinerWalletRequired)

    val newMinerAddresses = walletService.deriveNextMinerAddresses(walletName).rightValue

    val minerAddressesWithGroup2 = walletService.getMinerAddresses(walletName).rightValue

    val minerAddresses2 = minerAddressesWithGroup2.tail.head.map { case (_, address) => address }

    minerAddresses2.length is newMinerAddresses.length
    minerAddresses2.foreach(address => newMinerAddresses.contains(address))
  }

  it should "fail to start if secret dir path is invalid" in new Fixure {
    val path = s"/${Random.nextInt()}"
    override lazy val walletService = WalletService(
      blockFlowClient,
      Paths.get(path),
      config.chainId,
      Duration.ofMinutesUnsafe(10)
    )

    if (File.separatorChar equals '/') {
      // This test only works on Unix system
      whenReady(walletService.start().failed) { exception =>
        exception is a[java.nio.file.FileSystemException]
      }
    }
  }

  it should "lock the wallet if inactive" in new Fixure {
    override val lockingTimeout = Duration.ofSecondsUnsafe(1)

    val (walletName, _) =
      walletService.createWallet(password, mnemonicSize, true, None, None).rightValue

    walletService.getAddresses(walletName).isRight is true

    Thread.sleep(1001)

    walletService.getAddresses(walletName).leftValue is WalletService.WalletLocked

    walletService.unlockWallet(walletName, password, None).isRight is true

    walletService.getAddresses(walletName).isRight is true
  }

  it should "return Not Found if wallet doesn't exist" in new Fixure {
    import WalletService.WalletNotFound

    val walletName = "wallet"
    val notFound   = WalletNotFound(new File(tempSecretDir.toString, walletName))
    val address =
      Address.Asset(
        LockupScript.asset("17B4ErFknfmCg381b52k8sKbsXS8RFD7piVpPBB1T2Y4Z").get
      )

    walletService.unlockWallet(walletName, "", None).leftValue is notFound
    walletService.getBalances(walletName).futureValue.leftValue is notFound
    walletService.getAddresses(walletName).leftValue is notFound
    walletService.getMinerAddresses(walletName).leftValue is notFound
    walletService
      .transfer(walletName, AVector(Destination(address, U256.Zero)), None, None)
      .futureValue
      .leftValue is notFound
    walletService.deriveNextAddress(walletName).leftValue is notFound
    walletService.deriveNextMinerAddresses(walletName).leftValue is notFound
    walletService.changeActiveAddress(walletName, address).leftValue is notFound

    //We curently  do an optimist lock
    walletService.lockWallet(walletName) isE ()
  }

  it should "list all wallets even when locked" in new Fixure {

    val (walletName1, _) =
      walletService.createWallet(password, mnemonicSize, false, None, None).rightValue
    val (walletName2, _) =
      walletService.createWallet(password, mnemonicSize, true, None, None).rightValue

    walletService
      .listWallets()
      .map(_.toSet) isE AVector((walletName1, false), (walletName2, false)).toSet

    walletService.lockWallet(walletName1)

    walletService
      .listWallets()
      .map(_.toSet) isE AVector((walletName1, true), (walletName2, false)).toSet
  }

  it should "delete a wallet" in new Fixure {

    val (walletName, _) =
      walletService.createWallet(password, mnemonicSize, false, None, None).rightValue

    walletService
      .listWallets()
      .map(_.toSet) isE AVector((walletName, false)).toSet

    walletService.deleteWallet(walletName, password)

    walletService
      .listWallets()
      .map(_.toSet) isE AVector.empty[(String, Boolean)].toSet
  }

  it should "get back mnemonic" in new Fixure {
    val (walletName, mnemonic) =
      walletService.createWallet(password, mnemonicSize, false, None, None).rightValue

    walletService
      .getMnemonic(walletName, password) isE mnemonic

    walletService
      .getMnemonic(walletName, "wrongPassword")
      .leftValue is WalletService.InvalidPassword
  }

  trait Fixure extends WalletConfigFixture {

    val password     = "password"
    val mnemonicSize = Mnemonic.Size(12).get
    implicit val system: ActorSystem =
      ActorSystem(s"wallet-service-spec-${Random.nextInt()}")
    implicit val executionContext = scala.concurrent.ExecutionContext.Implicits.global
    lazy val blockFlowClient =
      BlockFlowClient.apply(
        config.blockflow.uri,
        config.chainId,
        config.blockflow.blockflowFetchMaxAge,
        config.blockflow.apiKey
      )

    lazy val walletService: WalletService =
      WalletService.apply(blockFlowClient, tempSecretDir, config.chainId, config.lockingTimeout)
  }
}

object WalletServiceSpec extends {}
