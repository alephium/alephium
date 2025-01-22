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

package org.alephium.flow.network.sync

import java.net.InetSocketAddress

import scala.collection.mutable

import akka.actor.{Props, Terminated}
import com.typesafe.scalalogging.LazyLogging

import org.alephium.flow.core.{maxSyncBlocksPerChain, BlockFlow}
import org.alephium.flow.handler._
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network._
import org.alephium.flow.network.broker.{BrokerHandler, ChainTipInfo, MisbehaviorManager}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.{ProtocolV1, ProtocolV2, ProtocolVersion}
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector, Duration, TimeStamp}
import org.alephium.util.EventStream.{Publisher, Subscriber}

// scalastyle:off file.size.limit
object BlockFlowSynchronizer {
  val V2SwitchThreshold: Int = 3

  def props(blockflow: BlockFlow, allHandlers: AllHandlers)(implicit
      networkSetting: NetworkSetting,
      brokerConfig: BrokerConfig
  ): Props =
    Props(new BlockFlowSynchronizer(blockflow, allHandlers))

  sealed trait Command
  sealed trait V1Command                                                extends Command
  sealed trait V2Command                                                extends Command
  case object Sync                                                      extends Command
  final case class SyncInventories(hashes: AVector[AVector[BlockHash]]) extends V1Command
  case object CleanDownloading                                          extends Command
  final case class BlockAnnouncement(hash: BlockHash)                   extends Command
  final case class UpdateChainState(tips: AVector[ChainTip])            extends V2Command
  final case class UpdateAncestors(chains: AVector[(ChainIndex, Int)])  extends V2Command
  final case class UpdateSkeletons(
      requests: AVector[(ChainIndex, BlockHeightRange)],
      responses: AVector[AVector[BlockHeader]]
  ) extends V2Command
  final case class UpdateBlockDownloaded(
      result: AVector[(SyncState.BlockDownloadTask, AVector[Block], Boolean)]
  ) extends V2Command
}

class BlockFlowSynchronizer(val blockflow: BlockFlow, val allHandlers: AllHandlers)(implicit
    val networkSetting: NetworkSetting,
    val brokerConfig: BrokerConfig
) extends IOBaseActor
    with Publisher
    with Subscriber
    with DownloadTracker
    with BlockFetcher
    with BrokerStatusTracker
    with InterCliqueManager.NodeSyncStatus
    with BlockFlowSynchronizerV1
    with BlockFlowSynchronizerV2 {
  import BlockFlowSynchronizer._
  import BrokerStatusTracker._

  override def preStart(): Unit = {
    super.preStart()
    schedule(self, CleanDownloading, networkSetting.syncCleanupFrequency)
    scheduleSync()
    subscribeEvent(self, classOf[InterCliqueManager.HandShaked])
    subscribeEvent(self, classOf[ChainHandler.FlowDataValidationEvent])
  }

  override def receive: Receive = v1

  private[sync] var currentVersion: ProtocolVersion = ProtocolV1
  private def v1: Receive = common orElse handleV1 orElse updateNodeSyncStatus
  private def v2: Receive = common orElse handleV2 orElse updateNodeSyncStatus

  private[sync] def shouldSwitchToV2(): Boolean = {
    networkSetting.enableSyncProtocolV2 &&
    currentVersion == ProtocolV1 &&
    peerSizeUsingV2 >= V2SwitchThreshold
  }

  def common: Receive = {
    case InterCliqueManager.HandShaked(broker, remoteBrokerInfo, _, _, protocolVersion) =>
      addBroker(broker, remoteBrokerInfo, protocolVersion)
      if (shouldSwitchToV2()) switchToV2()

    case CleanDownloading =>
      val sizeDelta = cleanupSyncing(networkSetting.syncExpiryPeriod)
      if (sizeDelta > 0) {
        log.debug(s"Clean up #$sizeDelta hashes from syncing pool")
      }

    case BlockAnnouncement(hash) => handleBlockAnnouncement(hash)
  }

  def addBroker(
      broker: BrokerActor,
      brokerInfo: BrokerInfo,
      protocolVersion: ProtocolVersion
  ): Unit = {
    log.debug(s"HandShaked with ${brokerInfo.address}")
    context.watch(broker.ref)
    brokers += broker -> BrokerStatus(brokerInfo, protocolVersion)
  }

  def removeBroker(broker: BrokerActor): Unit = {
    log.debug(s"Connection to ${remoteAddress(broker)} is closing")
    brokers.filterInPlace(_._1 != broker)
  }

  def remoteAddress(broker: ActorRefT[BrokerHandler.Command]): InetSocketAddress = {
    val brokerIndex = brokers.indexWhere(_._1 == broker)
    brokers(brokerIndex)._2.info.address
  }

  def scheduleSync(): Unit = {
    val frequency =
      if (isNodeSynced) networkSetting.stableSyncFrequency else networkSetting.fastSyncFrequency
    scheduleOnce(self, Sync, frequency)
  }

  def switchToV1(): Unit = {
    log.info("Switch to sync protocol V1")
    currentVersion = ProtocolV1
    context become v1
  }
  def switchToV2(): Unit = {
    log.info("Switch to sync protocol V2")
    currentVersion = ProtocolV2
    context become v2
  }
}

