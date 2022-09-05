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
import org.alephium.flow.core.{maxForkDepth => systemMaxForkDepth}
import org.alephium.flow.handler.{AllHandlers, DependencyHandler, FlowHandler, TxHandler}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler, MisbehaviorManager}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.protocol.message._
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model.{
  Block,
  BlockHash,
  BrokerInfo,
  ChainIndex,
  TransactionId,
  TransactionTemplate
}
import org.alephium.util.{ActorRefT, AVector, Cache}

trait BrokerHandler extends BaseBrokerHandler {
  val maxBlockCapacity: Int              = brokerConfig.groupNumPerBroker * brokerConfig.groups * 10
  val maxTxsCapacity: Int                = maxBlockCapacity * 32
  val seenBlocks: Cache[BlockHash, Unit] = Cache.fifo[BlockHash, Unit](maxBlockCapacity)
  val seenTxs: Cache[TransactionId, Unit] = Cache.fifo[TransactionId, Unit](maxTxsCapacity)
  val maxForkDepth: Int                   = systemMaxForkDepth

  def cliqueManager: ActorRefT[CliqueManager.Command]

  def allHandlers: AllHandlers

  override def handleHandshakeInfo(_remoteBrokerInfo: BrokerInfo, clientInfo: String): Unit = {
    remoteBrokerInfo = _remoteBrokerInfo
    cliqueManager ! CliqueManager.HandShaked(_remoteBrokerInfo, connectionType, clientInfo)
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
        case Right(false) =>
          log.debug(s"Receive new block ${block.shortHex} which have invalid height")
          handleMisbehavior(MisbehaviorManager.DeepForkBlock(remoteAddress))
        case Left(error) =>
          log.debug(s"IO error in validating block height: $error")
      }
    }
  }

  override def exchanging: Receive = exchangingCommon orElse syncing orElse flowEvents

  def syncing: Receive = {
    blockFlowSynchronizer ! BlockFlowSynchronizer.HandShaked(remoteBrokerInfo)

    val receive: Receive = {
      case BaseBrokerHandler.SyncLocators(locators) =>
        val showLocators = Utils.showFlow(locators)
        log.debug(s"Send sync locators to $remoteAddress: $showLocators")
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
      case BaseBrokerHandler.RelayBlock(hash) =>
        if (seenBlocks.contains(hash)) {
          log.debug(s"Remote broker already have the block ${hash.shortHex}")
        } else {
          log.debug(s"Relay new block hash ${hash.shortHex} to $remoteAddress")
          seenBlocks.put(hash, ())
          send(NewBlockHash(hash))
        }
      case BaseBrokerHandler.RelayTxs(txs)                 => handleRelayTxs(txs)
      case BaseBrokerHandler.Received(NewTxHashes(hashes)) => handleNewTxHashes(hashes)
      case BaseBrokerHandler.DownloadTxs(txs) =>
        log.debug(s"Download txs ${Utils.showChainIndexedDigest(txs)} from $remoteAddress")
        send(TxsRequest(txs))
      case BaseBrokerHandler.Received(TxsRequest(id, txs)) =>
        handleTxsRequest(id, txs)
      case BaseBrokerHandler.Received(TxsResponse(id, txs)) =>
        handleTxsResponse(id, txs)
    }

    receive
  }

  private def handleRelayTxs(txs: AVector[(ChainIndex, AVector[TransactionId])]): Unit = {
    val invs = txs.fold(AVector.empty[(ChainIndex, AVector[TransactionId])]) {
      case (acc, (chainIndex, txIds)) =>
        val selected = txIds.filter { txId =>
          val peerHaveTx = seenTxs.contains(txId)
          if (peerHaveTx) {
            log.debug(s"Remote broker already have the tx ${txId.shortHex}")
          } else {
            seenTxs.put(txId, ())
          }
          !peerHaveTx
        }
        if (selected.isEmpty) {
          acc
        } else {
          acc :+ ((chainIndex, selected))
        }
    }
    if (invs.nonEmpty) {
      send(NewTxHashes(invs))
    }
  }

  private def handleNewTxHashes(hashes: AVector[(ChainIndex, AVector[TransactionId])]): Unit = {
    log.debug(s"Received txs hashes ${Utils.showChainIndexedDigest(hashes)} from $remoteAddress")
    // ignore the tx announcements before synced
    if (selfSynced && remoteSynced) {
      val result = hashes.mapE { case (chainIndex, txHashes) =>
        if (!brokerConfig.contains(chainIndex.from)) {
          Left(())
        } else {
          val invs = txHashes.filter { hash =>
            val duplicated = seenTxs.contains(hash)
            if (!duplicated) {
              seenTxs.put(hash, ())
            }
            !duplicated
          }
          Right((chainIndex, invs))
        }
      }
      result match {
        case Right(announcements) =>
          allHandlers.txHandler ! TxHandler.TxAnnouncements(announcements)
        case _ =>
          log.debug(s"Received invalid tx hashes from $remoteAddress")
          handleMisbehavior(MisbehaviorManager.InvalidGroup(remoteAddress))
      }
    }
  }

  private def handleTxsRequest(
      id: RequestId,
      txs: AVector[(ChainIndex, AVector[TransactionId])]
  ): Unit = {
    log.debug(
      s"Received txs request ${Utils.showChainIndexedDigest(txs)} from $remoteAddress with $id"
    )
    val result = txs.foldE(AVector.empty[TransactionTemplate]) {
      case (acc, (chainIndex, txHashes)) =>
        if (!brokerConfig.contains(chainIndex.from)) {
          Left(())
        } else {
          val sharedPool = blockflow.getMemPool(chainIndex).getSharedPool(chainIndex)
          val txs        = sharedPool.getTxs(txHashes)
          Right(acc ++ txs)
        }
    }
    result match {
      case Right(txs) => send(TxsResponse(id, txs))
      case _ =>
        log.debug(s"Received invalid txs request from $remoteAddress")
        handleMisbehavior(MisbehaviorManager.InvalidGroup(remoteAddress))
    }
  }

  private def handleTxsResponse(id: RequestId, txs: AVector[TransactionTemplate]): Unit = {
    log.debug(
      s"Received #${txs.length} txs ${Utils.showDigest(txs.map(_.id))} from $remoteAddress with $id"
    )
    if (txs.nonEmpty) {
      if (txs.exists(tx => !brokerConfig.contains(tx.chainIndex.from))) {
        handleMisbehavior(MisbehaviorManager.InvalidGroup(remoteAddress))
      } else {
        allHandlers.txHandler ! TxHandler.AddToSharedPool(txs)
      }
    }
  }

  private def handleNewBlockHash(hash: BlockHash): Unit = {
    if (validateBlockHash(hash)) {
      if (!seenBlocks.contains(hash)) {
        log.debug(s"Receive new block hash ${hash.shortHex} from $remoteAddress")
        seenBlocks.put(hash, ())
        blockFlowSynchronizer ! BlockFlowSynchronizer.BlockAnnouncement(hash)
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
      val showHashes = Utils.showFlow(hashes)
      if (validate(hashes)) {
        log.debug(s"Received inv response $showHashes from $remoteAddress")
        blockFlowSynchronizer ! BlockFlowSynchronizer.SyncInventories(hashes)
      } else {
        log.warning(s"Invalid inv response from $remoteAddress: $showHashes")
      }
    }
  }
}
