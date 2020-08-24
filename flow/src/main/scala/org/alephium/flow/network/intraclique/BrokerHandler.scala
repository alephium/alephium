package org.alephium.flow.network.intraclique

import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandle}
import org.alephium.protocol.message.{GetBlocks, SyncResponse0}
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
    val inventory = blockflow.getIntraCliqueSyncHashesUnsafe(remoteBrokerInfo)
    send(SyncResponse0(inventory))

    val receive: Receive = {
      case BaseBrokerHandle.Received(SyncResponse0(hashes)) =>
        log.debug(s"Received sync response from intra clique broker")
        val toDownload = hashes.flatMap(_.filter(!blockflow.containsUnsafe(_)))
        send(GetBlocks(toDownload))
    }
    receive
  }

  override def dataOrigin: DataOrigin = DataOrigin.IntraClique(remoteCliqueId, remoteBrokerInfo)
}
