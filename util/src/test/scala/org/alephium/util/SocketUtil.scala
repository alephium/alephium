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

package org.alephium.util

import java.net.{DatagramSocket, InetSocketAddress, ServerSocket}
import java.nio.channels.{DatagramChannel, ServerSocketChannel}

import scala.collection.mutable
import scala.util.Random
import scala.util.control.NonFatal

trait SocketUtil {

  val usedPort = mutable.Set.empty[Int]

  def generatePort(): Int = {
    val port = 40000 + Random.nextInt(10000)

    if (usedPort.contains(port)) {
      generatePort()
    } else {
      val tcp: ServerSocket   = ServerSocketChannel.open().socket()
      val udp: DatagramSocket = DatagramChannel.open().socket()
      try {
        tcp.setReuseAddress(true)
        tcp.bind(new InetSocketAddress("localhost", port))
        udp.setReuseAddress(true)
        udp.bind(new InetSocketAddress("localhost", port))
        usedPort.add(port)
        port
      } catch {
        case NonFatal(_) => generatePort()
      } finally {
        tcp.close()
        udp.close()
      }
    }
  }
}
