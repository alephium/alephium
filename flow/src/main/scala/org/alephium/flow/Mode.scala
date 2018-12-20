package org.alephium.flow

import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.network.TcpHandler

// scalastyle:off magic.number
trait Mode extends PlatformConfig.Default {
  def port: Int

  def httpPort: Int

  def builders: Mode.Builder = Mode.defaultBuilders

  def node: Node
}

object Mode {

  type Builder = TcpHandler.Builder with Miner.Builder

  def defaultBuilders: Builder = new TcpHandler.Builder with Miner.Builder

  class Aws extends Mode with StrictLogging {
    val port: Int = config.port

    val httpPort: Int = 8080

    override val node: Node = Node.createUnsafe(builders, "Root")
  }

  class Local extends Mode {
    val port: Int = config.port

    def httpPort: Int = 8080 + (port - 9973)

    override val node: Node = Node.createUnsafe(builders, "Root")
  }
}
