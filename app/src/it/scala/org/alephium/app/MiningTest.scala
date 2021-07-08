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

import java.net.InetSocketAddress

import org.alephium.api.model._
import org.alephium.flow.mining.{ExternalMinerMock, Miner}
import org.alephium.protocol.model.defaultGasFee
import org.alephium.util._

class MiningTest extends AlephiumSpec {
  it should "work with 2 nodes" in new TestFixture("2-nodes") {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    val server1 = bootNode(publicPort = generatePort, brokerId = 1)
    Seq(server0.start(), server1.start()).foreach(_.futureValue is (()))

    eventually(request[SelfClique](getSelfClique).synced is true)

    val selfClique = request[SelfClique](getSelfClique)
    val group      = request[Group](getGroup(address))
    val index      = group.group / selfClique.groupNumPerBroker
    val restPort   = selfClique.nodes(index).restPort

    request[Balance](getBalance(address), restPort) is initialBalance

    startWS(defaultWsMasterPort)

    val tx = transfer(publicKey, transferAddress, transferAmount, privateKey, restPort)
    selfClique.nodes.foreach { peer => request[Boolean](startMining, peer.restPort) is true }
    confirmTx(tx, restPort)
    eventually {
      request[Balance](getBalance(address), restPort) is
        Balance(initialBalance.balance - transferAmount - defaultGasFee, 0, 1)
    }

    val tx2 = transferFromWallet(transferAddress, transferAmount, restPort)
    confirmTx(tx2, restPort)
    eventually {
      request[Balance](getBalance(address), restPort) is
        Balance(initialBalance.balance - (transferAmount + defaultGasFee) * 2, 0, 1)
    }

    selfClique.nodes.foreach { peer => request[Boolean](stopMining, peer.restPort) is true }
    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }

  it should "work with external miner" in new TestFixture("2-nodes-external-miner") {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    val server1 = bootNode(publicPort = generatePort, brokerId = 1)
    Seq(server0.start(), server1.start()).foreach(_.futureValue is ())

    eventually(request[SelfClique](getSelfClique).synced is true)

    val selfClique = request[SelfClique](getSelfClique)
    val group      = request[Group](getGroup(address))
    val index      = group.group / selfClique.groupNumPerBroker
    val restPort   = selfClique.nodes(index).restPort

    request[Balance](getBalance(address), restPort) is initialBalance

    startWS(server0.config.network.wsPort)
    startWS(server1.config.network.wsPort)

    val tx = transfer(publicKey, transferAddress, transferAmount, privateKey, restPort)

    val minerApiAddress0 = new InetSocketAddress("127.0.0.1", server0.config.network.minerApiPort)
    val minerApiAddress1 = new InetSocketAddress("127.0.0.1", server1.config.network.minerApiPort)
    val miner = system.actorOf(
      ExternalMinerMock.props(networkType, AVector(minerApiAddress0, minerApiAddress1))(
        server0.config.broker,
        server0.config.network,
        server0.config.mining
      )
    )
    miner ! Miner.Start

    eventually {
      val txStatus = request[TxStatus](getTransactionStatus(tx), restPort)
      txStatus is a[Confirmed]
    }

    awaitNBlocksPerChain(1)

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }

  it should "mine all the txs" in new TestFixture("many-txs") {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0, brokerNum = 1)
    Seq(server0.start()).foreach(_.futureValue is ())

    val selfClique = request[SelfClique](getSelfClique)
    val group      = request[Group](getGroup(address))
    val index      = group.group / selfClique.groupNumPerBroker
    val restPort   = selfClique.nodes(index).restPort

    request[Balance](getBalance(address), restPort) is initialBalance

    startWS(defaultWsMasterPort)

    selfClique.nodes.foreach { peer => request[Boolean](startMining, peer.restPort) is true }

    val n = 10

    val txs = (0 until n).map { _ =>
      val tx = transfer(publicKey, transferAddress, transferAmount, privateKey, restPort)
      Thread.sleep(100)
      tx
    }
    txs.foreach(tx => confirmTx(tx, restPort))

    eventually {
      request[Balance](getBalance(address), restPort) is
        Balance(initialBalance.balance - (transferAmount + defaultGasFee) * n, 0, 1)
    }

    selfClique.nodes.foreach { peer => request[Boolean](stopMining, peer.restPort) is true }
    server0.stop().futureValue is ()
  }
}
