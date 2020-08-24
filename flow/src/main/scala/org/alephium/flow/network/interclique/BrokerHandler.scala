package org.alephium.flow.network.interclique

import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BlockFlowSynchronizer, BrokerHandler => BaseBrokerHandler}
import org.alephium.protocol.message.{SyncRequest0, SyncResponse0}
import org.alephium.protocol.model.{BrokerInfo, CliqueId}
import org.alephium.util.ActorRefT

trait BrokerHandler extends BaseBrokerHandler {
  def cliqueManager: ActorRefT[CliqueManager.Command]

  override def handleHandshakeInfo(remoteCliqueId: CliqueId, remoteBrokerInfo: BrokerInfo): Unit = {
    super.handleHandshakeInfo(remoteCliqueId, remoteBrokerInfo)
    cliqueManager ! CliqueManager.HandShaked(remoteCliqueId, remoteBrokerInfo)
  }

  override def exchanging: Receive = exchangingCommon orElse syncing orElse flowEvents

  def syncing: Receive = {
    blockFlowSynchronizer ! BlockFlowSynchronizer.HandShaked(remoteBrokerInfo)

    val receive: Receive = {
      case BaseBrokerHandler.Sync(locators) =>
        send(SyncRequest0(locators))
      case BaseBrokerHandler.Received(SyncRequest0(locators)) =>
        val inventories = blockflow.getSyncDataUnsafe(locators)
        send(SyncResponse0(inventories))
      case BaseBrokerHandler.Received(SyncResponse0(hashes)) =>
        blockFlowSynchronizer ! BlockFlowSynchronizer.SyncData(hashes)
    }
    receive
  }

  override def dataOrigin: DataOrigin = DataOrigin.InterClique(remoteCliqueId, remoteBrokerInfo)
}
