package org.alephium.appserver

import scala.util.{Failure, Success}

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.{FairMiner, Miner}
import org.alephium.flow.platform.Mode
import org.alephium.util.ActorRefT

class Server(mode: Mode) extends StrictLogging {

  private val miner: ActorRefT[Miner.Command] = {
    val props =
      FairMiner.props(mode.node)(mode.config).withDispatcher("akka.actor.mining-dispatcher")
    ActorRefT.build[Miner.Command](mode.node.system, props, s"FairMiner")
  }

  val rpcServer: RPCServer = RPCServer(mode, miner)

  rpcServer
    .runServer()
    .onComplete {
      case Success(_) => ()
      case Failure(e) => logger.error("Fatal error during initialization.", e)
    }(mode.node.system.dispatcher)
}
