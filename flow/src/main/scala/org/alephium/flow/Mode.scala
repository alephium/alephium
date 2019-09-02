package org.alephium.flow

import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.network.clique.BrokerHandler

// scalastyle:off magic.number
trait Mode extends PlatformConfig.Default {
  def port: Int

  def rpcPort: Int = port + 1000

  def builders: Mode.Builder = Mode.defaultBuilders

  def node: Node
}

object Mode {

  type Builder = BrokerHandler.Builder with Miner.Builder

  def defaultBuilders: Builder = new BrokerHandler.Builder with Miner.Builder

  class Aws extends Mode with StrictLogging {
    val port: Int = config.publicAddress.getPort

    override val node: Node = Node(builders, "Root")
  }

  class Local extends Mode {
    val port: Int = config.publicAddress.getPort

    override val node: Node = Node(builders, "Root")
  }
}
