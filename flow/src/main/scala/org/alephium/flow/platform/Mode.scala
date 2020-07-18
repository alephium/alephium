package org.alephium.flow.platform

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem

import org.alephium.flow.client.Node
import org.alephium.flow.io.Storages
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.io.RocksDBSource.Settings
import org.alephium.util.Service

trait Mode extends Service {
  implicit def config: PlatformConfig
  implicit def system: ActorSystem
  implicit def executionContext: ExecutionContext

  def builders: Mode.Builder = Mode.defaultBuilders

  def node: Node

  override def subServices: ArraySeq[Service] = ArraySeq(node)

  override protected def startSelfOnce(): Future[Unit] = Future.successful(())
}

object Mode {
  type Builder = BrokerHandler.Builder

  def defaultBuilders: Builder = new BrokerHandler.Builder {}

  class Default(implicit val system: ActorSystem,
                val config: PlatformConfig,
                val executionContext: ExecutionContext)
      extends Mode {

    private val storages: Storages = {
      val postfix  = s"${config.brokerInfo.id}-${config.publicAddress.getPort}"
      val dbFolder = "db-" + postfix

      Storages.createUnsafe(config.rootPath, dbFolder, Settings.writeOptions)
    }

    override val node: Node = Node.build(builders, storages)

    override protected def stopSelfOnce(): Future[Unit] =
      storages.close() match {
        case Left(error) => Future.failed(error)
        case Right(_)    => Future.successful(())
      }
  }
}
