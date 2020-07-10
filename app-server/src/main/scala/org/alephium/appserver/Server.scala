package org.alephium.appserver

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem

import org.alephium.flow.Stoppable
import org.alephium.flow.client.Miner
import org.alephium.flow.platform.{Mode, PlatformConfig}
import org.alephium.util.ActorRefT

trait Server extends Stoppable {

  implicit def config: PlatformConfig
  implicit def system: ActorSystem
  implicit def executionContext: ExecutionContext

  def mode: Mode
  def rpcServer: RPCServer
  def restServer: RestServer
  def miner: ActorRefT[Miner.Command]

  def start(): Future[Unit] =
    for {
      _ <- rpcServer.runServer
      _ <- restServer.runServer
    } yield ()

  def stop(): Future[Unit] =
    for {
      _ <- rpcServer.stop()
      _ <- restServer.stop()
      _ <- mode.stop()
    } yield (())
}

class ServerImpl(implicit val config: PlatformConfig,
                 val system: ActorSystem,
                 val executionContext: ExecutionContext)
    extends Server {

  val mode: Mode = new Mode.Default()

  lazy val miner: ActorRefT[Miner.Command] = {
    val props =
      Miner.props(mode.node)(config).withDispatcher("akka.actor.mining-dispatcher")
    ActorRefT.build(system, props, s"FairMiner")
  }

  lazy val rpcServer: RPCServer   = RPCServer(mode, miner)
  lazy val restServer: RestServer = RestServer(mode, miner)
}
