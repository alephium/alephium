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

import scala.collection.mutable

import org.alephium.flow.Utils
import org.alephium.flow.core.{maxForkDepth, maxSyncBlocksPerChain, BlockFlow}
import org.alephium.flow.handler.{AllHandlers, FlowHandler, TxHandler}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{CliqueManager, InterCliqueManager}
import org.alephium.flow.network.broker.{
  BrokerHandler => BaseBrokerHandler,
  ChainTipInfo,
  MisbehaviorManager
}
import org.alephium.flow.network.sync.{BlockFlowSynchronizer, FlattenIndexedArray, SyncState}
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.ALPH
import org.alephium.protocol.message._
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector, Cache, Duration, TimeStamp}

// scalastyle:off file.size.limit
trait BrokerHandler extends BaseBrokerHandler with SyncV2Handler {
  val maxBlockCapacity: Int              = brokerConfig.groupNumPerBroker * brokerConfig.groups * 10
  val maxTxsCapacity: Int                = maxBlockCapacity * 32
  val seenBlocks: Cache[BlockHash, Unit] = Cache.fifo[BlockHash, Unit](maxBlockCapacity)
  val seenTxExpiryDuration: Duration     = BrokerHandler.seenTxExpiryDuration
  val seenTxs: Cache[TransactionId, TimeStamp] =
    Cache.fifo[TransactionId, TimeStamp](maxTxsCapacity, identity[TimeStamp], seenTxExpiryDuration)

  def cliqueManager: ActorRefT[CliqueManager.Command]

  def allHandlers: AllHandlers

  override def handleHandshakeInfo(
      _remoteBrokerInfo: BrokerInfo,
      clientInfo: String,
      p2pVersion: P2PVersion
  ): Unit = {
    remoteBrokerInfo = _remoteBrokerInfo
    val event = InterCliqueManager.HandShaked(
      ActorRefT[BaseBrokerHandler.Command](self),
      _remoteBrokerInfo,
      connectionType,
      clientInfo,
      p2pVersion
    )
    publishEvent(event)
  }

  override def handleNewBlock(block: Block): Unit = {
    val blocks = AVector(block)
    if (validateFlowData(blocks, isBlock = true)) {
      seenBlocks.put(block.hash, ())
      handleValidFlowData(blocks, dataOrigin)
    }
  }

  def exchangingV1: Receive = exchangingCommon orElse syncingV1 orElse flowEvents
  def exchangingV2: Receive = exchangingV1 orElse syncingV2

  private def tryUpdateSyncStatus(locators: AVector[AVector[BlockHash]]): Unit = {
    // When our node is V2 but the peer is V1, since it is impossible to receive the
    // chain state from V1, we need to update the sync state by checking the locators
    if (!selfSynced && selfP2PVersion == P2PV2 && remoteP2PVersion == P2PV1) {
      val result = locators.forallE { locatorsPerChain =>
        if (locatorsPerChain.isEmpty) {
          Right(true)
        } else {
          blockflow.contains(locatorsPerChain.last)
        }
      }
      escapeIOError(result, "tryUpdateSyncStatus") { synced =>
        if (synced) setSelfSynced()
      }
    }
  }

