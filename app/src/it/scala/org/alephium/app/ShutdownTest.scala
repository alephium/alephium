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

import org.alephium.api.model.SelfClique
import org.alephium.util._

class ShutdownTest extends AlephiumSpec {
  it should "shutdown the node when Tcp port is used" in new TestFixture("1-node") {
    val connection = TestProbe()
    IO(Tcp) ! Tcp.Bind(connection.ref, new InetSocketAddress("localhost", defaultMasterPort))

    try {
      val server = bootNode(publicPort = defaultMasterPort, brokerId = 0)
      server.restServer // need to call it as restServer is lazy val
      server.httpSystem.whenTerminated.futureValue is a[Terminated]
      server.flowSystem.whenTerminated.futureValue is a[Terminated]
    } catch {
      case _: IllegalStateException =>
        () // edge case: cannot create children while terminating or terminated
    }
  }

  it should "shutdown the clique when one node of the clique is down" in new TestFixture(
    "2-nodes"
  ) {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    val server1 = bootNode(publicPort = generatePort, brokerId = 1)
    Seq(server0.start(), server1.start()).foreach(_.futureValue is ())

    eventually(request[SelfClique](getSelfClique).synced is true)

    server0.stop().futureValue is ()
    server1.httpSystem.whenTerminated.futureValue is a[Terminated]
    server1.flowSystem.whenTerminated.futureValue is a[Terminated]
  }
}
