package org.alephium.flow.platform

import scala.concurrent.{ExecutionContext, Future}

import org.alephium.flow.client.Node
import org.alephium.flow.io.RocksDBSource.Settings
import org.alephium.flow.io.Storages
import org.alephium.flow.network.clique.BrokerHandler

trait Mode {
  implicit def config: PlatformConfig

  def builders: Mode.Builder = Mode.defaultBuilders

  def node: Node

  implicit def executionContext: ExecutionContext

  def shutdown(): Future[Unit]
}

object Mode {

  type Builder = BrokerHandler.Builder

  def defaultBuilders: Builder = new BrokerHandler.Builder {}

  class Default extends Mode {
    final implicit val config: PlatformConfig = PlatformConfig.loadDefault()

    private val storages: Storages = {
      val postfix      = s"${config.brokerInfo.id}-${config.publicAddress.getPort}"
      val dbFolder     = "db-" + postfix
      val blocksFolder = "blocks-" + postfix

      Storages.createUnsafe(config.rootPath, dbFolder, blocksFolder, Settings.writeOptions)
    }

    override val node: Node = Node.build(builders, "Root", storages)

    implicit val executionContext: ExecutionContext = node.system.dispatcher

    override def shutdown(): Future[Unit] =
      for {
        _ <- node.shutdown()
        _ <- Future.successful(storages.close())
      } yield ()
  }
}