trait BlockFlowSynchronizerV1 { _: BlockFlowSynchronizer =>
  import BlockFlowSynchronizer._

  def handleV1: Receive = {
    case Sync =>
      if (brokers.nonEmpty) {
        log.debug(s"Send sync requests to the network")
        allHandlers.flowHandler ! FlowHandler.GetSyncLocators
      }
      scheduleSync()
    case flowLocators: FlowHandler.SyncLocators =>
      samplePeers(ProtocolV1).foreach { case (actor, broker) =>
        actor ! BrokerHandler.SyncLocators(flowLocators.filterFor(broker.info))
      }
    case SyncInventories(hashes) =>
      val blockHashes = getDownloadBlockHashes(hashes)
      if (blockHashes.nonEmpty) {
        sender() ! BrokerHandler.DownloadBlocks(blockHashes)
      }
    case event: ChainHandler.FlowDataValidationEvent =>
      finalized(event.data.hash)
    case Terminated(actor) => removeBroker(ActorRefT(actor))
    case _: V2Command      => ()
  }
}

trait BlockFlowSynchronizerV2 extends SyncState { _: BlockFlowSynchronizer =>
  def handleV2: Receive = {
    case BlockFlowSynchronizer.Sync =>
      if (brokers.nonEmpty) {
        log.debug(s"Send chain state to the network")
        allHandlers.flowHandler ! FlowHandler.GetChainState
      }
      scheduleSync()

    case chainState: FlowHandler.UpdateChainState =>
      samplePeers(ProtocolV2).foreach { case (actor, broker) =>
        actor ! BrokerHandler.SendChainState(chainState.filterFor(broker.info))
      }
      handleSelfChainState(chainState.tips)

    case BlockFlowSynchronizer.UpdateChainState(tips) =>
      handlePeerChainState(tips)

    case BlockFlowSynchronizer.UpdateAncestors(ancestors) =>
      handleAncestors(ancestors)

    case BlockFlowSynchronizer.UpdateSkeletons(requests, responses) =>
      handleSkeletons(requests, responses)

    case BlockFlowSynchronizer.UpdateBlockDownloaded(result) =>
      handleBlockDownloaded(result)

    case event: ChainHandler.FlowDataValidationEvent =>
      onBlockProcessed(event)

    case Terminated(actor)                  => onBrokerTerminated(ActorRefT(actor))
    case _: BlockFlowSynchronizer.V1Command => ()
  }
}

trait SyncState { _: BlockFlowSynchronizer =>
  import BrokerStatusTracker._
  import SyncState._

