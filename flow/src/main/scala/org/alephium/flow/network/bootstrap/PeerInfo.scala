package org.alephium.flow.network.bootstrap

import java.net.InetAddress

import org.alephium.flow.platform.{Configs, PlatformConfig}
import org.alephium.protocol.SafeSerdeImpl
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

object PeerInfo extends SafeSerdeImpl[PeerInfo, GroupConfig] {
  def unsafe(id: Int,
             groupNumPerBroker: Int,
             address: InetAddress,
             tcpPort: Int,
             rpcPort: Option[Int],
             wsPort: Option[Int]): PeerInfo =
    new PeerInfo(id, groupNumPerBroker, address, tcpPort, rpcPort, wsPort) {}

  val _serde: Serde[PeerInfo] =
    Serde.forProduct6(unsafe,
                      t => (t.id, t.groupNumPerBroker, t.address, t.tcpPort, t.rpcPort, t.wsPort))

  override def validate(info: PeerInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    if (!BrokerInfo.validate(info.id, info.groupNumPerBroker)) Left(s"invalid peer info: $info")
    else if (Configs.validatePort(info.tcpPort).isEmpty)
      Left(s"invalid tcp port: ${info.tcpPort}")
    else if (info.rpcPort.nonEmpty && info.rpcPort.exists(Configs.validatePort(_).isEmpty))
      Left(s"invalid rpc port: ${info.rpcPort}")
    else if (info.wsPort.nonEmpty && info.wsPort.exists(Configs.validatePort(_).isEmpty))
      Left(s"invalid wsPort: ${info.wsPort}")
    else Right(())
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
