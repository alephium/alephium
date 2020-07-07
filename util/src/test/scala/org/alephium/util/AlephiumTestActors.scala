package org.alephium.util

import akka.actor.{Actor, Props}

object AlephiumTestActors {
  class ConstActor(message: Any) extends Actor {
    override def receive: Receive = {
      case _ => sender() ! message
    }
  }

  def const(message: Any): Props = Props(new ConstActor(message))
}
