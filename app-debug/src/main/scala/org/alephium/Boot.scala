package org.alephium

import scala.util.{Failure, Success}

import org.alephium.appserver.Server
import org.alephium.flow.platform.Mode
import org.alephium.mock.{MockBrokerHandler, MockMiner}

object Boot
    extends Server(
      new Mode.Local {
        override def builders: Mode.Builder =
          new MockBrokerHandler.Builder with MockMiner.Builder
      }
    )
    with App {
  start()
    .onComplete {
      case Success(_) => ()
      case Failure(e) => logger.error("Fatal error during initialization.", e)
    }
}
