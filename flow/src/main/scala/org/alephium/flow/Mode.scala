package org.alephium.flow

import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.network.clique.BrokerHandler

// scalastyle:off magic.number
trait Mode extends PlatformConfig.Default {
  def port: Int

  def httpPort: Int

  def builders: Mode.Builder = Mode.defaultBuilders

  def node: Node
}

object Mode {

  type Builder = BrokerHandler.Builder with Miner.Builder

  def defaultBuilders: Builder = new BrokerHandler.Builder with Miner.Builder

  class Aws extends Mode with StrictLogging {
    val port: Int = config.publicAddress.getPort

    val httpPort: Int = 8080

    override val node: Node = Node(builders, "Root")
  }

  class Local extends Mode {
    val port: Int = config.publicAddress.getPort

    def httpPort: Int = 8080 + (port - 9973)

    override val node: Node = Node(builders, "Root")
  }
}
