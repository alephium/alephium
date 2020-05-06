package org.alephium

import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging

import org.alephium.appserver.{Server, ServerImpl}
import org.alephium.flow.Utils
import org.alephium.flow.platform.Mode
import org.alephium.flow.platform.PlatformConfig
import org.alephium.mock.MockBrokerHandler

object Boot extends App with StrictLogging {

  implicit val config: PlatformConfig             = PlatformConfig.loadDefault()
  implicit val system: ActorSystem                = ActorSystem("Debug-Boot", config.all)
  implicit val executionContext: ExecutionContext = system.dispatcher

  val server: Server = new ServerImpl {
    override val mode = new Mode.Default {
      override def builders: Mode.Builder = new MockBrokerHandler.Builder {}
    }
  }

  server
    .start()
    .onComplete {
      case Success(_) => ()
      case Failure(e) =>
        logger.error("Fatal error during initialization.", e)
        Await.result(server.stop(), Utils.shutdownTimeout.asScala)
    }
}
