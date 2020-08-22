package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.Tcp

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.{Hello, Payload}
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}
import org.alephium.util.{ActorRefT, Duration}

object InboundBrokerHandler {
  // scalastyle:off parameter.number
  def props(selfCliqueInfo: CliqueInfo,
            remoteAddress: InetSocketAddress,
            connection: ActorRefT[Tcp.Command],
            blockflow: BlockFlow,
            allHandlers: AllHandlers,
            cliqueManager: ActorRefT[CliqueManager.Command],
            brokerManager: ActorRefT[BrokerManager.Command],
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting): Props =
    Props(
      new InboundBrokerHandler(selfCliqueInfo,
                               remoteAddress,
                               connection,
                               blockflow,
                               allHandlers,
                               cliqueManager,
                               brokerManager,
                               blockFlowSynchronizer))
  //scalastyle:on
}

class InboundBrokerHandler(val selfCliqueInfo: CliqueInfo,
                           val remoteAddress: InetSocketAddress,
                           val connection: ActorRefT[Tcp.Command],
                           val blockflow: BlockFlow,
                           val allHandlers: AllHandlers,
                           val cliqueManager: ActorRefT[CliqueManager.Command],
                           val brokerManager: ActorRefT[BrokerManager.Command],
                           val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
    implicit val brokerConfig: BrokerConfig,
    val networkSetting: NetworkSetting)
    extends BrokerHandler {
  override def handShakeDuration: Duration = networkSetting.retryTimeout

  override val brokerConnectionHandler: ActorRefT[BrokerConnectionHandler.Command] =
    context.actorOf(BrokerConnectionHandler.props(connection))

  override def handShakeMessage: Payload =
    Hello.unsafe(selfCliqueInfo.id, selfCliqueInfo.selfBrokerInfo)

  override def pingFrequency: Duration = networkSetting.pingFrequency

  override def selfCliqueId: CliqueId = selfCliqueInfo.id

  override def handleHandshakeInfo(remoteCliqueId: CliqueId, remoteBrokerInfo: BrokerInfo): Unit = {
    super.handleHandshakeInfo(remoteCliqueId, remoteBrokerInfo)
    cliqueManager ! CliqueManager.HandShaked(remoteCliqueId, remoteBrokerInfo)
  }
}
