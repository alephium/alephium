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

package org.alephium.app

import org.alephium.api.model._
import org.alephium.json.Json._
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.defaultGasFee
import org.alephium.util._
import org.alephium.wallet.api.model._

abstract class SweepTest(isMiner: Boolean) extends AlephiumActorSpec {

  it should "sweep amounts from the active address" in new SweepFixture {
    val transfer =
      request[TransferResults](sweepActiveAddress(walletName, transferAddress), restPort)
    transfer.results.length is 1

    eventually {
      // active address is swept
      val balance = request[Balance](getBalance(activeAddress.toBase58), restPort)
      balance.balance.value is 0

      val balances = request[Balances](walletBalances(walletName), restPort)

      // all other addresses are not swept
      addresses.filterNot(_.equals(activeAddress)).foreach { addr =>
        balances.balances.find(_.address.equals(addr)).value.balance.value is ALPH.alph(1)
      }
    }

    val transfer1 =
      request[TransferResults](sweepActiveAddress(walletName, transferAddress), restPort)
    transfer1.results.length is 0
  }

  it should "sweep amounts from all addresses" in new SweepFixture {
    val transfer =
      request[TransferResults](sweepAllAddresses(walletName, transferAddress), restPort)
    transfer.results.length is numberOfAddresses

    eventually {
      val balances = request[Balances](walletBalances(walletName), restPort)
      balances.totalBalance.value is ALPH.alph(0)

      // all addresses are swept
      addresses.foreach { addr =>
        balances.balances.find(_.address.equals(addr)).value.balance.value is ALPH.alph(0)
      }
    }

    val transfer1 =
      request[TransferResults](sweepAllAddresses(walletName, transferAddress), restPort)
    transfer1.results.length is 0
  }

  trait SweepFixture extends CliqueFixture {
    val clique = bootClique(nbOfNodes = 1)
    clique.start()
    clique.startWs()

    val group    = request[Group](getGroup(address), clique.masterRestPort)
    val restPort = clique.getRestPort(group.group)

    request[Balance](getBalance(address), restPort) is initialBalance

    val numberOfAddresses: Int = if (isMiner) 4 else 1

    val walletName = "miner-wallet"
    request[WalletCreationResult](createWallet(password, walletName, isMiner), restPort)

    val addressesResponse = request[Addresses](getAddresses(walletName), restPort)
    val addresses         = addressesResponse.addresses.map(_.address)
    val activeAddress     = addressesResponse.activeAddress
    addresses.length is numberOfAddresses

    val txs = addresses.map { address =>
      transfer(publicKey, address.toBase58, transferAmount, privateKey, restPort)
    }

    clique.startMining()

    txs.foreach(confirmTx(_, restPort))

    eventually {
      request[Balance](getBalance(address), restPort) is
        Balance.from(
          Amount(
            initialBalance.balance.value - (transferAmount + defaultGasFee) * numberOfAddresses
          ),
          Amount.Zero,
          1
        )
    }

    val balances = request[Balances](walletBalances(walletName), restPort)
    balances.totalBalance.value is ALPH.alph(1) * numberOfAddresses

    request[Balance](getBalance(activeAddress.toBase58), restPort).balance.value is ALPH.alph(1)

    addresses.foreach { address =>
      balances.balances.find(_.address.equals(address)).value.balance.value is ALPH.alph(1)
    }
  }
}

class SweepMiner     extends SweepTest(true)
class SweepNoneMiner extends SweepTest(false)
