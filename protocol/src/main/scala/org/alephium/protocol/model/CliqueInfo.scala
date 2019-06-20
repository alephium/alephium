package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.protocol.config.{CliqueConfig, GroupConfig}
import org.alephium.serde._
import org.alephium.util.AVector

// All the groups [0, ..., G-1] are divided into G/gFactor continuous groups
// Assume the peers are ordered according to the groups they correspond to
case class CliqueInfo(id: CliqueId, peers: AVector[InetSocketAddress], groupNumPerBroker: Int) {
  self =>
  def cliqueConfig: CliqueConfig = new CliqueConfig {
    val groups: Int            = peers.length * self.groupNumPerBroker
    val brokerNum: Int         = peers.length
    val groupNumPerBroker: Int = self.groupNumPerBroker
  }
  def brokerNum: Int = peers.length

  // TODO: add a field for master broker
  def masterAddress: InetSocketAddress = peers.head
}

object CliqueInfo {
  implicit val peerSerde: Serde[AVector[InetSocketAddress]] = avectorSerde[InetSocketAddress]
  implicit val serializer: Serializer[CliqueInfo] =
    Serde.forProduct3(new CliqueInfo(_, _, _), t => (t.id, t.peers, t.groupNumPerBroker))

  class Unsafe(val id: CliqueId,
               val peers: AVector[InetSocketAddress],
               val groupNumPerBroker: Int) {
    def validate(implicit config: GroupConfig): Either[String, CliqueInfo] = {
      if (peers.isEmpty) Left("Peers vector is empty")
      else if (groupNumPerBroker < 0) Left("Group number per broker is not positive")
      else if (peers.length * groupNumPerBroker != config.groups)
        Left(s"Number of groups: got: ${peers.length * groupNumPerBroker} expect: ${config.groups}")
      else Right(CliqueInfo.unsafe(id, peers, groupNumPerBroker))
    }
  }
  object Unsafe {
    implicit val serde: Serde[Unsafe] =
      Serde.forProduct3(new Unsafe(_, _, _), t => (t.id, t.peers, t.groupNumPerBroker))
  }

  def unsafe(id: CliqueId, peers: AVector[InetSocketAddress], groupNumPerBroker: Int)(
      implicit config: GroupConfig): CliqueInfo = {
    assert(config.groups == peers.length * groupNumPerBroker)
    new CliqueInfo(id, peers, groupNumPerBroker)
  }
}
