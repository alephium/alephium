package org.alephium.flow.network.bootstrap

import java.net.InetAddress

import org.alephium.flow.platform.Configs
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.SafeSerdeImpl
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model.BrokerInfo
import org.alephium.serde._

final case class PeerInfo private (
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
    new PeerInfo(id, groupNumPerBroker, address, tcpPort, rpcPort, wsPort)

  val _serde: Serde[PeerInfo] =
    Serde.forProduct6(unsafe,
                      t => (t.id, t.groupNumPerBroker, t.address, t.tcpPort, t.rpcPort, t.wsPort))

  override def validate(info: PeerInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    for {
      _ <- BrokerInfo.validate(info.id, info.groupNumPerBroker)
      _ <- Configs.validatePort(info.tcpPort)
      _ <- Configs.validatePort(info.rpcPort)
      _ <- Configs.validatePort(info.wsPort)
    } yield ()
  }

  def self(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): PeerInfo = {
    new PeerInfo(
      brokerConfig.brokerId,
      brokerConfig.groupNumPerBroker,
      networkSetting.publicAddress.getAddress,
      networkSetting.publicAddress.getPort,
      networkSetting.rpcPort,
      networkSetting.wsPort
    )
  }
}
