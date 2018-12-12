package org.alephium.util

import akka.actor.{Actor, ActorLogging}

trait BaseActor extends Actor with ActorLogging {

  override def unhandled(message: Any): Unit = {
    log.warning(s"Unhandled message: $message")
  }
}

object BaseActor {

  def envalidActorName(name: String): String = {
    name.map { char =>
      // The following magic string is from ActorPath.ValidSymbols
      if (Character.isLetter(char) || """-_.*$+:@&=,!~';""".contains(char)) char else '-'
    }
  }
}
