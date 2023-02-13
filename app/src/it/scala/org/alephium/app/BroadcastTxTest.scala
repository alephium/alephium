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
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{Address, BrokerInfo, GroupIndex}
import org.alephium.util._

class BroadcastTxTest extends AlephiumActorSpec {
  it should "broadcast cross-group txs inside a clique" in new CliqueFixture {
    val clique = bootClique(nbOfNodes = 2)
    clique.start()

    val fromAddressGroup = Address.fromBase58(address).get.groupIndex

    {
      info("intra-group transaction")
      val tx =
        transfer(publicKey, address, transferAmount, privateKey, clique.masterRestPort)
      val restPort1 = clique.getServer(tx.fromGroup).config.network.restPort
      tx.fromGroup is tx.toGroup
      eventually {
        clique.getServer(0).node.blockFlow.getMemPool(fromAddressGroup).contains(tx.txId) is true
        assertThrows[AssertionError](
          clique.getServer(1).node.blockFlow.getMemPool(fromAddressGroup).contains(tx.txId)
        )
      }

      clique.startMining()
      confirmTx(tx, restPort1)
      clique.stopMining()
    }

    {
      info("cross-group transaction")
      val toAddressGroup       = GroupIndex.unsafe(fromAddressGroup.value + 1)
      val (toPriKey, toPubKey) = toAddressGroup.generateKey
      val toAddress            = Address.p2pkh(toPubKey)
      val tx0 =
        transfer(publicKey, toAddress.toBase58, transferAmount, privateKey, clique.masterRestPort)
      tx0.fromGroup is fromAddressGroup.value
      tx0.toGroup is toAddressGroup.value

      eventually {
        clique.getServer(0).node.blockFlow.getMemPool(fromAddressGroup).contains(tx0.txId) is true
        clique.getServer(1).node.blockFlow.getMemPool(toAddressGroup).contains(tx0.txId) is true
      }

      val tx1 = transfer(
        toPubKey.toHexString,
        address,
        transferAmount.divUnsafe(2),
        toPriKey.toHexString,
        clique.masterRestPort
      )
      tx1.fromGroup is toAddressGroup.value
      tx1.toGroup is fromAddressGroup.value

      eventually {
        clique.getServer(0).node.blockFlow.getMemPool(fromAddressGroup).contains(tx1.txId) is true
        clique.getServer(1).node.blockFlow.getMemPool(toAddressGroup).contains(tx1.txId) is true
      }
    }

    clique.stop()
  }

  it should "broadcast sequential txs between inter clique node" in new CliqueFixture {
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

    var amount = ALPH.alph(4096)
    val transactions = AVector.tabulate(12) { k =>
      amount = amount.divUnsafe(2)
      val tx = (k % 4) match {
        case 0 =>
          transfer(
            publicKey,
            transferAddress,
            amount,
            privateKey,
            restPort(masterPortClique1)
          )
        case 1 =>
          transfer(
            transferPubKey,
            address,
            amount,
            transferPriKey,
            restPort(masterPortClique1)
          )
        case 2 =>
          transfer(
            publicKey,
            address,
            amount,
            privateKey,
            restPort(masterPortClique1)
          )
        case 3 =>
          transfer(
            transferPubKey,
            transferAddress,
            amount,
            transferPriKey,
            restPort(masterPortClique1)
          )
      }
      tx
    }
    transactions.foreach(checkTx(_, restPort(masterPortClique1), MemPooled()))
    transactions.foreach(checkTx(_, restPort(masterPortClique2), MemPooled()))
    transactions.foreach(tx => txNotInBlocks(tx.txId, restPort(masterPortClique1)))
    transactions.foreach(tx => txNotInBlocks(tx.txId, restPort(masterPortClique2)))

    clique2.startFakeMining()

    transactions.foreach { tx =>
      confirmTx(tx, restPort(masterPortClique1))
      getTransaction(tx.txId, restPort(masterPortClique1))
    }

    clique2.stopFakeMining()
    clique1.stop()
    clique2.stop()
  }
}
