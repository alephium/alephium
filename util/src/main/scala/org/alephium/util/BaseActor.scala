package org.alephium.util

import akka.actor.SupervisorStrategy.{Resume, Stop}
import akka.actor.{Actor, ActorLogging, OneForOneStrategy, SupervisorStrategy, SupervisorStrategyConfigurator}

trait BaseActor extends Actor with ActorLogging {

  // Note: make sure that your child actors could ignore the exception and resume
  override val supervisorStrategy: SupervisorStrategy = {
    new DefaultStrategy().create()
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

final class DefaultStrategy extends SupervisorStrategyConfigurator {

  override def create(): SupervisorStrategy = {
    val env = System.getenv("ALEPHIUM_ENV")
    env match {
      case "test" => stopStrategy
      case _      => resumeStrategy
    }
  }

  val resumeStrategy = OneForOneStrategy() {
    case e: Throwable =>
      System.out.print(e.toString + "\n")
      Resume
  }

  val stopStrategy = OneForOneStrategy() {
    case e: Throwable =>
      System.out.print(e + "\n")
      Stop
  }
}
