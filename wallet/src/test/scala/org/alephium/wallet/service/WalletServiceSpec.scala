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

import java.nio.file.Paths

import akka.actor.ActorSystem
import org.scalatest.concurrent.ScalaFutures

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.util.{AlephiumSpec, Duration, Random}
import org.alephium.wallet.config.WalletConfigFixture
import org.alephium.wallet.web.BlockFlowClient

class WalletServiceSpec extends AlephiumSpec with ScalaFutures {

  it should "handle a miner wallet" in new Fixure {

    val (walletName, _) =
      walletService.createWallet(password, mnemonicSize, true, None, None).rightValue

    val (_, addresses) = walletService.getAddresses(walletName).rightValue

    addresses.length is groupNum

    val minerAddressesWithGroup = walletService.getMinerAddresses(walletName).rightValue

    val groups         = minerAddressesWithGroup.flatMap(_.map { case (groups, _)    => groups.value })
    val minerAddresses = minerAddressesWithGroup.flatMap(_.map { case (_, addresses) => addresses })

    groups.length is groupNum
    minerAddresses.length is addresses.length

    (0 to (groupNum - 1)).foreach { group =>
      groups.contains(group)
    }
    minerAddresses.foreach { address =>
      addresses.contains(address)
    }

    walletService.deriveNextAddress(walletName) is Left(WalletService.MinerWalletRequired)

    val newMinerAddresses = walletService.deriveNextMinerAddresses(walletName).rightValue

    val minerAddressesWithGroup2 = walletService.getMinerAddresses(walletName).rightValue

    val minerAddresses2 = minerAddressesWithGroup2.tail.head.map { case (_, address) => address }

    minerAddresses2.length is newMinerAddresses.length
    minerAddresses2.foreach(address => newMinerAddresses.contains(address))
  }

  it should "fail to start if secret dir path is invalid" in new Fixure {
    val path = s"/${Random.source.nextInt}"
    override lazy val walletService = WalletService(
      blockFlowClient,
      Paths.get(path),
      config.networkType,
      Duration.ofMinutesUnsafe(10)
    )

    whenReady(walletService.start().failed) { exception =>
      exception is a[java.nio.file.AccessDeniedException]
    }
  }

  it should "lock the wallet if inactive" in new Fixure {
    override val lockingTimeout = Duration.ofSecondsUnsafe(1)

    val (walletName, _) =
      walletService.createWallet(password, mnemonicSize, true, None, None).rightValue

    walletService.getAddresses(walletName).isRight is true

    Thread.sleep(1001)

    walletService.getAddresses(walletName).leftValue is WalletService.WalletLocked

    walletService.unlockWallet(walletName, password).isRight is true

    walletService.getAddresses(walletName).isRight is true
  }

  trait Fixure extends WalletConfigFixture {

    val password     = "password"
    val mnemonicSize = Mnemonic.Size(12).get
    implicit val system: ActorSystem =
      ActorSystem(s"wallet-service-spec-${Random.source.nextInt}")
    implicit val executionContext = system.dispatcher
    lazy val blockFlowClient =
      BlockFlowClient.apply(config.blockflow.uri,
                            config.networkType,
                            config.blockflow.blockflowFetchMaxAge)

    lazy val walletService: WalletService =
      WalletService.apply(blockFlowClient, tempSecretDir, config.networkType, config.lockingTimeout)
  }
}

object WalletServiceSpec extends {}
