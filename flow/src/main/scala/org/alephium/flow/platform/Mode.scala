package org.alephium.flow.platform

import scala.concurrent.{ExecutionContext, Future}

import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.io.RocksDBSource
import org.alephium.flow.io.Storages
import org.alephium.flow.network.clique.BrokerHandler

// scalastyle:off magic.number
trait Mode {
  implicit def config: PlatformConfig

  def builders: Mode.Builder = Mode.defaultBuilders

  def node: Node

  implicit def executionContext: ExecutionContext

  def shutdown(): Future[Unit]
}
// scalastyle:on magic.number

object Mode {

  type Builder = BrokerHandler.Builder with Miner.Builder

  def defaultBuilders: Builder = new BrokerHandler.Builder with Miner.Builder

  class Default extends Mode {
    final implicit val config: PlatformConfig = PlatformConfig.loadDefault()

    private val db: RocksDBSource = {
      val dbFolder = "db"
      val dbName   = s"${config.brokerInfo.id}-${config.publicAddress.getPort}"
      RocksDBSource.createUnsafe(config.rootPath, dbFolder, dbName)
    }
    private val storages: Storages =
      Storages.createUnsafe(config.rootPath, db, RocksDBSource.Settings.writeOptions)

    override val node: Node = Node.build(builders, "Root", storages)

    implicit val executionContext: ExecutionContext = node.system.dispatcher

    override def shutdown(): Future[Unit] =
      for {
        _ <- node.shutdown()
        _ <- Future.successful(db.close())
      } yield ()
  }
}
