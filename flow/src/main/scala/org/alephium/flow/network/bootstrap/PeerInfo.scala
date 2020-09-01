package org.alephium.flow.network.bootstrap

import java.net.InetSocketAddress

import org.alephium.flow.setting.{Configs, NetworkSetting}
import org.alephium.protocol.SafeSerdeImpl
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model.BrokerInfo
import org.alephium.serde._

final case class PeerInfo private (
    id: Int,
    groupNumPerBroker: Int,
    externalAddress: Option[InetSocketAddress],
    internalAddress: InetSocketAddress,
    rpcPort: Option[Int],
    wsPort: Option[Int]
)

object PeerInfo extends SafeSerdeImpl[PeerInfo, GroupConfig] {
  def unsafe(id: Int,
             groupNumPerBroker: Int,
             publicAddress: Option[InetSocketAddress],
             privateAddress: InetSocketAddress,
             rpcPort: Option[Int],
             wsPort: Option[Int]): PeerInfo =
    new PeerInfo(id, groupNumPerBroker, publicAddress, privateAddress, rpcPort, wsPort)

  val _serde: Serde[PeerInfo] =
    Serde.forProduct6(
      unsafe,
      t => (t.id, t.groupNumPerBroker, t.externalAddress, t.internalAddress, t.rpcPort, t.wsPort))

  override def validate(info: PeerInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    for {
      _ <- BrokerInfo.validate(info.id, info.groupNumPerBroker)
      _ <- info.externalAddress.fold[Either[String, Unit]](Right(()))(address =>
        Configs.validatePort(address.getPort))
      _ <- Configs.validatePort(info.rpcPort)
      _ <- Configs.validatePort(info.wsPort)
    } yield ()
  }

  def self(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): PeerInfo = {
    new PeerInfo(
      brokerConfig.brokerId,
      brokerConfig.groupNumPerBroker,
      networkSetting.externalAddressInferred,
      networkSetting.internalAddress,
      networkSetting.rpcPort,
      networkSetting.wsPort
    )
  }
}
