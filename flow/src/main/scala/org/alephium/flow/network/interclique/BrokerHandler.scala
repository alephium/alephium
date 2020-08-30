package org.alephium.flow.network.interclique

import org.alephium.flow.Utils
import org.alephium.flow.handler.{AllHandlers, FlowHandler}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.protocol.message.{SyncRequest, SyncResponse}
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util.ActorRefT

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
        send(SyncRequest(locators))
      case BaseBrokerHandler.Received(SyncRequest(locators)) =>
        allHandlers.flowHandler ! FlowHandler.GetSyncInventories(locators)
      case FlowHandler.SyncInventories(inventories) =>
        send(SyncResponse(inventories))
      case BaseBrokerHandler.Received(SyncResponse(hashes)) =>
        log.debug(
          s"Received sync response ${Utils.show(hashes.flatMap(identity))} from $remoteAddress")
        if (hashes.forall(_.isEmpty)) {
          cliqueManager ! CliqueManager.Synced(remoteBrokerInfo)
        } else {
          blockFlowSynchronizer ! BlockFlowSynchronizer.SyncInventories(hashes)
        }
    }
    receive
  }

  override def dataOrigin: DataOrigin = DataOrigin.InterClique(remoteBrokerInfo)
}
