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
import org.alephium.flow.core.maxSyncBlocksPerChain
import org.alephium.flow.handler.{AllHandlers, DependencyHandler, FlowHandler}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler, MisbehaviorManager}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.protocol.BlockHash
import org.alephium.protocol.message.{InvRequest, InvResponse, NewBlockHash}
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model.{Block, BrokerInfo, ChainIndex}
import org.alephium.util.{ActorRefT, AVector, Cache}

trait BrokerHandler extends BaseBrokerHandler {
  val seenBlocks: Cache[BlockHash, Unit] = Cache.fifo[BlockHash, Unit](networkSetting.maxSeenBlocks)
  val maxForkDepth: Int                  = maxSyncBlocksPerChain

  def cliqueManager: ActorRefT[CliqueManager.Command]

  def allHandlers: AllHandlers

  override def handleHandshakeInfo(remoteBrokerInfo: BrokerInfo): Unit = {
    super.handleHandshakeInfo(remoteBrokerInfo)
    cliqueManager ! CliqueManager.HandShaked(remoteBrokerInfo, connectionType)
  }

  override def handleNewBlock(block: Block): Unit = {
    val blocks = AVector(block)
    if (validateFlowData(blocks, isBlock = true)) {
      val blockChain = blockflow.getBlockChain(block.chainIndex)
      blockChain.validateBlockHeight(block, maxForkDepth) match {
        case Right(true) =>
          seenBlocks.put(block.hash, ())
          val message = DependencyHandler.AddFlowData(blocks, dataOrigin)
          allHandlers.dependencyHandler ! message
        case _ =>
          log.debug(s"Receive new block ${block.shortHex} which have invalid height")
          handleMisbehavior(MisbehaviorManager.InvalidMessage(remoteAddress))
      }
    }
  }

  override def exchanging: Receive = exchangingCommon orElse syncing orElse flowEvents

  def syncing: Receive = {
    blockFlowSynchronizer ! BlockFlowSynchronizer.HandShaked(remoteBrokerInfo)

    val receive: Receive = {
      case BaseBrokerHandler.SyncLocators(locators) =>
        log.debug(s"Send sync locators to $remoteAddress: ${Utils.showFlow(locators)}")
        send(InvRequest(locators))
      case BaseBrokerHandler.Received(InvRequest(requestId, locators)) =>
        if (validate(locators)) {
          log.debug(s"Received sync request from $remoteAddress: ${Utils.showFlow(locators)}")
          allHandlers.flowHandler ! FlowHandler.GetSyncInventories(
            requestId,
            locators,
            remoteBrokerInfo
          )
        } else {
          log.warning(s"Invalid locators from $remoteAddress: ${Utils.showFlow(locators)}")
        }
      case FlowHandler.SyncInventories(Some(requestId), inventories) =>
        log.debug(s"Send sync response to $remoteAddress: ${Utils.showFlow(inventories)}")
        if (inventories.forall(_.isEmpty)) {
          setRemoteSynced()
        }
        send(InvResponse(requestId, inventories))
      case BaseBrokerHandler.Received(InvResponse(_, hashes)) => handleInv(hashes)
      case BaseBrokerHandler.Received(NewBlockHash(hash))     => handleNewBlockHash(hash)
      case BaseBrokerHandler.RelayInventory(hash) =>
        if (seenBlocks.contains(hash)) {
          log.debug(s"Remote broker already have the block ${hash.shortHex}")
        } else {
          log.debug(s"Relay new block hash ${hash.shortHex} to $remoteAddress")
          seenBlocks.put(hash, ())
          send(NewBlockHash(hash))
        }
    }

    receive
  }

  private def handleNewBlockHash(hash: BlockHash): Unit = {
    if (validateBlockHash(hash)) {
      if (!seenBlocks.contains(hash)) {
        log.debug(s"Receive new block hash ${hash.shortHex} from $remoteAddress")
        seenBlocks.put(hash, ())
        blockFlowSynchronizer ! BlockFlowSynchronizer.Announcement(hash)
      }
    } else {
      log.warning(s"Invalid new block hash ${hash.shortHex} from $remoteAddress")
    }
  }

  var selfSynced: Boolean   = false
  var remoteSynced: Boolean = false
  def setSelfSynced(): Unit = {
    if (!selfSynced) {
      log.info(s"Self synced with $remoteAddress")
      selfSynced = true
      checkAllSynced()
    }
  }
  def setRemoteSynced(): Unit = {
    if (!remoteSynced) {
      log.info(s"Remote $remoteAddress synced with our node")
      remoteSynced = true
      checkAllSynced()
    }
  }
  def checkAllSynced(): Unit = {
    if (selfSynced && remoteSynced) {
      cliqueManager ! CliqueManager.Synced(remoteBrokerInfo)
    }
  }

  override def dataOrigin: DataOrigin = DataOrigin.InterClique(remoteBrokerInfo)

  def validateBlockHash(hash: BlockHash): Boolean = {
    if (!PoW.checkWork(hash, blockflow.consensusConfig.maxMiningTarget)) {
      handleMisbehavior(MisbehaviorManager.InvalidPoW(remoteAddress))
      false
    } else {
      val ok = brokerConfig.contains(ChainIndex.from(hash).from)
      if (!ok) {
        handleMisbehavior(MisbehaviorManager.InvalidFlowChainIndex(remoteAddress))
      }
      ok
    }
  }

  def validate(locators: AVector[AVector[BlockHash]]): Boolean = {
    locators.forall(_.forall(validateBlockHash))
  }

  private def handleInv(hashes: AVector[AVector[BlockHash]]): Unit = {
    if (hashes.forall(_.isEmpty)) {
      setSelfSynced()
    } else {
      if (validate(hashes)) {
        log.debug(s"Received inv response ${Utils.showFlow(hashes)} from $remoteAddress")
        blockFlowSynchronizer ! BlockFlowSynchronizer.SyncInventories(hashes)
      } else {
        log.warning(s"Invalid inv response from $remoteAddress: ${Utils.showFlow(hashes)}")
      }
    }
  }
}
