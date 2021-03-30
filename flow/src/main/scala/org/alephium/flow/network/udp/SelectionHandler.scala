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

import java.nio.channels.{CancelledKeyException, SelectionKey, Selector}
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import akka.actor.{ActorRef, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.scalalogging.LazyLogging

import org.alephium.util.Duration

// This is modified from akk.io.SelectionHandler
object SelectionHandler extends ExtensionId[SelectionHandler] with ExtensionIdProvider {
  override def lookup(): ExtensionId[_ <: Extension] = SelectionHandler

  override def createExtension(system: ExtendedActorSystem): SelectionHandler = {
    val selector         = Selector.open()
    val dispatcher       = system.dispatchers.lookup(s"akka.io.pinned-dispatcher")
    val executionContext = SerializedExecutionContext(dispatcher)

    system.registerOnTermination(selector.close())
    new SelectionHandler(selector, executionContext)
  }
}

class SelectionHandler(
    val selector: Selector,
    executionContext: ExecutionContext
) extends Extension
    with LazyLogging {
  private val timeout = Duration.ofSecondsUnsafe(5)
  private val wakeUp  = new AtomicBoolean(false)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def select(): Unit = {
    if (selector.select(timeout.millis) > 0) {
      val selectedKeys = selector.selectedKeys()
      val iterator     = selectedKeys.iterator()
      while (iterator.hasNext) {
        val key = iterator.next()

        if (key.isValid) {
          val udpServer = key.attachment().asInstanceOf[ActorRef]
          val readyOps  = key.readyOps()
          key.interestOps(key.interestOps & ~readyOps) // prevent immediate reselection
          if ((readyOps & SelectionKey.OP_READ) != 0) {
            udpServer ! UdpServer.Read
          }
        }
      }
      selectedKeys.clear()
    }
    wakeUp.set(false)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def loop: Runnable = () => {
    if (selector.isOpen) {
      try select()
      catch {
        case _: CancelledKeyException => // ok, can be triggered while setting interest ops
        case NonFatal(e)              => logger.error(s"Udp selection non-fatal error: $e")
      } finally executionContext.execute(loop)
    }
  }

  def execute(f: => Unit): Unit = {
    executionContext.execute { () =>
      try f
      catch {
        case _: CancelledKeyException => // ok, can be triggered while setting interest ops
        case NonFatal(e)              => logger.error(s"Error during selector management task: $e")
      }
    }
    // if possible avoid syscall and trade off with LOCK CMPXCHG
    if (wakeUp.compareAndSet(false, true)) {
      selector.wakeup()
      ()
    }
  }

  executionContext.execute(loop)
}
