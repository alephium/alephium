package org.alephium.flow.network.clique

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.Tcp

import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}
import org.alephium.util.ActorRefT

object InboundBrokerHandler {
  def props(selfCliqueInfo: CliqueInfo,
            remote: InetSocketAddress,
            connection: ActorRefT[Tcp.Command],
            allHandlers: AllHandlers,
            cliqueManager: ActorRefT[CliqueManager.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting): Props =
    Props(new InboundBrokerHandler(selfCliqueInfo, remote, connection, allHandlers, cliqueManager))
}

class InboundBrokerHandler(val selfCliqueInfo: CliqueInfo,
                           val remote: InetSocketAddress,
                           val connection: ActorRefT[Tcp.Command],
                           val allHandlers: AllHandlers,
                           val cliqueManager: ActorRefT[CliqueManager.Command])(
    implicit val brokerConfig: BrokerConfig,
    val networkSetting: NetworkSetting)
    extends BrokerHandler {

  var remoteCliqueId: CliqueId     = _
  var remoteBrokerInfo: BrokerInfo = _

  connection ! Tcp.Register(self, keepOpenOnPeerClosed = true)
  handshakeOut()

  override def receive: Receive = handleReadWrite

  def handleBrokerInfo(_remoteCliqueId: CliqueId, _remoteBrokerInfo: BrokerInfo): Unit = {
    remoteCliqueId   = _remoteCliqueId
    remoteBrokerInfo = _remoteBrokerInfo
  }
}
