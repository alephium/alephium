package org.alephium.appserver

import scala.util.{Failure, Success}

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.platform.Mode

class Server(mode: Mode) extends StrictLogging {
  val rpcServer: RPCServer = new RPCServer(mode)

  rpcServer
    .runServer()
    .onComplete {
      case Success(_) => ()
      case Failure(e) => logger.error("Fatal error during initialization.", e)
    }(mode.node.system.dispatcher)
}
