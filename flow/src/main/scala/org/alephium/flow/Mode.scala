package org.alephium.flow

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.network.clique.BrokerHandler

// scalastyle:off magic.number
trait Mode {
  implicit def profile: PlatformProfile

  def port: Int

  def rpcPort: Int = port + 1000

  def builders: Mode.Builder = Mode.defaultBuilders

  def node: Node
}
// scalastyle:on magic.number

object Mode {

  type Builder = BrokerHandler.Builder with Miner.Builder

  def defaultBuilders: Builder = new BrokerHandler.Builder with Miner.Builder

  class Aws extends Mode with StrictLogging {
    final implicit val profile: PlatformProfile = PlatformProfile.loadDefault()

    val port: Int = profile.publicAddress.getPort

    override val node: Node = Node(builders, "Root")
  }

  class Local extends Mode {
    final implicit val profile: PlatformProfile = PlatformProfile.loadDefault()

    val port: Int = profile.publicAddress.getPort

    override val node: Node = Node(builders, "Root")
  }
}
