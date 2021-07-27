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
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.FlowHandler
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler}
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.{GetBlocks, GetHeaders, SyncResponse}
import org.alephium.protocol.model.{BrokerGroupInfo, BrokerInfo, CliqueInfo}
import org.alephium.util.{ActorRefT, AVector, Duration}

trait BrokerHandler extends BaseBrokerHandler {
  def selfCliqueInfo: CliqueInfo

  def cliqueManager: ActorRefT[CliqueManager.Command]

  override def handleHandshakeInfo(remoteBrokerInfo: BrokerInfo): Unit = {
    if (remoteBrokerInfo.cliqueId == selfCliqueInfo.id) {
      super.handleHandshakeInfo(remoteBrokerInfo)
      cliqueManager ! CliqueManager.HandShaked(remoteBrokerInfo, connectionType)
    } else {
      log.warning(s"Invalid intra cliqueId")
      context stop self
    }
  }

  override def exchanging: Receive = exchangingCommon orElse syncing orElse flowEvents

  def syncing: Receive = {
    schedule(self, BrokerHandler.IntraSync, Duration.zero, Duration.ofMinutesUnsafe(1))

    val receive: Receive = {
      case FlowHandler.SyncInventories(requestId, inventories) =>
        send(SyncResponse(requestId, inventories))
      case BaseBrokerHandler.Received(SyncResponse(requestId, hashes)) =>
        log.debug(
          s"Received sync response ${Utils.showFlow(hashes)} from intra clique broker, $requestId"
        )
        assume(hashes.length == remoteBrokerInfo.groupNumPerBroker * brokerConfig.groups)
        val (headersToSync, blocksToSync) =
          BrokerHandler.extractToSync(blockflow, hashes, remoteBrokerInfo)
        send(GetHeaders(headersToSync))
        send(GetBlocks(blocksToSync))
      case BrokerHandler.IntraSync =>
        allHandlers.flowHandler ! FlowHandler.GetIntraSyncInventories
    }
    receive
  }

  override def dataOrigin: DataOrigin = DataOrigin.IntraClique(remoteBrokerInfo)
}

object BrokerHandler {
  def extractToSync(
      blockflow: BlockFlow,
      hashes: AVector[AVector[BlockHash]],
      remoteBrokerInfo: BrokerGroupInfo
  )(implicit brokerConfig: BrokerConfig): (AVector[BlockHash], AVector[BlockHash]) = {
    var headersToSync = AVector.empty[BlockHash]
    var blocksToSync  = AVector.empty[BlockHash]
    (0 until remoteBrokerInfo.groupNumPerBroker).foreach { groupShift =>
      (0 until brokerConfig.groups).foreach { toGroup =>
        val toSync =
          hashes(groupShift * brokerConfig.groups + toGroup).filter(!blockflow.containsUnsafe(_))
        if (brokerConfig.containsRaw(toGroup)) {
          blocksToSync = blocksToSync ++ toSync
        } else {
          headersToSync = headersToSync ++ toSync
        }
      }
    }
    headersToSync -> blocksToSync
  }

  case object IntraSync
}
