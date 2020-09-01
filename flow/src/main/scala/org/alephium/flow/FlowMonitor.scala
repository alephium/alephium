package org.alephium.flow

import akka.actor.Props

import org.alephium.util.{BaseActor, Duration}

object FlowMonitor {
  sealed trait Command
  case object Shutdown extends Command

  val shutdownTimeout: Duration = Duration.ofSecondsUnsafe(10)

  def props(task: => Unit): Props = Props(new FlowMonitor(task))
}

class FlowMonitor(shutdown: => Unit) extends BaseActor {
  override def preStart(): Unit = {
    require(context.system.eventStream.subscribe(self, classOf[FlowMonitor.Command]))
  }

  override def receive: Receive = {
    case FlowMonitor.Shutdown =>
      log.info(s"Shutdown the system")
      shutdown
  }
}
