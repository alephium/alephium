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

import java.nio.channels.{SelectionKey, Selector}

import scala.concurrent.ExecutionContext

import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging

import org.alephium.util.Duration

final case class SelectionHandler(
    selector: Selector,
    executionContext: ExecutionContext
) extends LazyLogging {
  private val timeout = Duration.ofSecondsUnsafe(10)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def select(): Unit = {
    selector.select(timeout.millis)
    val selectedKeys = selector.selectedKeys().iterator()
    while (selectedKeys.hasNext) {
      val key = selectedKeys.next()
      selectedKeys.remove()

      if (key.isValid) {
        val udpServer = key.attachment().asInstanceOf[ActorRef]
        val readyOps  = key.readyOps()
        key.interestOps(key.interestOps & ~readyOps) // prevent immediate reselection
        if ((readyOps & SelectionKey.OP_READ) != 0) {
          udpServer ! UdpServer.Read
        }
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def loop: Runnable = () => {
    if (selector.isOpen) {
      try select()
      catch {
        case e: Throwable =>
          logger.error(s"Udp selection error: $e")
          sys.exit(1)
      } finally executionContext.execute(loop)
    }
  }

  executionContext.execute(loop)
}
