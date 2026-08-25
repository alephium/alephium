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

import scala.concurrent.{ExecutionContext, Future, blocking}

import org.alephium.api.model._
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{Address, dustUtxoAmount, nonCoinbaseMinGasFee}
import org.alephium.util._
import org.alephium.wallet.api.model._

abstract class SweepTest(isMiner: Boolean) extends AlephiumActorSpec {

  it should "sweep amounts from the active address" in new SweepFixture {
    val transfer =
      request[TransferResults](sweepActiveAddress(walletName, transferAddress), restPort)
    transfer.results.length is sweepTransactionCount

    eventually {
      // active address is swept
      val balance = request[Balance](getBalance(activeAddress.toBase58), restPort)
      balance.balance.value is 0

      val balances = request[Balances](walletBalances(walletName), restPort)

      // all other addresses are not swept
      addresses.filterNot(_.equals(activeAddress)).foreach { addr =>
        balances.balances.find(_.address.equals(addr)).value.balance.value is amountPerAddress
      }
    }

    val transfer1 =
      request[TransferResults](sweepActiveAddress(walletName, transferAddress), restPort)
    transfer1.results.length is 0

    clique.stopMining()
    clique.stop()
  }

  it should "sweep amounts from all addresses" in new SweepFixture {
    val transfer =
      request[TransferResults](sweepAllAddresses(walletName, transferAddress), restPort)
    transfer.results.length is sweepTransactionCount * numberOfAddresses

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

    clique.stopMining()
    clique.stop()
  }

  trait SweepFixture extends CliqueFixture {
    val sweepTransactionCount = 5
    val fundingOutputCount =
      (sweepTransactionCount - 1) * ALPH.MaxTxInputNum + 1
    val fundingOutputAmount = dustUtxoAmount.addUnsafe(nonCoinbaseMinGasFee)
    val amountPerAddress    = fundingOutputAmount * fundingOutputCount

    val clique = bootClique(
      nbOfNodes = 1,
      configOverrides = Map("alephium.api.default-utxos-limit" -> fundingOutputCount)
    )
    clique.start()
    clique.startWsAndWaitConnection()

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

    val fundingDestinations = addresses.map { address =>
      AVector
        .fill(fundingOutputCount)(Destination(address, Some(Amount(fundingOutputAmount))))
        .groupedWithRemainder(ALPH.MaxTxOutputNum - 1)
    }
    val fundingAccounts = addresses.map(address => generateAccount(address.groupIndex))

    clique.startMining()

    fundingAccounts.foreach { case (fundingAddress, _, _) =>
      val fundAccountTx = transfer(
        publicKey,
        AVector(
          Destination(
            Address.asset(fundingAddress).rightValue,
            Some(Amount(amountPerAddress.addUnsafe(ALPH.oneAlph)))
          )
        ),
        privateKey,
        restPort
      )
      confirmTx(fundAccountTx, restPort)
    }

    {
      implicit val executionContext: ExecutionContext = system.dispatcher
      val fundingLanes = fundingAccounts.mapWithIndex {
        case ((_, fundingPublicKey, fundingPrivateKey), accountIndex) =>
          Future {
            blocking {
              (0 until sweepTransactionCount).foreach { batchIndex =>
                val fundingTx = transfer(
                  fundingPublicKey,
                  fundingDestinations(accountIndex)(batchIndex),
                  fundingPrivateKey,
                  restPort
                )
                confirmTx(fundingTx, restPort)
              }
            }
          }
      }
      discard(Future.sequence(fundingLanes.toSeq).futureValue)
    }

    eventually {
      request[Balance](getBalance(address), restPort).balance.value < initialBalance.balance.value
    }

    val balances = request[Balances](walletBalances(walletName), restPort)
    balances.totalBalance.value is amountPerAddress * numberOfAddresses

    addresses.foreach { address =>
      val balance = request[Balance](getBalance(address.toBase58), restPort)
      balance.balance.value is amountPerAddress
      balance.utxoNum is fundingOutputCount
    }

    addresses.foreach { address =>
      balances.balances.find(_.address.equals(address)).value.balance.value is amountPerAddress
    }
  }
}

class SweepMiner     extends SweepTest(true)
class SweepNoneMiner extends SweepTest(false)