  private[sync] var isSyncing     = false
  private[sync] val bestChainTips = FlattenIndexedArray.empty[(BrokerActor, ChainTip)]
  private[sync] val selfChainTips = FlattenIndexedArray.empty[ChainTip]
  private[sync] val syncingChains = FlattenIndexedArray.empty[SyncStatePerChain]
  private[sync] var startTime: Option[TimeStamp] = None

  def handleBlockDownloaded(
      result: AVector[(BlockDownloadTask, AVector[Block], Boolean)]
  ): Unit = {
    val broker: BrokerActor = ActorRefT(sender())
    getBrokerStatus(broker).foreach { status =>
      status.handleBlockDownloaded(result)
      handleBlockDownloaded(broker, status.info, result)
    }
    tryValidateMoreBlocksFromAllChains()
    downloadBlocks()
  }

  private def validateMoreBlocks(blocks: mutable.ArrayBuffer[DownloadedBlock]) = {
    if (blocks.nonEmpty) {
      blocks.groupBy(_.from).foreachEntry { case (from, blocks) =>
        val dataOrigin = DataOrigin.InterClique(from._2)
        val addFlowData =
          DependencyHandler.AddFlowData(AVector.from(blocks.map(_.block)), dataOrigin)
        allHandlers.dependencyHandler.tell(addFlowData, from._1.ref)
      }
    }
  }

  private def tryValidateMoreBlocksFromAllChains(): Unit = {
    val acc = mutable.ArrayBuffer.empty[DownloadedBlock]
    syncingChains.foreach(_.tryValidateMoreBlocks(acc))
    validateMoreBlocks(acc)
  }

  private def tryValidateMoreBlocksFromChain(chainState: SyncStatePerChain): Unit = {
    val acc = mutable.ArrayBuffer.empty[DownloadedBlock]
    chainState.tryValidateMoreBlocks(acc)
    validateMoreBlocks(acc)
  }

  private def clearMissedBlocks(chainIndex: ChainIndex): Unit = {
    brokers.foreach(_._2.clearMissedBlocks(chainIndex))
  }

  private def handleBlockDownloaded(
      broker: BrokerActor,
      brokerInfo: BrokerInfo,
      result: AVector[(BlockDownloadTask, AVector[Block], Boolean)]
  ): Unit = {
    result.foreach { case (task, blocks, isValid) =>
      syncingChains(task.chainIndex).foreach { state =>
        if (isValid) {
          state.onBlockDownloaded(broker, brokerInfo, task.id, blocks)
          if (state.isSkeletonFilled) clearMissedBlocks(state.chainIndex)
        } else {
          log.warning(
            s"The broker ${brokerInfo.address} do not have the required blocks, " +
              s"put back the task to the queue, chain: ${state.chainIndex}, task id: ${task.id}"
          )
          state.putBack(task)
          handleMissedBlocks(state, task.id)
        }
      }
    }
  }

  private def handleMissedBlocks(state: SyncStatePerChain, batchId: BlockBatch): Unit = {
    val isOriginBrokerInvalid = brokers.forall(_._2.containsMissedBlocks(state.chainIndex, batchId))
    if (isOriginBrokerInvalid) {
      // No one can fill in the skeleton, disconnect from the origin peer and restart the sync
      // once we receive the `Terminated` message.
      log.error(
        "All the brokers do not have the required blocks, stop the origin broker and resync"
      )
      val misbehavior = MisbehaviorManager.InvalidFlowData(remoteAddress(state.originBroker))
      publishEvent(misbehavior)
      context.stop(state.originBroker.ref)
    }
  }

  def handlePeerChainState(tips: AVector[ChainTip]): Unit = {
    val brokerActor: BrokerActor = ActorRefT(sender())
    getBrokerStatus(brokerActor).foreach(_.updateTips(tips))
    tips.foreach { chainTip =>
      val chainIndex = chainTip.chainIndex
      bestChainTips(chainIndex) match {
        case Some((_, current)) =>
          if (chainTip.weight > current.weight) {
            bestChainTips(chainIndex) = (brokerActor, chainTip)
          }
        case None => bestChainTips(chainIndex) = (brokerActor, chainTip)
      }
    }
  }

