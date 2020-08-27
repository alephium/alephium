package org.alephium.flow.network.intraclique

import org.alephium.flow.Utils
import org.alephium.flow.handler.FlowHandler
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.protocol.message.SyncResponse
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}
import org.alephium.util.ActorRefT

trait BrokerHandler extends BaseBrokerHandler {
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
      case BaseBrokerHandler.Received(SyncResponse(hashes)) =>
        log.debug(
          s"Received sync response ${Utils.show(hashes.flatMap(identity))} from intra clique broker")
        blockFlowSynchronizer ! BlockFlowSynchronizer.SyncInventories(hashes)
    }
    receive
  }

  override def dataOrigin: DataOrigin = DataOrigin.IntraClique(remoteCliqueId, remoteBrokerInfo)
}
