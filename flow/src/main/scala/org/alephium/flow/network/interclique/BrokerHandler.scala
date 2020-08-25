package org.alephium.flow.network.interclique

import org.alephium.flow.handler.{AllHandlers, FlowHandler}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BlockFlowSynchronizer, BrokerHandler => BaseBrokerHandler}
import org.alephium.protocol.message.{SyncRequest, SyncResponse}
import org.alephium.protocol.model.{BrokerInfo, CliqueId}
import org.alephium.util.ActorRefT

trait BrokerHandler extends BaseBrokerHandler {
  def cliqueManager: ActorRefT[CliqueManager.Command]

  def allHandlers: AllHandlers

  override def handleHandshakeInfo(remoteCliqueId: CliqueId, remoteBrokerInfo: BrokerInfo): Unit = {
    super.handleHandshakeInfo(remoteCliqueId, remoteBrokerInfo)
    cliqueManager ! CliqueManager.HandShaked(remoteCliqueId, remoteBrokerInfo)
  }

  override def exchanging: Receive = exchangingCommon orElse syncing orElse flowEvents

  def syncing: Receive = {
    blockFlowSynchronizer ! BlockFlowSynchronizer.HandShaked(remoteBrokerInfo)

    val receive: Receive = {
      case BaseBrokerHandler.SyncLocators(locators) =>
        send(SyncRequest(locators))
      case BaseBrokerHandler.Received(SyncRequest(locators)) =>
        allHandlers.flowHandler ! FlowHandler.GetSyncInventories(locators)
      case FlowHandler.SyncInventories(inventories) =>
        send(SyncResponse(inventories))
      case BaseBrokerHandler.Received(SyncResponse(hashes)) =>
        if (hashes.forall(_.isEmpty)) {
          cliqueManager ! CliqueManager.Synced(remoteCliqueId, remoteBrokerInfo)
        } else {
          blockFlowSynchronizer ! BlockFlowSynchronizer.SyncInventories(hashes)
        }
    }
    receive
  }

  override def dataOrigin: DataOrigin = DataOrigin.InterClique(remoteCliqueId, remoteBrokerInfo)
}
