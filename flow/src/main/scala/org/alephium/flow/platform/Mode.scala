package org.alephium.flow.platform

import scala.concurrent.{ExecutionContext, Future}

import org.alephium.flow.client.{Miner, Node}
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

    override val node: Node = Node.build(builders, "Root")

    implicit val executionContext: ExecutionContext = node.system.dispatcher

    override def shutdown(): Future[Unit] =
      for {
        _ <- node.shutdown()
      } yield ()
  }
}