  private def hasBestChainTips: Boolean = {
    brokerConfig.chainIndexes.forall(bestChainTips.contains)
  }

  def handleSelfChainState(chainTips: AVector[ChainTip]): Unit = {
    chainTips.foreach { chainTip =>
      this.selfChainTips(chainTip.chainIndex) = Some(chainTip)
    }
    if (!isSyncing) {
      tryStartSync()
    } else if (isSynced) {
      tryStartNextSyncRound()
    }
  }

  def onBlockProcessed(event: ChainHandler.FlowDataValidationEvent): Unit = {
    if (isSyncing) {
      val isBlockValid = event match {
        case _: ChainHandler.FlowDataAdded   => true
        case _: ChainHandler.InvalidFlowData => false
      }
      val block = event.data
      if (isBlockValid) {
        syncingChains(block.chainIndex).foreach { chainState =>
          chainState.handleFinalizedBlock(block.hash)
          tryValidateMoreBlocksFromChain(chainState)
          tryMoveOn(chainState)
        }
      } else {
        log.info(s"Block ${block.hash.toHexString} is invalid, resync")
        resync()
      }
    }
  }

  private[sync] def isSynced: Boolean = {
    syncingChains.forall { state =>
      val selfChainTip = selfChainTips(state.chainIndex)
      selfChainTip.exists(state.isSynced)
    }
  }

  private def tryMoveOn(chainState: SyncStatePerChain): Unit = {
    val taskSize = chainState.taskSize
    chainState.tryMoveOn() match {
      case Some(range) =>
        val request = AVector(chainState.chainIndex -> range)
        chainState.originBroker ! BrokerHandler.GetSkeletons(request)
      case None =>
        // We need to attempt to download the blocks only when the remaining blocks cannot form a skeleton
        if (chainState.taskSize > taskSize) {
          downloadBlocks()
        }
    }
  }

  private def tryStartSync(): Unit = {
    if (hasBestChainTips) {
      startTime = None
      val chains = mutable.ArrayBuffer.empty[(ChainIndex, BrokerActor, ChainTip, ChainTip)]
      brokerConfig.chainIndexes.foreach { chainIndex =>
        for {
          selfTip <- selfChainTips(chainIndex)
          bestTip <- bestChainTips(chainIndex)
        } yield {
          if (bestTip._2.weight > selfTip.weight) {
            chains.addOne((chainIndex, bestTip._1, bestTip._2, selfTip))
          }
        }
      }
      if (chains.nonEmpty) startSync(chains)
    } else {
      startTime match {
        case Some(ts) =>
          if (ts.plusUnsafe(FallbackThreshold) < TimeStamp.now()) {
            clearV2StateAndSwitchToV1()
          }
        case None => startTime = Some(TimeStamp.now())
      }
    }
  }

  // The sync process consists of three steps:
  // 1. Find the common ancestor height `h` between the local node and the origin peer
  // 2. Start constructing the header chain skeletons from `h + 1` using the origin peer
  // 3. Download blocks from all nodes to fill in the header chain skeletons. If no one can
  //    fill in the skeleton it's assumed invalid and the origin peer is dropped
  def startSync(chains: collection.Seq[(ChainIndex, BrokerActor, ChainTip, ChainTip)]): Unit = {
    assume(!isSyncing)
    isSyncing = true
    log.debug("Start syncing")

    val requestsPerBroker = mutable.HashMap
      .empty[BrokerActor, mutable.ArrayBuffer[(ChainIndex, ChainTip, ChainTip)]]
    chains.foreach { case (chainIndex, brokerActor, bestTip, selfTip) =>
      syncingChains(chainIndex) = Some(SyncStatePerChain(chainIndex, bestTip, brokerActor))
      requestsPerBroker.get(brokerActor) match {
        case Some(value) => value.addOne((chainIndex, bestTip, selfTip))
        case None =>
          requestsPerBroker(brokerActor) = mutable.ArrayBuffer((chainIndex, bestTip, selfTip))
      }
    }
    requestsPerBroker.foreachEntry { case (broker, chainsPerBroker) =>
      val requests = chainsPerBroker.map { case (chainIndex, bestTip, selfTip) =>
        ChainTipInfo(chainIndex, bestTip, selfTip)
      }
      broker ! BrokerHandler.GetAncestors(AVector.from(requests))
    }
  }

