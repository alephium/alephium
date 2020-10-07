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

import java.net.InetSocketAddress

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.alephium.appserver.ApiModel._
import org.alephium.util._

class InterCliqueSyncTest extends AlephiumSpec {
  it should "boot and sync two cliques" in new TestFixture("2-cliques-of-2-nodses") {
    val fromTs = TimeStamp.now()

    val clique1           = bootClique(nbOfNodes = 2)
    val masterPortClique1 = clique1.head.config.network.coordinatorAddress.getPort

    Future.sequence(clique1.map(_.start())).futureValue

    startWS(wsPort(masterPortClique1))

    clique1.foreach { server =>
      request[Boolean](startMining, rpcPort(server.config.network.bindAddress.getPort)) is true
    }

    blockNotifyProbe.receiveN(10, Duration.ofMinutesUnsafe(2).asScala)

    clique1.foreach { server =>
      request[Boolean](stopMining, rpcPort(server.config.network.bindAddress.getPort)) is true
    }

    val selfClique1 = request[SelfClique](getSelfClique, rpcPort(masterPortClique1))

    val clique2 =
      bootClique(nbOfNodes = 2,
                 bootstrap = Some(new InetSocketAddress("localhost", masterPortClique1)))
    val masterPortClique2 = clique2.head.config.network.coordinatorAddress.getPort

    Future.sequence(clique2.map(_.start())).futureValue

    clique2.zipWithIndex.foreach {
      case (server, index) =>
        eventually {
          val response =
            request[Seq[InterCliquePeerInfo]](
              getInterCliquePeerInfo,
              rpcPort(server.config.network.bindAddress.getPort)).head

          response.cliqueId is selfClique1.cliqueId
          response.brokerId is index
          response.isSynced is true
        }
    }

    val toTs = TimeStamp.now()

    eventually {
      request[FetchResponse](blockflowFetch(fromTs, toTs), rpcPort(masterPortClique1)).blocks.toSet is
        request[FetchResponse](blockflowFetch(fromTs, toTs), rpcPort(masterPortClique2)).blocks.toSet
    }
  }
}
