package org.alephium.util

import akka.actor._
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

trait BaseActor extends Actor with ActorLogging {

  // Note: make sure that your child actors could ignore the exception and resume
  override val supervisorStrategy: SupervisorStrategy = {
    new DefaultStrategy().create()
  }

  override def unhandled(message: Any): Unit = {
    log.warning(s"Unhandled message: $message")
  }

  // Note: no periodic scheduler, use Akka Timers instead which could be cancelled automatically
  def scheduleOnce(receiver: ActorRef, message: Any, delay: FiniteDuration): Unit = {
    context.system.scheduler
      .scheduleOnce(delay, receiver, message)(context.dispatcher, context.self)
    ()
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

  val logger = LoggerFactory.getLogger(this.getClass)

  override def create(): SupervisorStrategy = {
    val env = System.getenv("ALEPHIUM_ENV")
    env match {
      case "test" => stopStrategy
      case _      => resumeStrategy
    }
  }

  val resumeStrategy = OneForOneStrategy() {
    case e: Throwable =>
      logger.error("Unhandled throwable", e)
      SupervisorStrategy.Resume
  }

  val stopStrategy = OneForOneStrategy() {
    case e: Throwable =>
      logger.error("Unhandled throwable", e)
      SupervisorStrategy.Stop
  }
}