  def handleAncestors(ancestors: AVector[(ChainIndex, Int)]): Unit = {
    val brokerActor: BrokerActor = ActorRefT(sender())
    val requests                 = mutable.ArrayBuffer.empty[(ChainIndex, BlockHeightRange)]
    ancestors.foreach { case (chainIndex, height) =>
      syncingChains(chainIndex).foreach { state =>
        state.initSkeletonHeights(brokerActor, height + 1) match {
          case Some(range) => requests.addOne((chainIndex, range))
          case None        => ()
        }
      }
    }
    if (requests.nonEmpty) {
      brokerActor ! BrokerHandler.GetSkeletons(AVector.from(requests))
    }
    downloadBlocks()
  }

  def handleSkeletons(
      requests: AVector[(ChainIndex, BlockHeightRange)],
      responses: AVector[AVector[BlockHeader]]
  ): Unit = {
    val brokerActor: BrokerActor = ActorRefT(sender())
    assume(requests.length == responses.length)
    requests.foreachWithIndex { case ((chainIndex, range), index) =>
      val headers = responses(index)
      syncingChains(chainIndex).foreach(_.onSkeletonFetched(brokerActor, range, headers))
    }
    downloadBlocks()
  }

  private[sync] def downloadBlocks(): Unit = {
    val chains = syncingChains.array.collect {
      case Some(chain) if !chain.isTaskQueueEmpty => chain
    }
    if (chains.nonEmpty) {
      val allTasks = collectAndAssignTasks(AVector.from(chains))
      allTasks.foreachEntry { case (brokerActor, tasksPerBroker) =>
        val tasks = AVector.from(tasksPerBroker)
        log.debug(
          s"Trying to download blocks from ${remoteAddress(brokerActor)}, tasks: ${SyncState.showTasks(tasks)}"
        )
        brokerActor ! BrokerHandler.DownloadBlockTasks(tasks)
      }
    }
  }

  private def collectAndAssignTasks(
      chains: AVector[SyncStatePerChain]
  ): mutable.HashMap[BrokerActor, mutable.ArrayBuffer[BlockDownloadTask]] = {
    val orderedChains  = chains.sortBy(_.taskSize)(Ordering[Int].reverse)
    val orderedBrokers = brokers.sortBy(_._2.requestNum)
    val acc            = mutable.HashMap.empty[BrokerActor, mutable.ArrayBuffer[BlockDownloadTask]]

    @scala.annotation.tailrec
    def iter(): mutable.HashMap[BrokerActor, mutable.ArrayBuffer[BlockDownloadTask]] = {
      val continue = collectAndAssignTasks(orderedChains, orderedBrokers, acc)
      if (continue) iter() else acc
    }

    iter()
  }

  private def collectAndAssignTasks(
      orderedChains: AVector[SyncStatePerChain],
      orderedBrokers: scala.collection.Seq[(BrokerActor, BrokerStatus)],
      acc: mutable.HashMap[BrokerActor, mutable.ArrayBuffer[BlockDownloadTask]]
  ) = {
    var size = 0
    orderedChains.foreach { state =>
      state.nextTask { task =>
        val selectedBroker = if (task.toHeader.isDefined) {
          orderedBrokers.find(_._2.canDownload(task))
        } else {
          // download the latest blocks from the `originBroker`
          getBrokerStatus(state.originBroker).flatMap { status =>
            if (status.canDownload(task)) {
              Some((state.originBroker, status))
            } else {
              None
            }
          }
        }
        selectedBroker match {
          case Some((broker, brokerStatus)) =>
            brokerStatus.addPendingTask(task)
            addToMap(acc, broker, task)
            size += 1
            true
          case None => false
        }
      }
    }
    size > 0
  }