  def syncingV1: Receive = {
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
        tryUpdateSyncStatus(locators)
      } else {
        log.warning(s"Invalid locators from $remoteAddress: ${Utils.showFlow(locators)}")
      }
    case FlowHandler.SyncInventories(Some(requestId), inventories) =>
      log.debug(s"Send sync response to $remoteAddress: ${Utils.showFlow(inventories)}")
      if (inventories.sumBy(_.length) < brokerConfig.groups) {
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

  private def handleRelayTxs(txs: AVector[(ChainIndex, AVector[TransactionId])]): Unit = {
    val now = TimeStamp.now()
    val invs = txs.fold(AVector.empty[(ChainIndex, AVector[TransactionId])]) {
      case (acc, (chainIndex, txIds)) =>
        val selected = txIds.filter { txId =>
          val peerHaveTx = seenTxs.contains(txId)
          if (peerHaveTx) {
            log.debug(s"Remote broker already have the tx ${txId.shortHex}")
          } else {
            seenTxs.put(txId, now)
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
    if (selfSynced) {
      val now = TimeStamp.now()
      val result = hashes.mapE { case (chainIndex, txHashes) =>
        if (!brokerConfig.contains(chainIndex.from)) {
          Left(())
        } else {
          val invs = txHashes.filter { hash =>
            val duplicated = seenTxs.contains(hash)
            if (!duplicated) {
              seenTxs.put(hash, now)
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
          val txs = blockflow.getMemPool(chainIndex).getTxs(txHashes)
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
        allHandlers.txHandler ! TxHandler.AddToMemPool(
          txs,
          isIntraCliqueSyncing = false,
          isLocalTx = false
        )
      }
    }
  }

  private def handleBlockAnnouncement(hash: BlockHash): Unit = {
    // If the current client version is V0 and the sync protocol V2 enable version is V1, we need to handle the following three cases:
    // 1. V0 < V1, which means the client will sync using protocol V1, and both `selfChainTips` and `remoteChainTips` will be empty.
    // 2. V0 >= V1 and the peer’s client version is also greater than V1, but the client syncs using sync protocol V1.
    //    This means we won’t receive the `UpdateSelfChainTips` command from `BlockFlowSynchronizer`, and in this case,
    //    we need to send the hash to the `BlockFlowSynchronizer`.
    // 3. V0 >= V1 and the client syncs using sync protocol V2. We will only need to handle the
    //    announcement if the local chain height satisfies the conditions.
    val command = BlockFlowSynchronizer.BlockAnnouncement(hash)
    if (selfChainTips.isEmpty || remoteChainTips.isEmpty) {
      blockFlowSynchronizer ! command
    } else {
      val chainIndex = ChainIndex.from(hash)
      val needToDownload = for {
        selfTip   <- selfChainTips(chainIndex)
        remoteTip <- remoteChainTips(chainIndex)
      } yield math.abs(selfTip.height - remoteTip.height) < BrokerHandler.newBlockHashHeightDiff
      if (needToDownload.getOrElse(false)) {
        blockFlowSynchronizer ! command
      }
    }
  }

  private def handleNewBlockHash(hash: BlockHash): Unit = {
    if (validateBlockHash(hash)) {
      if (!seenBlocks.contains(hash)) {
        log.debug(s"Receive new block hash ${hash.shortHex} from $remoteAddress")
        seenBlocks.put(hash, ())
        handleBlockAnnouncement(hash)
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
      cliqueManager ! CliqueManager.Synced(remoteBrokerInfo)
    }
  }
  def setRemoteSynced(): Unit = {
    if (!remoteSynced) {
      log.info(s"Remote $remoteAddress synced with our node")
      remoteSynced = true
    }
  }

  override def dataOrigin: DataOrigin = DataOrigin.InterClique(remoteBrokerInfo)

  @inline final protected def checkWork(hash: BlockHash): Boolean = {
    PoW.checkWork(hash, blockflow.consensusConfigs.maxAllowedMiningTarget)
  }

  def validateBlockHash(hash: BlockHash): Boolean = {
    if (!checkWork(hash)) {
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

object BrokerHandler {
  val seenTxExpiryDuration: Duration = Duration.ofMinutesUnsafe(5)
  val newBlockHashHeightDiff: Int    = maxSyncBlocksPerChain

  def showChainState(tips: AVector[ChainTip]): String = {
    tips
      .map(t => s"(${t.hash.shortHex}, ${t.height}, ${t.weight.value})")
      .mkString("[", ",", "]")
  }

  def showIndexedHeights(heights: AVector[(ChainIndex, BlockHeightRange)]): String = {
    heights.map(p => s"${p._1} -> ${p._2}").mkString(", ")
  }

  def showFlowData[T <: FlowData](data: AVector[AVector[T]]): String = {
    Utils.showFlow(data.map(_.map(_.hash)))
  }

  def showAncestors(heights: AVector[(ChainIndex, Int)]): String = {
    heights.map(p => s"${p._1} -> ${p._2}").mkString(", ")
  }
}

trait SyncV2Handler { _: BrokerHandler =>
  import SyncV2Handler._

  private[interclique] var findingAncestorStates: Option[AVector[StatePerChain]] = None
  private[interclique] val pendingRequests = mutable.Map.empty[RequestId, RequestInfo]

  private[interclique] val selfChainTips   = FlattenIndexedArray.empty[ChainTip]
  private[interclique] val remoteChainTips = FlattenIndexedArray.empty[ChainTip]

  // scalastyle:off method.length
  def syncingV2: Receive = {
    schedule(self, BaseBrokerHandler.CheckPendingRequest, RequestTimeout)

    val receive: Receive = {
      case BaseBrokerHandler.SendChainState(tips) =>
        log.debug(s"Send chain state to $remoteAddress: ${BrokerHandler.showChainState(tips)}")
        send(ChainState(tips))
        tips.foreach(tip => selfChainTips(tip.chainIndex) = tip)
        checkSyncedByChainState()

      case BaseBrokerHandler.Received(ChainState(tips)) =>
        if (checkChainState(tips)) {
          log.debug(
            s"Received chain state from $remoteAddress: ${BrokerHandler.showChainState(tips)}"
          )
          tips.foreach(tip => remoteChainTips(tip.chainIndex) = tip)
          checkSyncedByChainState()
          blockFlowSynchronizer ! BlockFlowSynchronizer.UpdateChainState(tips, isRemoteNearlySynced)
        } else {
          log.warning(
            s"Invalid chain state ${BrokerHandler.showChainState(tips)} from $remoteAddress"
          )
          handleMisbehavior(MisbehaviorManager.InvalidChainState(remoteAddress))
        }

      case BaseBrokerHandler.GetAncestors(chains) =>
        // When we receive the `GetAncestors` message, we want to restart the sync workflow,
        // so we need to clear `pendingRequests` to ignore stale requests, especially the skeleton responses.
        log.debug(s"Remove pending requests: ${pendingRequests.keys.mkString("[", ",", "]")}")
        pendingRequests.clear()
        handleGetAncestors(chains)

      case BaseBrokerHandler.GetSkeletons(chains) =>
        assume(chains.nonEmpty)
        val request = HeadersByHeightsRequest(chains)
        log.debug(
          s"Sending HeadersByHeightsRequest to $remoteAddress: " +
            s"${BrokerHandler.showIndexedHeights(chains)}, id: ${request.id.value.v}"
        )
        sendRequest(request, None)

      case BaseBrokerHandler.Received(HeadersByHeightsRequest(id, heights)) =>
        log.debug(
          s"Received HeadersByHeightsRequest from $remoteAddress: " +
            s"${BrokerHandler.showIndexedHeights(heights)}, id: ${id.value.v}"
        )
        handleHeadersRequest(id, heights)

      case BaseBrokerHandler.Received(HeadersByHeightsResponse(id, headerss)) =>
        log.debug(
          s"Received HeadersByHeightsResponse from $remoteAddress: " +
            s"${BrokerHandler.showFlowData(headerss)}, id: ${id.value.v}"
        )
        handleHeadersResponse(id, headerss)

      case cmd: BaseBrokerHandler.DownloadBlockTasks =>
        handleDownloadBlocksRequest(cmd)

      case BaseBrokerHandler.Received(BlocksAndUnclesByHeightsRequest(id, chains)) =>
        log.debug(
          s"Received BlocksAndUnclesByHeightsRequest from $remoteAddress: " +
            s"${BrokerHandler.showIndexedHeights(chains)}, id: ${id.value.v}"
        )
        handleBlocksRequest(id, chains)

      case BaseBrokerHandler.Received(BlocksAndUnclesByHeightsResponse(id, blockss)) =>
        log.debug(
          s"Received BlocksAndUnclesByHeightsResponse from $remoteAddress: " +
            s"${BrokerHandler.showFlowData(blockss)}, id: ${id.value.v}"
        )
        handleBlocksResponse(id, blockss)

      case BaseBrokerHandler.CheckPendingRequest => checkPendingRequest()
    }
    receive
  }
  // scalastyle:on method.length

  private[interclique] def isRemoteNearlySynced: Boolean = {
    !remoteSynced && remoteChainTips.exists { remoteTip =>
      selfChainTips(remoteTip.chainIndex) match {
        case Some(selfTip) =>
          selfTip.height > remoteTip.height && selfTip.height - remoteTip.height < maxForkDepth
        case None => true // We haven't sent the chain state to the peer yet.
      }
    }
  }

  private def checkSyncedByChainState(): Unit = {
    if (!selfSynced && selfChainTips.nonEmpty) {
      val synced = selfChainTips.forall { selfTip =>
        val remoteTip = remoteChainTips(selfTip.chainIndex)
        remoteTip.exists(selfTip.weight >= _.weight)
      }
      if (synced) setSelfSynced()
    }
    if (!remoteSynced && remoteChainTips.nonEmpty) {
      val synced = remoteChainTips.forall { remoteTip =>
        val selfTip = selfChainTips(remoteTip.chainIndex)
        selfTip.exists(remoteTip.weight >= _.weight)
      }
      if (synced) setRemoteSynced()
    }
  }

  private def checkChainState(tips: AVector[ChainTip]): Boolean = {
    val groupRange = brokerConfig.calIntersection(remoteBrokerInfo)
    tips.length == groupRange.length * brokerConfig.groups &&
    groupRange.indices.forall { index =>
      val fromGroup = groupRange(index)
      (0 until brokerConfig.groups).forall { toGroup =>
        val tip        = tips(index * brokerConfig.groups + toGroup)
        val chainIndex = tip.chainIndex
        (checkWork(tip.hash)
          && chainIndex.from.value == fromGroup && chainIndex.to.value == toGroup) ||
        (tip.height == ALPH.GenesisHeight && tip.weight == ALPH.GenesisWeight)
      }
    }
  }

  private def handleDownloadBlocksRequest(cmd: BaseBrokerHandler.DownloadBlockTasks): Unit = {
    val heights = cmd.tasks.map(task => (task.chainIndex, task.heightRange))
    val request = BlocksAndUnclesByHeightsRequest(heights)
    log.debug(
      s"Sending BlocksAndUnclesByHeightsRequest to $remoteAddress: " +
        s"${BrokerHandler.showIndexedHeights(heights)}, id: ${request.id.value.v}"
    )
    sendRequest(request, Some(cmd))
  }

  private def handleBlocksResponse(id: RequestId, blockss: AVector[AVector[Block]]): Unit = {
    pendingRequests.remove(id) match {
      case Some(info) =>
        info.command match {
          case Some(BaseBrokerHandler.DownloadBlockTasks(tasks)) =>
            handleDownloadedBlocks(blockss, tasks)
          case _ =>
            log.error(
              s"Received invalid BlocksAndUnclesByHeightsResponse from $remoteAddress, request: $info"
            )
            publishEvent(MisbehaviorManager.InvalidResponse(remoteAddress))
        }
      case None =>
        log.warning(
          s"Ignore unknown BlocksAndUnclesByHeightsResponse from $remoteAddress, request id: $id"
        )
    }
  }

  private def handleDownloadedBlocks(
      blockss: AVector[AVector[Block]],
      tasks: AVector[SyncState.BlockDownloadTask]
  ): Unit = {
    val isValid =
      tasks.length == blockss.length &&
        tasks.forallWithIndex { case (task, index) =>
          val blocks = blockss(index)
          blocks.length >= task.size && blocks.forall(b =>
            checkWork(b.hash) && b.chainIndex == task.chainIndex
          )
        }
    if (isValid) {
      val result = tasks.mapWithIndex { case (task, index) =>
        val blocks  = blockss(index)
        val isValid = SyncV2Handler.validateBlocks(blocks, task.size, task.toHeader)
        (task, blocks, isValid)
      }
      blockFlowSynchronizer ! BlockFlowSynchronizer.UpdateBlockDownloaded(result)
    } else {
      log.error(
        s"Received invalid BlocksAndUnclesByHeightsResponse from $remoteAddress: ${BrokerHandler.showFlowData(blockss)}"
      )
      stopOnError(MisbehaviorManager.InvalidFlowData(remoteAddress))
    }
  }

  private def handleFlowDataRequest[T <: FlowData](
      id: RequestId,
      heights: AVector[(ChainIndex, BlockHeightRange)],
      fetchFlowData: (ChainIndex, BlockHeightRange) => IOResult[AVector[T]],
      responseCtor: (RequestId, AVector[AVector[T]]) => Payload.Solicited,
      name: String
  ): Unit = {
    if (heights.exists(c => !brokerConfig.contains(c._1.from))) {
      log.error(
        s"Received invalid ${name}Request from $remoteAddress: " +
          s"${BrokerHandler.showIndexedHeights(heights)}, id: ${id.value.v}"
      )
      stopOnError(MisbehaviorManager.InvalidFlowData(remoteAddress))
    } else {
      val flowData = mutable.ArrayBuffer.empty[AVector[T]]
      heights.foreach { case (chainIndex, heightsPerChain) =>
        escapeIOError(fetchFlowData(chainIndex, heightsPerChain), s"handling $name request")(
          flowData.addOne
        )
      }
      val result = AVector.from(flowData)
      log.debug(
        s"Sending ${name}Response to $remoteAddress: " +
          s"${BrokerHandler.showFlowData(result)}, id: ${id.value.v}"
      )
      send(responseCtor(id, result))
    }
  }

  private def handleBlocksRequest(
      id: RequestId,
      chains: AVector[(ChainIndex, BlockHeightRange)]
  ): Unit = {
    handleFlowDataRequest(
      id,
      chains,
      (chainIndex, range) =>
        blockflow.getBlockChain(chainIndex).getBlocksWithUnclesByHeights(range.heights),
      BlocksAndUnclesByHeightsResponse.apply,
      "BlocksAndUnclesByHeights"
    )
  }

  private def handleGetAncestors(chains: AVector[ChainTipInfo]): Unit = {
    assume(chains.nonEmpty)
    val states = mutable.ArrayBuffer.empty[StatePerChain]
    // We first send the latest height to try to find the common ancestor.
    // If it's not found, we fall back to binary search to find the common ancestor.
    val heights = chains.map { case ChainTipInfo(chainIndex, bestTip, selfTip) =>
      val heightsPerChain = SyncV2Handler.calculateRequestSpan(bestTip.height, selfTip.height)
      states.addOne(StatePerChain(chainIndex, bestTip))
      (chainIndex, heightsPerChain)
    }
    this.findingAncestorStates = Some(AVector.from(states))
    val request = HeadersByHeightsRequest(heights)
    log.debug(
      s"Sending HeadersByHeightsRequest to $remoteAddress: " +
        s"${BrokerHandler.showIndexedHeights(heights)}, id: ${request.id.value.v}"
    )
    sendRequest(request, None)
  }

  private def handleHeadersRequest(
      id: RequestId,
      heights: AVector[(ChainIndex, BlockHeightRange)]
  ): Unit = {
    handleFlowDataRequest(
      id,
      heights,
      (
          chainIndex,
          range
      ) => blockflow.getHeaderChain(chainIndex).getHeadersByHeights(range.heights),
      HeadersByHeightsResponse.apply,
      "HeadersByHeights"
    )
  }

  private def handleHeadersResponse(
      id: RequestId,
      headerss: AVector[AVector[BlockHeader]]
  ): Unit = {
    pendingRequests.remove(id) match {
      case Some(info) =>
        info.payload match {
          case HeadersByHeightsRequest(_, chains) =>
            handleHeadersResponse(headerss, chains)
          case _ =>
            log.error(
              s"Received invalid HeadersByHeightsResponse from $remoteAddress, request: $info"
            )
            publishEvent(MisbehaviorManager.InvalidResponse(remoteAddress))
        }
      case None =>
        log.warning(s"Ignore unknown HeadersByHeightsResponse from $remoteAddress, request id: $id")
    }
  }

  private def handleHeadersResponse(
      headerss: AVector[AVector[BlockHeader]],
      chains: AVector[(ChainIndex, BlockHeightRange)]
  ): Unit = {
    val isValid =
      headerss.length == chains.length &&
        headerss.forallWithIndex { case (headers, index) =>
          val chainIndex = chains(index)._1
          headers.nonEmpty && headers.forall(h =>
            (checkWork(h.hash) && h.chainIndex == chainIndex) || h.isGenesis
          )
        }
    if (isValid) {
      if (!isFindingAncestor) {
        handleSkeletonResponse(chains, headerss)
      } else {
        handleAncestorResponse(headerss)
      }
    } else {
      log.error(
        s"Received invalid HeadersByHeightsResponse from $remoteAddress: ${BrokerHandler.showFlowData(headerss)}"
      )
      stopOnError(MisbehaviorManager.InvalidFlowData(remoteAddress))
    }
  }

  private def handleAncestorResponse(headerss: AVector[AVector[BlockHeader]]): Unit = {
    escapeIOError(
      IOUtils.tryExecute(handleAncestorResponseUnsafe(headerss)),
      "handle ancestor response"
    ) { _ => }
  }

  private def handleAncestorResponseUnsafe(headerss: AVector[AVector[BlockHeader]]): Unit = {
    val heights = mutable.ArrayBuffer.empty[(ChainIndex, BlockHeightRange)]
    val isResponseValid = headerss.forall { headers =>
      val chainIndex = headers.head.chainIndex
      getChainState(chainIndex) match {
        case Some(state) =>
          val (isResponseValid, heightOpt) = handleAncestorResponseUnsafe(state, headers)
          if (isResponseValid) {
            heightOpt.foreach(h => heights.addOne(chainIndex -> h))
          }
          isResponseValid
        case None => true
      }
    }
    if (!isResponseValid) {
      stopOnError(MisbehaviorManager.InvalidFlowData(remoteAddress))
    } else if (heights.nonEmpty) {
      val request = HeadersByHeightsRequest(AVector.from(heights))
      log.debug(
        s"Sending HeadersByHeightsRequest to $remoteAddress: " +
          s"${BrokerHandler.showIndexedHeights(request.data)}, id: ${request.id.value.v}"
      )
      sendRequest(request, None)
    } else if (isAncestorFound) {
      findingAncestorStates.foreach { states =>
        val ancestors = mutable.ArrayBuffer.empty[(ChainIndex, Int)]
        states.foreach(s => s.ancestorHeight.foreach(h => ancestors.addOne(s.chainIndex -> h)))
        val command = BlockFlowSynchronizer.UpdateAncestors(AVector.from(ancestors))
        log.info(
          s"Found ancestors between self and the peer $remoteAddress: ${BrokerHandler.showAncestors(command.chains)}"
        )
        blockFlowSynchronizer ! command
      }
      findingAncestorStates = None
    }
  }

  private def handleSkeletonResponse(
      requests: AVector[(ChainIndex, BlockHeightRange)],
      response: AVector[AVector[BlockHeader]]
  ): Unit = {
    val isValid = requests.forallWithIndex { case ((_, heights), index) =>
      response(index).length == heights.length
    }
    if (isValid) {
      blockFlowSynchronizer ! BlockFlowSynchronizer.UpdateSkeletons(requests, response)
    } else {
      log.error(
        s"Received invalid skeleton from $remoteAddress: ${BrokerHandler.showFlowData(response)}"
      )
      stopOnError(MisbehaviorManager.InvalidFlowData(remoteAddress))
    }
  }

  private def handleAncestorResponseUnsafe(
      state: StatePerChain,
      headers: AVector[BlockHeader]
  ): (Boolean, Option[BlockHeightRange]) = {
    state.handleAncestorResponseUnsafe(blockflow, headers) match {
      case AncestorFound =>
        log.debug(
          s"Found the ancestor between self and the peer $remoteAddress, " +
            s"chain index: ${state.chainIndex}, height: ${state.ancestorHeight}"
        )
        (true, None)
      case StartBinarySearch(height) =>
        log.info(
          s"Fallback to binary search to find the ancestor between self " +
            s"and the peer $remoteAddress, chain index: ${state.chainIndex}"
        )
        (true, Some(BlockHeightRange.fromHeight(height)))
      case ContinueBinarySearch(height) =>
        (true, Some(BlockHeightRange.fromHeight(height)))
      case InvalidState(error) =>
        log.error(s"Invalid state when handling ancestor response: $error")
        (true, None)
      case InvalidResponse(error) =>
        log.error(s"Received invalid ancestor response: $error")
        (false, None)
    }
  }

  private def checkPendingRequest(): Unit = {
    val now = TimeStamp.now()
    pendingRequests.find(_._2.expiry < now) match {
      case Some((id, _)) =>
        log.error(
          s"The request ${id.value.v} sent to $remoteAddress timed out, stop the broker now"
        )
        stopOnError(MisbehaviorManager.RequestTimeout(remoteAddress))
      case None => ()
    }
  }

  private def sendRequest(
      request: Payload.Solicited,
      command: Option[BaseBrokerHandler.Command]
  ): Unit = {
    pendingRequests.addOne(request.id -> RequestInfo(request, command))
    send(request)
  }

  private def stopOnError(misbehavior: MisbehaviorManager.Misbehavior): Unit = {
    publishEvent(misbehavior)
    context.stop(self)
  }

  private def getChainState(chainIndex: ChainIndex): Option[StatePerChain] = {
    findingAncestorStates.flatMap(_.find(s => s.chainIndex == chainIndex))
  }

  private def isFindingAncestor: Boolean = findingAncestorStates.isDefined

  private def isAncestorFound: Boolean =
    findingAncestorStates.exists(_.forall(_.isAncestorFound))
}

object SyncV2Handler {
  val RequestTimeout: Duration = Duration.ofMinutesUnsafe(1)
  final case class RequestInfo(
      payload: Payload,
      command: Option[BaseBrokerHandler.Command],
      expiry: TimeStamp
  )
  object RequestInfo {
    def apply(payload: Payload.Solicited, command: Option[BaseBrokerHandler.Command]): RequestInfo =
      RequestInfo(payload, command, TimeStamp.now().plusUnsafe(RequestTimeout))
  }

  final class StatePerChain(
      val chainIndex: ChainIndex,
      val bestTip: ChainTip,
      var binarySearch: Option[(Int, Int)],
      var ancestorHeight: Option[Int]
  ) {
    def startBinarySearch(): Int = {
      assume(binarySearch.isEmpty)
      binarySearch = Some((ALPH.GenesisHeight, bestTip.height))
      (ALPH.GenesisHeight + bestTip.height) / 2
    }

    def updateBinarySearch(foundInLastHeight: Boolean): Unit = {
      assume(binarySearch.isDefined)
      binarySearch.foreach { case (start, end) =>
        val lastHeight = (start + end) / 2
        val (newStart, newEnd) = if (foundInLastHeight) {
          this.ancestorHeight = Some(lastHeight)
          (lastHeight, end)
        } else {
          (start, lastHeight)
        }
        binarySearch = Some((newStart, newEnd))
      }
    }

    def getNextHeight(): Option[Int] = {
      assume(binarySearch.isDefined)
      binarySearch.flatMap { case (start, end) =>
        Option.when(start + 1 < end)((start + end) / 2)
      }
    }

    @inline private def resetBinarySearch(): Unit = {
      binarySearch = None
    }

    @inline def isAncestorFound: Boolean = ancestorHeight.isDefined

    def handleAncestorResponseUnsafe(
        blockFlow: BlockFlow,
        headers: AVector[BlockHeader]
    ): HandleAncestorResponseResult = {
      binarySearch match {
        case None =>
          if (isAncestorFound) {
            InvalidState(s"The ancestor height is already found: ${ancestorHeight}")
          } else {
            headers.findReversed(h => blockFlow.containsUnsafe(h.hash)) match {
              case Some(header) =>
                ancestorHeight = Some(blockFlow.getHeightUnsafe(header.hash))
                AncestorFound
              case None => StartBinarySearch(startBinarySearch())
            }
          }
        case Some((start, end)) =>
          if (headers.length == 1) { // we have checked that the headers are not empty
            val ancestorHash = headers.head.hash
            val found        = blockFlow.containsUnsafe(ancestorHash)
            val lastHeight   = (start + end) / 2
            if (found && (blockFlow.getHeightUnsafe(ancestorHash) != lastHeight)) {
              InvalidResponse(s"The received header height does not match, expected $lastHeight")
            } else {
              updateBinarySearch(found)
              getNextHeight() match {
                case Some(height) => ContinueBinarySearch(height)
                case None =>
                  if (!isAncestorFound) ancestorHeight = Some(ALPH.GenesisHeight)
                  resetBinarySearch()
                  AncestorFound
              }
            }
          } else {
            InvalidResponse(s"The expected headers length is 1, but got ${headers.length}")
          }
      }
    }
  }

  object StatePerChain {
    def apply(chainIndex: ChainIndex, bestTip: ChainTip): StatePerChain =
      new StatePerChain(chainIndex, bestTip, None, None)
  }

  sealed trait HandleAncestorResponseResult
  case object AncestorFound                          extends HandleAncestorResponseResult
  final case class StartBinarySearch(height: Int)    extends HandleAncestorResponseResult
  final case class ContinueBinarySearch(height: Int) extends HandleAncestorResponseResult
  final case class InvalidState(error: String)       extends HandleAncestorResponseResult
  final case class InvalidResponse(error: String)    extends HandleAncestorResponseResult

  @inline private def calcInRange(value: Int, min: Int, max: Int): Int = {
    if (value < min) min else if (value > max) max else value
  }

  def calculateRequestSpan(remoteHeight: Int, localHeight: Int): BlockHeightRange = {
    assume(remoteHeight >= 0 && localHeight >= 0)
    val maxCount = 12
    // requestHead is the highest block that we will ask for. If requestHead is not offset,
    // the highest block that we will get is 16 blocks back from head, which means we
    // will fetch 14 or 15 blocks unnecessarily in the case the height difference
    // between us and the peer is 1-2 blocks, which is most common
    val requestHead   = math.max(remoteHeight - 1, ALPH.GenesisHeight)
    val requestBottom = math.max(localHeight - 1, ALPH.GenesisHeight)
    val totalSpan     = requestHead - requestBottom
    val span          = calcInRange(1 + totalSpan / maxCount, 2, 16)
    val count         = calcInRange(1 + totalSpan / span, 2, maxCount)
    val from          = math.max(requestHead - (count - 1) * span, ALPH.GenesisHeight)
    BlockHeightRange.from(from, from + (count - 1) * span, span)
  }

  // format: off
  /**
   * Validates a sequence of downloaded blocks by performing two critical checks:
   * 1. Verifies that the blocks form a valid chain by checking parent-child relationships
   * 2. Ensures the chain connects properly to the target header (if provided)
   *
   * The function traverses the blocks in reverse order, starting from the most recent block,
   * and verifies that each block's hash matches the expected hash in the chain.
   *
   * @param blocks The vector of blocks to validate
   * @param mainChainBlockSize The expected number of blocks in the main chain
   * @param toHeaderOpt Optional target header that the chain should connect to
   * @return true if the blocks form a valid chain of the expected size, false otherwise
   */
  // format: on
  def validateBlocks(
      blocks: AVector[Block],
      mainChainBlockSize: Int,
      toHeaderOpt: Option[BlockHeader]
  ): Boolean = {
    assume(mainChainBlockSize > 0)

    if (blocks.length < mainChainBlockSize) {
      false
    } else {
      val startHash        = toHeaderOpt.map(_.hash).getOrElse(blocks.last.hash)
      var nextBlockToCheck = startHash
      var remainingBlocks  = mainChainBlockSize

      for (block <- blocks.reverseIterator) {
        if (block.hash == nextBlockToCheck) {
          nextBlockToCheck = block.parentHash
          remainingBlocks -= 1
        }
      }

      remainingBlocks == 0
    }
  }
}
