package org.alephium.util

import akka.actor.SupervisorStrategy.Resume
import akka.actor.{Actor, ActorLogging, OneForOneStrategy, SupervisorStrategy}

trait BaseActor extends Actor with ActorLogging {

  // Note: make sure that your child actors could ignore the exception and resume
  override val supervisorStrategy: SupervisorStrategy = {
    OneForOneStrategy() {
      case t: Throwable =>
        log.error(t, "Unhandled exception")
        Resume
    }
  }

  override def unhandled(message: Any): Unit = {
    log.warning(s"Unhandled message: $message")
  }
}

object BaseActor {

  def envalidActorName(name: String): String = {
    name.map { char =>
      // The following magic string is from ActorPath.ValidSymbols
      if (Character.isLetter(char) ||
          Character.isDigit(char) ||
          """-_.*$+:@&=,!~';""".contains(char)) char
      else '-'
    }
  }
}