  private def clearSyncingState(): Unit = {
    syncingChains.reset()
    isSyncing = false
    brokers.foreach(_._2.clear())
  }

  private def resync(): Unit = {
    log.debug("Clear syncing state and resync")
    clearSyncingState()
    tryStartSync()
  }

  private[sync] def needToStartNextSyncRound(): Boolean = {
    // Only start the next round of sync if the best tip is better than the best tip being used for syncing
    // This helps avoid re-downloading the latest blocks while they are still cached in the `DependencyHandler`
    brokerConfig.chainIndexes.exists { chainIndex =>
      (for {
        usedBestTip   <- syncingChains(chainIndex).map(_.bestTip)
        latestBestTip <- bestChainTips(chainIndex).map(_._2)
      } yield latestBestTip.weight > usedBestTip.weight).getOrElse(false)
    }
  }

  @inline private def tryStartNextSyncRound(): Unit = {
    if (needToStartNextSyncRound()) resync()
  }

  private def clearV2StateAndSwitchToV1(): Unit = {
    selfChainTips.reset()
    bestChainTips.reset()
    startTime = None
    clearSyncingState()
    switchToV1()
  }

  private def updateBestChainTips(terminatedBroker: BrokerActor): Unit = {
    assume(getBrokerStatus(terminatedBroker).isEmpty)
    bestChainTips.foreach { case (brokerActor, chainTip) =>
      if (brokerActor == terminatedBroker) {
        val selected = brokers.view
          .flatMap { case (newBroker, status) =>
            status.getChainTip(chainTip.chainIndex).map(chainTip => (newBroker, chainTip))
          }
          .maxByOption(_._2.weight)
        bestChainTips(chainTip.chainIndex) = selected
      }
    }
  }

  def onBrokerTerminated(broker: BrokerActor): Unit = {
    val status = getBrokerStatus(broker)
    removeBroker(broker)
    if (peerSizeUsingV2 < BlockFlowSynchronizer.V2SwitchThreshold) {
      clearV2StateAndSwitchToV1()
    } else {
      status.foreach(onBrokerTerminated(broker, _))
    }
  }

  private def onBrokerTerminated(broker: BrokerActor, status: BrokerStatus): Unit = {
    updateBestChainTips(broker)
    if (isSyncing) {
      val needToResync = syncingChains.exists(_.isOriginPeer(broker))
      if (needToResync) {
        log.info(s"Resync due to the origin broker ${status.info.address} terminated")
        resync()
      } else {
        val recycledTaskSize = status.recycleTasks(syncingChains)
        if (recycledTaskSize > 0) {
          log.debug(
            s"Reschedule the pending tasks from the terminated broker ${status.info.address}, " +
              s"task size: $recycledTaskSize"
          )
          downloadBlocks()
        }
      }
    }
  }
}

object SyncState {
  import BrokerStatusTracker.BrokerActor

  val SkeletonSize: Int           = 16
  val BatchSize: Int              = 128
  val MaxQueueSize: Int           = SkeletonSize * BatchSize
  val FallbackThreshold: Duration = Duration.ofMinutesUnsafe(2)

  def addToMap[K, V](map: mutable.HashMap[K, mutable.ArrayBuffer[V]], key: K, value: V): Unit = {
    map.get(key) match {
      case Some(acc) => acc.addOne(value)
      case None      => map(key) = mutable.ArrayBuffer(value)
    }
  }

  final case class BlockBatch(from: Int, to: Int) {
    override def toString: String = s"[$from .. $to]"
  }
  object BlockBatch {
    implicit val ordering: Ordering[BlockBatch] = Ordering.by(_.from)
  }

  final case class BlockDownloadTask(
      chainIndex: ChainIndex,
      fromHeight: Int,
      toHeight: Int,
      toHeader: Option[BlockHeader]
  ) {
    def heightRange: BlockHeightRange = BlockHeightRange.from(fromHeight, toHeight, 1)
    def size: Int                     = toHeight - fromHeight + 1
    def id: BlockBatch                = BlockBatch(fromHeight, toHeight)

    override def toString: String = s"${chainIndex.from.value}->${chainIndex.to.value}:$id"
  }

