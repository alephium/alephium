package org.alephium.util

import akka.actor.Actor
import com.typesafe.scalalogging.StrictLogging

trait BaseActor extends Actor with StrictLogging {

  override def unhandled(message: Any): Unit = {
    logger.warn(s"Unhandled message: $message")
  }
}
