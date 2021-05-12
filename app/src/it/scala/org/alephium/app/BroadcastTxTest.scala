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
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util._

class BroadcastTxTest extends AlephiumSpec {
  it should "broadcast tx between intra clique node" in new TestFixture("broadcast-tx-2-nodes") {
    val port2   = generatePort()
    val server1 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    val server2 = bootNode(publicPort = port2, brokerId = 1)
    Seq(server1.start(), server2.start()).foreach(_.futureValue is (()))

    eventually(request[SelfClique](getSelfClique).synced is true)

    val selfClique1 = request[SelfClique](getSelfClique)
    val group1      = request[Group](getGroup(address))
    val index1      = group1.group / selfClique1.groupNumPerBroker
    val restPort1   = selfClique1.nodes(index1).restPort

    val selfClique2 = request[SelfClique](getSelfClique, restPort(port2))
    val group2      = request[Group](getGroup(address), restPort(port2))
    val index2      = group2.group / selfClique2.groupNumPerBroker
    val restPort2   = selfClique2.nodes(index2).restPort

    val tx = transfer(publicKey, transferAddress, transferAmount, privateKey, restPort1)

    eventually(request[TxStatus](getTransactionStatus(tx), restPort1) is MemPooled)
    eventually(request[TxStatus](getTransactionStatus(tx), restPort2) is MemPooled)

    server1.stop().futureValue is ()
    server2.stop().futureValue is ()
  }

  it should "broadcast sequential txs between inter clique node" in new TestFixture(
    "broadcast-tx-inter-clique"
  ) {

    val clique1           = bootClique(nbOfNodes = 1)
    val masterPortClique1 = clique1.head.config.network.coordinatorAddress.getPort

    clique1.map(_.start()).foreach(_.futureValue is (()))
    val selfClique1 = request[SelfClique](getSelfClique, restPort(masterPortClique1))

    val clique2 =
      bootClique(
        nbOfNodes = 1,
        bootstrap = Some(new InetSocketAddress("localhost", masterPortClique1))
      )
    val masterPortClique2 = clique2.head.config.network.coordinatorAddress.getPort

    clique2.map(_.start()).foreach(_.futureValue is (()))
    clique2.foreach { server =>
      eventually {
        val interCliquePeers =
          request[Seq[InterCliquePeerInfo]](
            getInterCliquePeerInfo,
            restPort(server.config.network.bindAddress.getPort)
          ).head
        interCliquePeers.cliqueId is selfClique1.cliqueId
        interCliquePeers.isSynced is true

        val discoveredNeighbors =
          request[Seq[BrokerInfo]](
            getDiscoveredNeighbors,
            restPort(server.config.network.bindAddress.getPort)
          )
        discoveredNeighbors.length is 2
      }
    }

    val tx0 =
      transfer(publicKey, transferAddress, transferAmount, privateKey, restPort(masterPortClique1))
    eventually(
      request[TxStatus](getTransactionStatus(tx0), restPort(masterPortClique1)) is MemPooled
    )
    eventually(
      request[TxStatus](getTransactionStatus(tx0), restPort(masterPortClique2)) is MemPooled
    )

    val tx1 =
      transfer(publicKey, transferAddress, transferAmount, privateKey, restPort(masterPortClique1))
    eventually(
      request[TxStatus](getTransactionStatus(tx1), restPort(masterPortClique1)) is MemPooled
    )
    eventually(
      request[TxStatus](getTransactionStatus(tx1), restPort(masterPortClique2)) is NotFound
    )

    clique2.foreach { server =>
      request[Boolean](startMining, restPort(server.config.network.bindAddress.getPort)) is true
    }

    eventually(
      request[TxStatus](getTransactionStatus(tx0), restPort(masterPortClique1)) is a[Confirmed]
    )
    eventually(
      request[TxStatus](getTransactionStatus(tx1), restPort(masterPortClique1)) is a[Confirmed]
    )

    clique1.foreach(_.stop().futureValue is ())
    clique2.foreach(_.stop().futureValue is ())
  }
}
