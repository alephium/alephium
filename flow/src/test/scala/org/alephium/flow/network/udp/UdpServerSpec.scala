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

package org.alephium.flow.network.udp

import java.net.InetSocketAddress

import akka.testkit.{EventFilter, SocketUtil, TestActorRef}
import akka.util.ByteString

import org.alephium.crypto.Blake2b
import org.alephium.util.{AlephiumActorSpec}

class UdpServerSpec extends AlephiumActorSpec {
  it should "read and write messages" in new Fixture {
    val (bindAddress0, udpServer0) = createUdpServer()
    val (bindAddress1, udpServer1) = createUdpServer()

    (0 until 100).foreach { k =>
      val message = ByteString.fromString("alephium" * k)
      udpServer0 ! UdpServer.Send(message, bindAddress1)
      expectMsg(UdpServer.Received(message, bindAddress0))

      udpServer1 ! UdpServer.Send(message.reverse, bindAddress0)
      expectMsg(UdpServer.Received(message.reverse, bindAddress1))
    }
  }

  it should "fail when send data to invalid address" in new Fixture {
    val (_, udpServer) = createUdpServer()
    val message        = ByteString.fromString("alephium")
    val remote         = new InetSocketAddress(s"www.${Blake2b.generate.toHexString}.io", 9973)
    val send           = UdpServer.Send(message, remote)

    EventFilter
      .warning(
        message =
          s"Failed in sending data to $remote: java.nio.channels.UnresolvedAddressException",
        occurrences = 1
      )
      .intercept {
        udpServer ! send
      }
  }

  trait Fixture {
    def createUdpServer() = {
      val bindAddress = SocketUtil.temporaryServerAddress(udp = true)
      val udpServer   = TestActorRef[UdpServer](UdpServer.props())
      udpServer ! UdpServer.Bind(bindAddress)
      expectMsg(UdpServer.Bound(bindAddress))
      bindAddress -> udpServer
    }
  }
}
