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
import org.alephium.flow.handler.{FlowHandler, TxHandler}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{CliqueManager, IntraCliqueManager, MaxTxsRequestNum}
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler, MisbehaviorManager}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector, Duration}

trait BrokerHandler extends BaseBrokerHandler {
  def selfCliqueInfo: CliqueInfo

  def cliqueManager: ActorRefT[CliqueManager.Command]

  override def handleHandshakeInfo(
      _remoteBrokerInfo: BrokerInfo,
      clientInfo: String,
      p2pVersion: P2PVersion
  ): Unit = {
    if (_remoteBrokerInfo.cliqueId == selfCliqueInfo.id) {
      remoteBrokerInfo = _remoteBrokerInfo
      cliqueManager ! IntraCliqueManager.HandShaked(_remoteBrokerInfo, connectionType, clientInfo)
    } else {
      log.warning(s"Invalid intra cliqueId")
      context stop self
    }
  }

  def exchangingV2: Receive = exchangingCommon orElse syncing orElse flowEvents

  def syncing: Receive = {
    schedule(self, BrokerHandler.IntraSync, Duration.zero, Duration.ofMinutesUnsafe(1))

    val receive: Receive = {
      case FlowHandler.SyncInventories(None, inventories) =>
        send(NewInv(inventories))
      case BaseBrokerHandler.Received(NewInv(hashes)) =>
        log.debug(
          s"Received new inv ${Utils.showFlow(hashes)} from intra clique broker"
        )
        handleInv(hashes)
      case BaseBrokerHandler.Received(TxsResponse(id, txs)) =>
        handleTxsResponse(id, txs)
      case BrokerHandler.IntraSync =>
        allHandlers.flowHandler ! FlowHandler.GetIntraSyncInventories
    }
    receive
  }

  override def dataOrigin: DataOrigin = DataOrigin.IntraClique(remoteBrokerInfo)

  private def handleInv(hashes: AVector[AVector[BlockHash]]): Unit = {
    BrokerHandler.extractToSync(blockflow, hashes, remoteBrokerInfo) match {
      case Right((headersToSync, blocksToSync)) =>
        send(HeadersRequest(headersToSync))
        send(BlocksRequest(blocksToSync))
      case Left(error) =>
        log.warning(s"Invalid inventory from intra clique broker $remoteAddress: $error")
        stop(MisbehaviorManager.InvalidGroup(remoteAddress))
    }
  }

  private def handleTxsResponse(id: RequestId, txs: AVector[TransactionTemplate]): Unit = {
    if (id != RequestId.unsafe(0)) {
      log.error(s"Received unexpected txs response id from intra clique broker $remoteAddress")
      stop(MisbehaviorManager.InvalidResponse(remoteAddress))
    } else if (txs.length > MaxTxsRequestNum) {
      log.error(s"Received oversized txs response from intra clique broker $remoteAddress")
      stop(MisbehaviorManager.Spamming(remoteAddress))
    } else {
      log.debug(
        s"Received #${txs.length} txs ${Utils.showDigest(txs.map(_.id))} from $remoteAddress with $id"
      )
      if (txs.nonEmpty) {
        if (txs.forall(tx => tx.chainIndexOpt.exists(brokerConfig.isIncomingChain))) {
          allHandlers.txHandler ! TxHandler.AddToMemPool(
            txs,
            isIntraCliqueSyncing = true,
            isLocalTx = false
          )
        } else {
          log.error(s"Received invalid txs response from intra clique broker $remoteAddress")
          context.stop(self)
        }
      }
    }
  }
}

object BrokerHandler {
  def extractToSync(
      blockflow: BlockFlow,
      hashes: AVector[AVector[BlockHash]],
      remoteBrokerInfo: BrokerGroupInfo
  )(implicit
      brokerConfig: BrokerConfig
  ): Either[String, (AVector[BlockHash], AVector[BlockHash])] = {
    BrokerInfo.validate(remoteBrokerInfo.brokerId, remoteBrokerInfo.brokerNum).flatMap { _ =>
      val remoteGroupNum = brokerConfig.remoteGroupNum(remoteBrokerInfo)
      val expectedLength = remoteGroupNum.toLong * brokerConfig.groups.toLong
      if (hashes.length.toLong != expectedLength) {
        Left(s"expected $expectedLength chains, got ${hashes.length}")
      } else {
        var headersToSync = AVector.empty[BlockHash]
        var blocksToSync  = AVector.empty[BlockHash]
        (0 until remoteGroupNum).foreach { groupShift =>
          (0 until brokerConfig.groups).foreach { toGroup =>
            val toSync =
              hashes(groupShift * brokerConfig.groups + toGroup)
                .filter(!blockflow.containsUnsafe(_))
            if (brokerConfig.containsRaw(toGroup)) {
              blocksToSync = blocksToSync ++ toSync
            } else {
              headersToSync = headersToSync ++ toSync
            }
          }
        }
        Right(headersToSync -> blocksToSync)
      }
    }
  }

  case object IntraSync
}
