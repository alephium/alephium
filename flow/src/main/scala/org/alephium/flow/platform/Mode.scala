package org.alephium.flow.platform

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem

import org.alephium.flow.Stoppable
import org.alephium.flow.client.Node
import org.alephium.flow.io.RocksDBSource.Settings
import org.alephium.flow.io.Storages
import org.alephium.flow.network.clique.BrokerHandler

trait Mode extends Stoppable {
  implicit def config: PlatformConfig
  implicit def system: ActorSystem
  implicit def executionContext: ExecutionContext

  def builders: Mode.Builder = Mode.defaultBuilders

  def node: Node

}

object Mode {

  type Builder = BrokerHandler.Builder

  def defaultBuilders: Builder = new BrokerHandler.Builder {}

  class Default(implicit val system: ActorSystem,
                val config: PlatformConfig,
                val executionContext: ExecutionContext)
      extends Mode {

    private val storages: Storages = {
      val postfix      = s"${config.brokerInfo.id}-${config.publicAddress.getPort}"
      val dbFolder     = "db-" + postfix
      val blocksFolder = "blocks-" + postfix

      Storages.createUnsafe(config.rootPath, dbFolder, blocksFolder, Settings.writeOptions)
    }

    override val node: Node = Node.build(builders, storages)

    override def stop(): Future[Unit] =
      for {
        _ <- node.stop()
        _ <- Future.successful(storages.close())
      } yield ()
  }
}
