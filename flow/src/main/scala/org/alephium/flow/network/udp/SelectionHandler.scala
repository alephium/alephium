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

import java.io.IOException
import java.nio.channels.Selector

import scala.concurrent.ExecutionContext

import akka.event.LoggingAdapter

import org.alephium.util.ActorRefT

case class SelectionHandler(
    udpServer: ActorRefT[UdpServer.Command],
    selector: Selector,
    executionContext: ExecutionContext,
    log: LoggingAdapter
) {
  def select(): Unit = {
    selector.select()
    val selectedKeys = selector.selectedKeys().iterator()
    while (selectedKeys.hasNext) {
      val key = selectedKeys.next()
      selectedKeys.remove()

      if (key.isReadable) {
        udpServer ! UdpServer.Read
      }
    }
  }

  def loop(): Unit = {
    executionContext.execute(() => {
      if (selector.isOpen) {
        try select()
        catch {
          case e: IOException => udpServer ! UdpServer.SelectFailure(e)
        } finally loop()
      }
    })
  }
}
