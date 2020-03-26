package org.alephium

import scala.util.{Failure, Success}

import org.alephium.appserver.Server
import org.alephium.flow.platform.Mode

object Boot extends Server(new Mode.Local) with App {
  start()
    .onComplete {
      case Success(_) => ()
      case Failure(e) => logger.error("Fatal error during initialization.", e)
    }
}
