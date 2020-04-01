package org.alephium

import scala.concurrent.Await
import scala.util.{Failure, Success}

import org.alephium.appserver.Server
import org.alephium.flow.Utils
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
      case Failure(e) =>
        logger.error("Fatal error during initialization.", e)
        Await.result(stop(), Utils.shutdownTimeout.asScala)
    }
}
