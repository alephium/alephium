package org.alephium.flow.handler

import org.alephium.io.{IOError, IOResult}
import org.alephium.util.BaseActor

trait IOBaseActor extends BaseActor {
  // TODO: improve error handling
  def handleIOError(error: IOError): Unit = {
    log.warning(s"IO failed: ${error.toString}")
  }
  def escapeIOError[T](result: IOResult[T])(f: T => Unit): Unit = {
    result match {
      case Right(t) => f(t)
      case Left(e)  => handleIOError(e)
    }
  }
}
