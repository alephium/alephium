package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.protocol.SafeSerdeImpl
import org.alephium.protocol.config.{CliqueConfig, GroupConfig}
import org.alephium.serde._
import org.alephium.util.AVector

// All the groups [0, ..., G-1] are divided into G/gFactor continuous groups
// Assume the peers are ordered according to the groups they correspond to
final case class CliqueInfo private (
    id: CliqueId,
    peers: AVector[InetSocketAddress],
    groupNumPerBroker: Int
) { self =>
  def cliqueConfig: CliqueConfig = new CliqueConfig {
    val groups: Int            = peers.length * self.groupNumPerBroker
    val brokerNum: Int         = peers.length
    val groupNumPerBroker: Int = self.groupNumPerBroker
  }

  def brokers: AVector[BrokerInfo] = {
    peers.mapWithIndex { (address, index) =>
      BrokerInfo.unsafe(index, groupNumPerBroker, address)
    }
  }

  def brokerNum: Int = peers.length

  def masterAddress: InetSocketAddress = peers.head
}

object CliqueInfo extends SafeSerdeImpl[CliqueInfo, GroupConfig] {
  private implicit val peerSerde: Serde[AVector[InetSocketAddress]] =
    avectorSerde[InetSocketAddress]
  val _serde: Serde[CliqueInfo] =
    Serde.forProduct3(unsafe, t => (t.id, t.peers, t.groupNumPerBroker))

  override def validate(info: CliqueInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    val peers             = info.peers
    val groupNumPerBroker = info.groupNumPerBroker
    if (peers.isEmpty) Left("Peers vector is empty")
    else if (groupNumPerBroker < 0) Left("Group number per broker is not positive")
    else if (peers.length * groupNumPerBroker != config.groups)
      Left(s"Number of groups: got: ${peers.length * groupNumPerBroker} expect: ${config.groups}")
    else Right(())
  }

  def unsafe(id: CliqueId,
             peers: AVector[InetSocketAddress],
             groupNumPerBroker: Int): CliqueInfo = {
    new CliqueInfo(id, peers, groupNumPerBroker)
  }
}
