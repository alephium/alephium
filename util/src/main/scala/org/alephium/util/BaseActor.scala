package org.alephium.util

import akka.actor._
import org.slf4j.{Logger, LoggerFactory}

trait BaseActor extends Actor with ActorLogging {

  // Note: make sure that your child actors could ignore the exception and resume
  override val supervisorStrategy: SupervisorStrategy = {
    new DefaultStrategy().create()
  }

  override def unhandled(message: Any): Unit = {
    log.warning(s"Unhandled message: $message")
  }

  // Note: no periodic scheduler, use Akka Timers instead which could be cancelled automatically
  def scheduleOnce(receiver: ActorRef, message: Any, delay: Duration): Unit = {
    context.system.scheduler
      .scheduleOnce(delay.asScala, receiver, message)(context.dispatcher, context.self)
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
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def create(): SupervisorStrategy = {
    Env.resolve() match {
      case Env.Prod => resumeStrategy
      case _        => stopStrategy
    }
  }

  val resumeStrategy: OneForOneStrategy = OneForOneStrategy() {
    case e: Throwable =>
      logger.error("Unhandled throwable", e)
      SupervisorStrategy.Resume
  }

  val stopStrategy: OneForOneStrategy = OneForOneStrategy() {
    case e: Throwable =>
      logger.error("Unhandled throwable", e)
      SupervisorStrategy.Stop
  }
}
