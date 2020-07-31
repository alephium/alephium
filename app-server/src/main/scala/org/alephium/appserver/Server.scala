package org.alephium.appserver

import java.nio.file.Path

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem

import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, BrokerSetting}
import org.alephium.io.RocksDBSource.Settings
import org.alephium.util.{ActorRefT, Service}

trait Server extends Service {
  def config: AlephiumConfig
  implicit def system: ActorSystem
  implicit def executionContext: ExecutionContext

  def node: Node
  def rpcServer: RPCServer
  def restServer: RestServer
  def miner: ActorRefT[Miner.Command]

  override def subServices: ArraySeq[Service] = ArraySeq(rpcServer, restServer, node)

  override protected def startSelfOnce(): Future[Unit] =
    Future.successful(())

  override protected def stopSelfOnce(): Future[Unit] = {
    Future.successful(())
  }
}

class ServerImpl(rootPath: Path)(implicit val config: AlephiumConfig,
                                 val apiConfig: ApiConfig,
                                 val system: ActorSystem,
                                 val executionContext: ExecutionContext)
    extends Server {
  private val storages: Storages = {
    val postfix  = s"${config.broker.brokerId}-${config.network.publicAddress.getPort}"
    val dbFolder = "db-" + postfix

    Storages.createUnsafe(rootPath, dbFolder, Settings.writeOptions)(config.broker)
  }

  val node: Node = Node.build(storages)

  lazy val miner: ActorRefT[Miner.Command] = {
    val props =
      Miner
        .props(node)(config.broker, config.mining)
        .withDispatcher("akka.actor.mining-dispatcher")
    ActorRefT.build(system, props, s"FairMiner")
  }

  implicit def brokerConfig: BrokerSetting = config.broker
  lazy val rpcServer: RPCServer            = RPCServer(node, miner)
  lazy val restServer: RestServer          = RestServer(node, miner)
}