  def showTasks(tasks: AVector[BlockDownloadTask]): String = {
    tasks.mkString(", ")
  }

  final case class DownloadedBlock(block: Block, from: (BrokerActor, BrokerInfo))

  final class SyncStatePerChain(
      val originBroker: BrokerActor,
      val chainIndex: ChainIndex,
      val bestTip: ChainTip
  ) extends LazyLogging {
    private[sync] var nextFromHeight                                = ALPH.GenesisHeight
    private[sync] var skeletonHeightRange: Option[BlockHeightRange] = None
    private[sync] val batchIds  = mutable.SortedSet.empty[BlockBatch]
    private[sync] val taskQueue = mutable.Queue.empty[BlockDownloadTask]

    // Although we download blocks in order of height, the blocks we receive
    // may not be sorted by height. `downloadedBlocks` is used to sort the
    // downloaded blocks by height and place them into the `blockQueue`
    private[sync] val downloadedBlocks =
      mutable.SortedMap.empty[BlockBatch, AVector[DownloadedBlock]]
    private[sync] val pendingQueue = mutable.LinkedHashMap.empty[BlockHash, DownloadedBlock]
    private[sync] var validating   = mutable.Set.empty[BlockHash]

    private def addNewTask(task: BlockDownloadTask): Unit = {
      batchIds.addOne(task.id)
      taskQueue.enqueue(task)
    }

    def nextTask(handler: BlockDownloadTask => Boolean): Unit = {
      taskQueue.headOption.foreach { task =>
        if (handler(task)) taskQueue.dequeue()
      }
    }

    def initSkeletonHeights(broker: BrokerActor, from: Int): Option[BlockHeightRange] = {
      if (broker == originBroker) nextSkeletonHeights(from, MaxQueueSize) else None
    }

    private[sync] def nextSkeletonHeights(from: Int, size: Int): Option[BlockHeightRange] = {
      assume(from <= bestTip.height && size > BatchSize)
      if (bestTip.height - from < BatchSize) {
        // If the skeleton's finished, download any remaining blocks directly from the `originBroker`
        nextFromHeight = bestTip.height + 1
        skeletonHeightRange = None
        val task = BlockDownloadTask(chainIndex, from, bestTip.height, None)
        logger.debug(s"Trying to download latest blocks $task, chain index: $chainIndex")
        addNewTask(task)
        None
      } else {
        val maxHeight  = math.min(bestTip.height, from + size)
        val toHeight   = (from + ((maxHeight - from + 1) / BatchSize) * BatchSize) - 1
        val fromHeight = from + BatchSize - 1
        val range      = BlockHeightRange.from(fromHeight, toHeight, BatchSize)
        nextFromHeight = toHeight + 1
        skeletonHeightRange = Some(range)
        logger.debug(s"Moving on to the next skeleton, range: $range, chain index: $chainIndex")
        Some(range)
      }
    }

    def onSkeletonFetched(
        broker: BrokerActor,
        range: BlockHeightRange,
        headers: AVector[BlockHeader]
    ): Unit = {
      if (broker == originBroker && skeletonHeightRange.contains(range)) {
        skeletonHeightRange = None
        assume(range.length == headers.length)
        headers.foreachWithIndex { case (header, index) =>
          val toHeight   = range.at(index)
          val fromHeight = toHeight - BatchSize + 1
          val task       = BlockDownloadTask(chainIndex, fromHeight, toHeight, Some(header))
          addNewTask(task)
        }
      }
    }

    def onBlockDownloaded(
        from: BrokerActor,
        info: BrokerInfo,
        batchId: BlockBatch,
        blocks: AVector[Block]
    ): Unit = {
      if (batchIds.contains(batchId)) {
        logger.debug(s"Add the downloaded blocks $batchId to the buffer, chain index: $chainIndex")
        val fromBroker = (from, info)
        downloadedBlocks.addOne((batchId, blocks.map(b => DownloadedBlock(b, fromBroker))))
        moveToBlockQueue()
      }
    }

    @scala.annotation.tailrec
    private def moveToBlockQueue(): Unit = {
      // The `blockDownloaded` is ordered by height. If the first downloaded task is not what we need,
      // we will wait for the first task to complete and move all the downloaded blocks into the `blockQueue` in height order
      downloadedBlocks.headOption match {
        case Some((batchId, blocks)) if batchIds.headOption.contains(batchId) =>
          pendingQueue.addAll(blocks.map(b => (b.block.hash, b)))
          batchIds.remove(batchId)
          downloadedBlocks.remove(batchId)
          moveToBlockQueue()
        case _ => ()
      }
    }

    def tryValidateMoreBlocks(acc: mutable.ArrayBuffer[DownloadedBlock]): Unit = {
      if (validating.size < maxSyncBlocksPerChain && pendingQueue.nonEmpty) {
        val selected = pendingQueue.view.take(maxSyncBlocksPerChain).map(_._2).toSeq
        logger.debug(
          s"Sending more blocks for validation: ${selected.size}, chain index: $chainIndex"
        )
        val hashes = selected.map(_.block.hash)
        validating.addAll(hashes)
        pendingQueue.subtractAll(hashes)
        acc.addAll(selected)
      }
    }

    def isSkeletonFilled: Boolean = batchIds.forall(downloadedBlocks.contains)

    def handleFinalizedBlock(hash: BlockHash): Unit = {
      validating.remove(hash)
      ()
    }

    def tryMoveOn(): Option[BlockHeightRange] = {
      val queueSize = pendingQueue.size + validating.size
      if (
        queueSize <= MaxQueueSize / 2 &&
        nextFromHeight > ALPH.GenesisHeight && // We don't know the common ancestor height yet
        nextFromHeight <= bestTip.height &&
        skeletonHeightRange.isEmpty &&
        taskQueue.isEmpty &&
        isSkeletonFilled
      ) {
        val size = ((MaxQueueSize - queueSize) / BatchSize) * BatchSize
        nextSkeletonHeights(nextFromHeight, size)
      } else {
        None
      }
    }

    def putBack(task: BlockDownloadTask): Boolean = {
      if (batchIds.contains(task.id)) {
        if (taskQueue.isEmpty) {
          taskQueue.prepend(task)
        } else {
          val index = taskQueue.indexWhere(queued => BlockBatch.ordering.gt(queued.id, task.id))
          if (index == -1) {
            taskQueue.insert(taskQueue.length, task)
          } else {
            taskQueue.insert(index, task)
          }
        }
        true
      } else {
        false
      }
    }

    def putBack(tasks: AVector[BlockDownloadTask]): Unit = tasks.foreach(putBack)

    def taskSize: Int             = taskQueue.length
    def isTaskQueueEmpty: Boolean = taskQueue.isEmpty

    def isSynced(selfTip: ChainTip): Boolean = {
      selfTip.weight >= bestTip.weight || (
        // When syncing different chains from different nodes, it is possible that a block has already
        // been downloaded and sent to the `DependencyHandler`, but due to dependencies not being ready,
        // it cannot be added to the blockchain. This means that `selfTip.weight >= bestTip.weight` does not hold.
        // In this case, if all tasks have already been downloaded and sent to the `DependencyHandler`,
        // we consider the chain to be synced and start the next round of sync.
        nextFromHeight > bestTip.height &&
          skeletonHeightRange.isEmpty &&
          batchIds.isEmpty &&
          pendingQueue.isEmpty
      )
    }

    def isOriginPeer(broker: BrokerActor): Boolean = originBroker == broker
  }

  object SyncStatePerChain {
    def apply(chainIndex: ChainIndex, bestTip: ChainTip, broker: BrokerActor): SyncStatePerChain =
      new SyncStatePerChain(broker, chainIndex, bestTip)
  }
}
