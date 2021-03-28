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

package org.alephium.flow

import akka.actor.Props

import org.alephium.util.{BaseActor, Duration, EventStream}

object FlowMonitor {
  sealed trait Command extends EventStream.Event
  case object Shutdown extends Command

  val shutdownTimeout: Duration = Duration.ofSecondsUnsafe(10)

  def props(shutdown: => Unit): Props = Props(new FlowMonitor(shutdown))
}

class FlowMonitor(shutdown: => Unit) extends BaseActor with EventStream.Subscriber {
  override def preStart(): Unit = {
    subscribeEvent(self, classOf[FlowMonitor.Command])
  }

  override def receive: Receive = { case FlowMonitor.Shutdown =>
    log.info(s"Shutdown the system")
    shutdown
  }
}
