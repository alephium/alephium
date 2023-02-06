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
import org.alephium.flow.mining.Miner
import org.alephium.protocol.model.nonCoinbaseMinGasFee
import org.alephium.util._

class MiningTest extends AlephiumActorSpec {
  class Fixture(numNodes: Int) extends CliqueFixture {
    val clique = bootClique(nbOfNodes = numNodes)
    clique.start()
    clique.startWs()

    val selfClique = clique.selfClique()
    val restPort   = clique.getRestPort(clique.getGroup(address).group)

    request[Balance](getBalance(address), restPort) is initialBalance
  }

  it should "work with 2 nodes" in new Fixture(2) {
    val tx = transfer(publicKey, transferAddress, transferAmount, privateKey, restPort)
    clique.startMining()
    confirmTx(tx, restPort)
    eventually {
      request[Balance](getBalance(address), restPort) is
        Balance.from(
          Amount(initialBalance.balance.value - transferAmount - nonCoinbaseMinGasFee),
          Amount.Zero,
          None,
          None,
          1
        )
    }

    val tx2 = transferFromWallet(transferAddress, transferAmount, clique.masterRestPort)
    confirmTx(tx2, restPort)
    eventually {
      request[Balance](getBalance(address), restPort) is
        Balance.from(
          Amount(initialBalance.balance.value - (transferAmount + nonCoinbaseMinGasFee) * 2),
          Amount.Zero,
          None,
          None,
          1
        )
    }

    clique.stopMining()
    clique.stop()
    clique.servers.foreach(_.flowSystem.whenTerminated.futureValue)
  }

  it should "work with external miner" in new Fixture(2) {
    val tx = transfer(publicKey, transferAddress, transferAmount, privateKey, restPort)

    val server0 = clique.servers(0)
    val server1 = clique.servers(1)
    val apiAddresses =
      s"127.0.0.1:${server0.config.network.minerApiPort},127.0.0.1:${server1.config.network.minerApiPort}"
    val soloMiner = new CpuSoloMiner(server0.config, server0.flowSystem, Some(apiAddresses))

    confirmTx(tx, restPort)
    eventually {
      val txStatus = request[TxStatus](getTransactionStatus(tx), restPort)
      txStatus is a[Confirmed]
    }

    awaitNBlocksPerChain(1)

    soloMiner.miner ! Miner.Stop
    clique.stop()
  }

  it should "mine all the txs" in new Fixture(1) {
    clique.startMining()

    val n = 10
    val txs = (0 until n).map { _ =>
      val tx = transfer(publicKey, transferAddress, transferAmount, privateKey, restPort)
      Thread.sleep(100)
      tx
    }
    txs.foreach(tx => confirmTx(tx, restPort))

    eventually {
      request[Balance](getBalance(address), restPort) is
        Balance.from(
          Amount(initialBalance.balance.value - (transferAmount + nonCoinbaseMinGasFee) * n),
          Amount.Zero,
          None,
          None,
          1
        )
    }

    clique.stopMining()
    clique.stop()
  }
}
