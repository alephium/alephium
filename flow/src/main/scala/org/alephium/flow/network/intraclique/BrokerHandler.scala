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

package org.alephium.flow.network.intraclique

import org.alephium.flow.Utils
import org.alephium.flow.handler.FlowHandler
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.protocol.message.SyncResponse
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo}
import org.alephium.util.ActorRefT

trait BrokerHandler extends BaseBrokerHandler {
  def selfCliqueInfo: CliqueInfo

  def cliqueManager: ActorRefT[CliqueManager.Command]

  override def handleHandshakeInfo(remoteBrokerInfo: BrokerInfo): Unit = {
    if (remoteBrokerInfo.cliqueId == selfCliqueInfo.id) {
      super.handleHandshakeInfo(remoteBrokerInfo)
      cliqueManager ! CliqueManager.HandShaked(remoteBrokerInfo)
    } else {
      log.warning(s"Invalid intra cliqueId")
      context stop self
    }
  }

  override def exchanging: Receive = exchangingCommon orElse syncing orElse flowEvents

  def syncing: Receive = {
    allHandlers.flowHandler ! FlowHandler.GetIntraSyncInventories(remoteBrokerInfo)

    val receive: Receive = {
      case FlowHandler.SyncInventories(inventories) =>
        send(SyncResponse(inventories))
      case BaseBrokerHandler.Received(SyncResponse(hashes)) =>
        log.debug(s"Received sync response ${Utils.showFlow(hashes)} from intra clique broker")
        blockFlowSynchronizer ! BlockFlowSynchronizer.SyncInventories(hashes)
    }
    receive
  }

  override def dataOrigin: DataOrigin = DataOrigin.IntraClique(remoteBrokerInfo)
}
