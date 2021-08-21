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

import akka.actor.Terminated
import akka.io.{IO, Tcp}
import akka.testkit.TestProbe

import org.alephium.util._

class ShutdownTest extends AlephiumActorSpec {
  it should "shutdown the node when Tcp port is used" in new CliqueFixture {
    val connection = TestProbe()
    IO(Tcp) ! Tcp.Bind(connection.ref, new InetSocketAddress("127.0.0.1", defaultMasterPort))

    try {
      val server = bootNode(publicPort = defaultMasterPort, brokerId = 0)
      server.restServer // need to call it as restServer is lazy val
      server.flowSystem.whenTerminated.futureValue is a[Terminated]
    } catch {
      case _: IllegalStateException =>
        () // edge case: cannot create children while terminating or terminated
    }
  }

  it should "shutdown the clique when one node of the clique is down" in new CliqueFixture {
    val clique = bootClique(nbOfNodes = 2)
    clique.start()

    clique.servers(0).stop().futureValue is ()
    clique.servers(1).flowSystem.whenTerminated.futureValue is a[Terminated]
  }
}
