package org.alephium.flow.network.bootstrap

import java.net.InetAddress

import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.BrokerInfo
import org.alephium.serde._

sealed abstract case class PeerInfo(
    id: Int,
    groupNumPerBroker: Int,
    address: InetAddress,
    tcpPort: Int,
    rpcPort: Option[Int],
    wsPort: Option[Int]
)

object PeerInfo {
  def unsafe(id: Int,
             groupNumPerBroker: Int,
             address: InetAddress,
             tcpPort: Int,
             rpcPort: Option[Int],
             wsPort: Option[Int]): PeerInfo =
    new PeerInfo(id, groupNumPerBroker, address, tcpPort, rpcPort, wsPort) {}

  private val _serde: Serde[PeerInfo] =
    Serde.forProduct6(unsafe,
                      t => (t.id, t.groupNumPerBroker, t.address, t.tcpPort, t.rpcPort, t.wsPort))

  def serde(implicit config: GroupConfig): Serde[PeerInfo] =
    _serde.validate { info =>
      if (BrokerInfo.validate(info.id, info.groupNumPerBroker)) Right(())
      else Left(s"invalid peer info: $info")
    }

  def self(implicit config: PlatformConfig): PeerInfo = {
    val broker = config.brokerInfo
    new PeerInfo(broker.id,
                 broker.groupNumPerBroker,
                 broker.address.getAddress,
                 broker.address.getPort,
                 config.rpcPort,
                 config.wsPort) {}
  }
}
