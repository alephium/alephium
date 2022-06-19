// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.network.interclique

import java.net.InetSocketAddress

import akka.actor.Props

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{OutboundBrokerHandler => BaseOutboundBrokerHandler}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo}
import org.alephium.util.ActorRefT

object OutboundBrokerHandler {
  // scalastyle:off parameter.number
  def props(
      selfCliqueInfo: CliqueInfo,
      remoteBroker: BrokerInfo,
      blockflow: BlockFlow,
      allHandlers: AllHandlers,
      cliqueManager: ActorRefT[CliqueManager.Command],
      blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command]
  )(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): Props =
    Props(
      new OutboundBrokerHandler(
        selfCliqueInfo,
        remoteBroker,
        blockflow,
        allHandlers,
        cliqueManager,
        blockFlowSynchronizer
      )
    )
  // scalastyle:on
}

class OutboundBrokerHandler(
    val selfCliqueInfo: CliqueInfo,
    val expectedRemoteBroker: BrokerInfo,
    val blockflow: BlockFlow,
    val allHandlers: AllHandlers,
    val cliqueManager: ActorRefT[CliqueManager.Command],
    val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command]
)(implicit val brokerConfig: BrokerConfig, val networkSetting: NetworkSetting)
    extends BaseOutboundBrokerHandler
    with BrokerHandler {
  val remoteAddress: InetSocketAddress = expectedRemoteBroker.address

  override def handleHandshakeInfo(remoteBrokerInfo: BrokerInfo, clientInfo: String): Unit = {
    if (remoteBrokerInfo == expectedRemoteBroker) {
      super.handleHandshakeInfo(remoteBrokerInfo, clientInfo)
    } else {
      log.debug(
        s"Remote broker has different broker info: expected: $expectedRemoteBroker, actual: $remoteBrokerInfo"
      )
      context.stop(self)
    }
  }
}
