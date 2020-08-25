package org.alephium.flow.network.intraclique

import org.alephium.flow.handler.FlowHandler
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandle}
import org.alephium.protocol.message.{GetBlocks, SyncResponse}
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}
import org.alephium.util.ActorRefT

trait BrokerHandler extends BaseBrokerHandle {
  def selfCliqueInfo: CliqueInfo

  def cliqueManager: ActorRefT[CliqueManager.Command]

  override def handleHandshakeInfo(remoteCliqueId: CliqueId, remoteBrokerInfo: BrokerInfo): Unit = {
    if (remoteCliqueId == selfCliqueInfo.id) {
      super.handleHandshakeInfo(remoteCliqueId, remoteBrokerInfo)
      cliqueManager ! CliqueManager.HandShaked(remoteCliqueId, remoteBrokerInfo)
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
      case BaseBrokerHandle.Received(SyncResponse(hashes)) =>
        log.debug(s"Received sync response from intra clique broker")
        val toDownload = hashes.flatMap(_.filter(!blockflow.containsUnsafe(_)))
        send(GetBlocks(toDownload))
    }
    receive
  }

  override def dataOrigin: DataOrigin = DataOrigin.IntraClique(remoteCliqueId, remoteBrokerInfo)
}
