// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.util

import scala.language.implicitConversions

import akka.actor._
import org.slf4j.{Logger, LoggerFactory}

trait BaseActor extends Actor with ActorLogging {
  @SuppressWarnings(Array("org.wartremover.warts.ImplicitConversion"))
  implicit def safeActor[T](ref: ActorRef): ActorRefT[T] = ActorRefT(ref)

  // Note: make sure that your child actors could ignore the exception and resume
  override val supervisorStrategy: SupervisorStrategy = {
    new DefaultStrategy().create()
  }

  def publishEvent(event: Any): Unit = {
    context.system.eventStream.publish(event)
  }

  override def unhandled(message: Any): Unit = {
    log.warning(s"Unhandled message: $message")
  }

  def scheduleCancellable(receiver: ActorRef, message: Any, delay: Duration): Cancellable = {
    val delayScala = delay.asScala
    context.system.scheduler
      .scheduleWithFixedDelay(delayScala, delayScala, receiver, message)(context.dispatcher,
                                                                         context.self)
  }

  def schedule(receiver: ActorRef, message: Any, delay: Duration): Unit = {
    scheduleCancellable(receiver, message, delay)
    ()
  }

  def scheduleCancellableOnce(receiver: ActorRef, message: Any, delay: Duration): Cancellable = {
    context.system.scheduler
      .scheduleOnce(delay.asScala, receiver, message)(context.dispatcher, context.self)
  }

  def scheduleOnce(receiver: ActorRef, message: Any, delay: Duration): Unit = {
    scheduleCancellableOnce(receiver, message, delay)
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
      logger.error("Unhandled throwable, resume the actor", e)
      SupervisorStrategy.Resume
  }

  val stopStrategy: OneForOneStrategy = OneForOneStrategy() {
    case e: Throwable =>
      logger.error("Unhandled throwable, stop the actor", e)
      SupervisorStrategy.Stop
  }
}
