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

import sttp.model.StatusCode

import org.alephium.api.model._
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util._

class BroadcastTxTest extends AlephiumSpec {
  it should "not broadcast tx between intra clique node" in new TestFixture(
    "broadcast-tx-2-nodes"
  ) {
    val clique = bootClique(nbOfNodes = 2)
    clique.start()

    val restPort1 = clique.servers(0).config.network.restPort
    val restPort2 = clique.servers(1).config.network.restPort
    val tx        = transfer(publicKey, transferAddress, transferAmount, privateKey, restPort1)
    eventually(request[TxStatus](getTransactionStatus(tx), restPort1) is MemPooled)
    eventually(requestFailed(getTransactionStatus(tx), restPort2, StatusCode.BadRequest))

    clique.stop()
  }

  it should "broadcast sequential txs between inter clique node" in new TestFixture(
    "broadcast-tx-inter-clique"
  ) {
    val clique1           = bootClique(nbOfNodes = 1)
    val masterPortClique1 = clique1.masterTcpPort

    clique1.start()
    val selfClique1 = clique1.selfClique()

    val clique2 =
      bootClique(
        nbOfNodes = 1,
        bootstrap = Some(new InetSocketAddress("127.0.0.1", masterPortClique1))
      )
    clique2.start()
    val masterPortClique2 = clique2.masterTcpPort

    clique2.servers.foreach { server =>
      eventually(request[SelfClique](getSelfClique, server.config.network.restPort).synced is true)
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
    checkTx(tx0, restPort(masterPortClique1), MemPooled)
    checkTx(tx0, restPort(masterPortClique2), MemPooled)

    val tx1 =
      transfer(publicKey, transferAddress, transferAmount, privateKey, restPort(masterPortClique1))
    checkTx(tx1, restPort(masterPortClique1), MemPooled)
    checkTx(tx1, restPort(masterPortClique2), NotFound)

    clique2.startMining()

    confirmTx(tx0, restPort(masterPortClique1))
    confirmTx(tx1, restPort(masterPortClique1))

    clique1.stop()
    clique2.stop()
  }
}
