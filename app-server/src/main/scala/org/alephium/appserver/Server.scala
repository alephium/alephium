package org.alephium.appserver

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem

import org.alephium.flow.client.Miner
import org.alephium.flow.platform.{Mode, PlatformConfig}
import org.alephium.util.{ActorRefT, Service}

trait Server extends Service {

  implicit def config: PlatformConfig
  implicit def system: ActorSystem
  implicit def executionContext: ExecutionContext

  def mode: Mode
  def rpcServer: RPCServer
  def restServer: RestServer
  def miner: ActorRefT[Miner.Command]

  override def subServices: ArraySeq[Service] = ArraySeq(rpcServer, restServer, mode)

  override protected def startSelfOnce(): Future[Unit] =
    Future.successful(())

  override protected def stopSelfOnce(): Future[Unit] = {
    Future.successful(())
  }
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
