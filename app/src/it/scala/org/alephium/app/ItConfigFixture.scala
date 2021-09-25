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

import java.net.{DatagramSocket, InetSocketAddress, ServerSocket}
import java.nio.channels.{DatagramChannel, ServerSocketChannel}

import scala.util.Random
import scala.util.control.NonFatal

import org.alephium.flow.setting.AlephiumConfigFixture

trait ItConfigFixture extends AlephiumConfigFixture {

  def wsPort(port: Int)    = port - 1
  def restPort(port: Int)  = port - 2
  def minerPort(port: Int) = port - 3

  override def generatePort(): Int = {
    val tcpPort = 40000 + Random.nextInt(5000) * 4

    if (usedPort.contains(tcpPort)) {
      generatePort()
    } else {
      val tcp: ServerSocket      = ServerSocketChannel.open().socket()
      val udp: DatagramSocket    = DatagramChannel.open().socket()
      val rest: ServerSocket     = ServerSocketChannel.open().socket()
      val ws: ServerSocket       = ServerSocketChannel.open().socket()
      val minerApi: ServerSocket = ServerSocketChannel.open().socket()
      try {
        tcp.setReuseAddress(true)
        tcp.bind(new InetSocketAddress("127.0.0.1", tcpPort))
        udp.setReuseAddress(true)
        udp.bind(new InetSocketAddress("127.0.0.1", tcpPort))
        rest.setReuseAddress(true)
        rest.bind(new InetSocketAddress("127.0.0.1", restPort(tcpPort)))
        ws.setReuseAddress(true)
        ws.bind(new InetSocketAddress("127.0.0.1", wsPort(tcpPort)))
        minerApi.setReuseAddress(true)
        minerApi.bind(new InetSocketAddress("127.0.0.1", minerPort(tcpPort)))
        usedPort.add(tcpPort)
        tcpPort
      } catch {
        case NonFatal(_) => generatePort()
      } finally {
        tcp.close()
        udp.close()
        rest.close()
        ws.close()
        minerApi.close()
      }
    }
  }
}
