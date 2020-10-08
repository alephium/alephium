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

package org.alephium.appserver

import org.alephium.appserver.ApiModel._
import org.alephium.util._

class MiningTest extends AlephiumSpec {
  it should "work with 2 nodes" in new TestFixture("2-nodes") {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    val server1 = bootNode(publicPort = generatePort, brokerId      = 1)
    Seq(server0.start(), server1.start()).foreach(_.futureValue is (()))

    val selfClique = request[SelfClique](getSelfClique)
    val group      = request[Group](getGroup(address))
    val index      = group.group / selfClique.groupNumPerBroker
    val rpcPort    = selfClique.peers(index).rpcPort

    request[Balance](getBalance(address), rpcPort) is initialBalance

    startWS(defaultWsMasterPort)

    val tx = transfer(publicKey, transferAddress, transferAmount, privateKey, rpcPort)

    selfClique.peers.foreach { peer =>
      request[Boolean](startMining, peer.rpcPort) is true
    }

    awaitNewBlock(tx.fromGroup, tx.toGroup)
    Thread.sleep(1000)
    awaitNewBlock(tx.fromGroup, tx.fromGroup)

    eventually {
      request[Balance](getBalance(address), rpcPort) is
        Balance(initialBalance.balance - transferAmount, 1)
    }

    val tx2 = transfer(publicKey, transferAddress, transferAmount, privateKey, rpcPort)

    awaitNewBlock(tx2.fromGroup, tx2.toGroup)
    Thread.sleep(1000)
    awaitNewBlock(tx2.fromGroup, tx2.fromGroup)

    selfClique.peers.foreach { peer =>
      request[Boolean](stopMining, peer.rpcPort) is true
    }

    eventually {
      request[Balance](getBalance(address), rpcPort) is
        Balance(initialBalance.balance - (2 * transferAmount), 1)
    }

    server1.stop()
    server0.stop()
  }
}
