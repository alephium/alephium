package org.alephium.appserver

import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.{FairMiner, Miner}
import org.alephium.flow.platform.Mode
import org.alephium.util.ActorRefT

class Server(mode: Mode) extends StrictLogging {

  implicit val executionContext: ExecutionContext = (mode.node.system.dispatcher)

  private val miner: ActorRefT[Miner.Command] = {
    val props =
      FairMiner.props(mode.node)(mode.config).withDispatcher("akka.actor.mining-dispatcher")
    ActorRefT.build[Miner.Command](mode.node.system, props, s"FairMiner")
  }

  val rpcServer: RPCServer = RPCServer(mode, miner)

  def start(): Future[Unit] =
    rpcServer
      .runServer()

  def stop(): Future[Unit] =
    for {
      _ <- rpcServer.stopServer()
      _ <- mode.node.system.terminate()
    } yield {
      ()
    }
}
