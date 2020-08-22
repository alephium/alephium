package org.alephium.flow

import akka.actor.Props

import org.alephium.util.BaseActor

object FlowMonitor {
  sealed trait Command
  case object Shutdown extends Command

  def props(task: => Unit): Props = Props(new FlowMonitor(task))
}

class FlowMonitor(shutdown: => Unit) extends BaseActor {
  override def preStart(): Unit = {
    require(context.system.eventStream.subscribe(self, classOf[FlowMonitor.Command]))
  }

  override def receive: Receive = {
    case FlowMonitor.Shutdown => shutdown
  }
}
