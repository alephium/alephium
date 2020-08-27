package org.alephium.flow.network.intraclique

import java.net.InetSocketAddress

import akka.actor.Props

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{
  BrokerManager,
  OutboundBrokerHandler => BaseOutboundBrokerHandler
}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo}
import org.alephium.util.ActorRefT

object OutboundBrokerHandler {
  // scalastyle:off parameter.number
  def props(selfCliqueInfo: CliqueInfo,
            remoteBroker: BrokerInfo,
            blockflow: BlockFlow,
            allHandlers: AllHandlers,
            cliqueManager: ActorRefT[CliqueManager.Command],
            brokerManager: ActorRefT[BrokerManager.Command],
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting): Props =
    Props(
      new OutboundBrokerHandler(selfCliqueInfo,
                                remoteBroker.address,
                                blockflow,
                                allHandlers,
                                cliqueManager,
                                brokerManager,
                                blockFlowSynchronizer))
  //scalastyle:on
}

class OutboundBrokerHandler(val selfCliqueInfo: CliqueInfo,
                            val remoteAddress: InetSocketAddress,
                            val blockflow: BlockFlow,
                            val allHandlers: AllHandlers,
                            val cliqueManager: ActorRefT[CliqueManager.Command],
                            val brokerManager: ActorRefT[BrokerManager.Command],
                            val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
    implicit val brokerConfig: BrokerConfig,
    val networkSetting: NetworkSetting)
    extends BaseOutboundBrokerHandler
    with BrokerHandler
