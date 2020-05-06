package org.alephium.flow

import akka.actor.Props

import org.alephium.util.BaseActor

object TaskTrigger {
  sealed trait Command
  case object Trigger extends Command
  def props(task: => Unit): Props = Props(new TaskTrigger(task))
}

class TaskTrigger(task: => Unit) extends BaseActor {
  override def receive: Receive = {
    case TaskTrigger.Trigger => task
  }
}
