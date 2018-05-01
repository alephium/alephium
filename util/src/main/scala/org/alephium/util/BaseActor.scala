package org.alephium.util

import akka.actor.{Actor, ActorLogging}

trait BaseActor extends Actor with ActorLogging {

  override def unhandled(message: Any): Unit = {
    log.warning(s"Unhandled message: $message")
  }
}
