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

import org.alephium.flow.Utils
import org.alephium.flow.handler.{AllHandlers, FlowHandler}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.protocol.BlockHash
import org.alephium.protocol.message.{SyncRequest, SyncResponse}
import org.alephium.protocol.model.{BrokerInfo, ChainIndex}
import org.alephium.util.{ActorRefT, AVector}

trait BrokerHandler extends BaseBrokerHandler {
  def cliqueManager: ActorRefT[CliqueManager.Command]

  def allHandlers: AllHandlers

  override def handleHandshakeInfo(remoteBrokerInfo: BrokerInfo): Unit = {
    super.handleHandshakeInfo(remoteBrokerInfo)
    cliqueManager ! CliqueManager.HandShaked(remoteBrokerInfo)
  }

  override def exchanging: Receive = exchangingCommon orElse syncing orElse flowEvents

  def syncing: Receive = {
    blockFlowSynchronizer ! BlockFlowSynchronizer.HandShaked(remoteBrokerInfo)

    val receive: Receive = {
      case BaseBrokerHandler.SyncLocators(locators) =>
        log.debug(s"Send sync locators to $remoteAddress: ${Utils.showFlow(locators)}")
        send(SyncRequest(locators))
      case BaseBrokerHandler.Received(SyncRequest(locators)) =>
        if (validate(locators)) {
          log.debug(s"Received sync request from $remoteAddress: ${Utils.showFlow(locators)}")
          allHandlers.flowHandler ! FlowHandler.GetSyncInventories(locators)
        } else {
          log.warning(s"Invalid locators from $remoteAddress: ${Utils.showFlow(locators)}")
        }
      case FlowHandler.SyncInventories(inventories) =>
        log.debug(s"Send sync response to $remoteAddress: ${Utils.showFlow(inventories)}")
        send(SyncResponse(inventories))
      case BaseBrokerHandler.Received(SyncResponse(hashes)) =>
        if (hashes.forall(_.isEmpty)) {
          cliqueManager ! CliqueManager.Synced(remoteBrokerInfo)
        } else {
          if (validate(hashes)) {
            log.debug(s"Received sync response ${Utils.showFlow(hashes)} from $remoteAddress")
            blockFlowSynchronizer ! BlockFlowSynchronizer.SyncInventories(hashes)
          } else {
            log.warning(s"Invalid sync response from $remoteAddress: ${Utils.showFlow(hashes)}")
          }
        }
    }
    receive
  }

  override def dataOrigin: DataOrigin = DataOrigin.InterClique(remoteBrokerInfo)

  def validate(locators: AVector[AVector[BlockHash]]): Boolean = {
    locators.forall(_.forall(hash => brokerConfig.contains(ChainIndex.from(hash).from)))
  }
}
