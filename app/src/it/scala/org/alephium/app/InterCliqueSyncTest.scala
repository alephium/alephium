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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.alephium.api.model._
import org.alephium.util._

class InterCliqueSyncTest extends AlephiumSpec {
  it should "boot and sync two cliques of 2 nodes" in new Fixture("2-cliques-of-2-nodes") {
    test(2, 2)
  }

  it should "boot and sync two cliques of 1 and 2 nodes" in new Fixture(
    "clique-1-node-clique-2-node") {
    test(1, 2)
  }

  it should "boot and sync two cliques of 2 and 1 nodes" in new Fixture(
    "clique-2-node-clique-1-node") {
    test(2, 1)
  }

  class Fixture(name: String) extends TestFixture(name) {

    def test(nbOfNodesClique1: Int, nbOfNodesClique2: Int) = {
      val fromTs = TimeStamp.now()

      val clique1           = bootClique(nbOfNodes = nbOfNodesClique1)
      val masterPortClique1 = clique1.head.config.network.coordinatorAddress.getPort

      Future.sequence(clique1.map(_.start())).futureValue

      startWS(wsPort(masterPortClique1))

      clique1.foreach { server =>
        request[Boolean](startMining, restPort(server.config.network.bindAddress.getPort)) is true
      }

      blockNotifyProbe.receiveN(10, Duration.ofMinutesUnsafe(2).asScala)

      clique1.foreach { server =>
        request[Boolean](stopMining, restPort(server.config.network.bindAddress.getPort)) is true
      }

      val selfClique1 = request[SelfClique](getSelfClique, restPort(masterPortClique1))

      val clique2 =
        bootClique(nbOfNodes = nbOfNodesClique2,
                   bootstrap = Some(new InetSocketAddress("localhost", masterPortClique1)))
      val masterPortClique2 = clique2.head.config.network.coordinatorAddress.getPort

      Future.sequence(clique2.map(_.start())).futureValue

      clique2.foreach { server =>
        eventually {
          val response =
            request[Seq[InterCliquePeerInfo]](
              getInterCliquePeerInfo,
              restPort(server.config.network.bindAddress.getPort)).head

          response.cliqueId is selfClique1.cliqueId
          response.isSynced is true
        }
      }

      val toTs = TimeStamp.now()

      eventually {
        request[FetchResponse](blockflowFetch(fromTs, toTs), restPort(masterPortClique1)).blocks.toSet is
          request[FetchResponse](blockflowFetch(fromTs, toTs), restPort(masterPortClique2)).blocks.toSet
      }

      clique1.foreach(_.stop().futureValue is ())
      clique2.foreach(_.stop().futureValue is ())
    }
  }
}
