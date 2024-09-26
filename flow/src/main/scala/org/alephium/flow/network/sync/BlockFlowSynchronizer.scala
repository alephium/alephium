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
import org.alephium.flow.handler.{AllHandlers, DependencyHandler, FlowHandler, IOBaseActor}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network._
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.{ProtocolV1, ProtocolV2, ProtocolVersion}
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector}
import org.alephium.util.EventStream.Subscriber

object BlockFlowSynchronizer {
  def props(blockflow: BlockFlow, allHandlers: AllHandlers)(implicit
      networkSetting: NetworkSetting,
      brokerConfig: BrokerConfig
  ): Props =
    Props(new BlockFlowSynchronizer(blockflow, allHandlers))

  sealed trait Command
  case object Sync                                                      extends Command
  final case class SyncInventories(hashes: AVector[AVector[BlockHash]]) extends Command
  final case class BlockFinalized(hash: BlockHash)                      extends Command
  case object CleanDownloading                                          extends Command
  final case class BlockAnnouncement(hash: BlockHash)                   extends Command
  final case class ChainState(tips: AVector[ChainTip])                  extends Command
  final case class Ancestors(chains: AVector[(ChainIndex, Int)])        extends Command
  final case class Skeletons(
      requests: AVector[(ChainIndex, AVector[Int])],
      responses: AVector[AVector[BlockHeader]]
  ) extends Command
  final case class BlockDownloaded(
      result: AVector[(SyncState.BlockDownloadTask, AVector[Block], Boolean)]
  ) extends Command
}

class BlockFlowSynchronizer(val blockflow: BlockFlow, val allHandlers: AllHandlers)(implicit
    val networkSetting: NetworkSetting,
    val brokerConfig: BrokerConfig
) extends IOBaseActor
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
  }

  override def receive: Receive = common orElse handleV2 orElse updateNodeSyncStatus

  def common: Receive = {
    case InterCliqueManager.HandShaked(broker, remoteBrokerInfo, _, _, protocolVersion) =>
      addBroker(broker, remoteBrokerInfo, protocolVersion)
    case BlockFinalized(hash) =>
      finalized(hash)
      onBlockFinalized(hash)
    case CleanDownloading        => cleanupSyncing(networkSetting.syncExpiryPeriod)
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
    case SyncInventories(hashes) => download(hashes)
    case Terminated(actor)       => removeBroker(ActorRefT(actor))
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

    case chainState: FlowHandler.ChainState =>
      samplePeers(ProtocolV2).foreach { case (actor, broker) =>
        actor ! BrokerHandler.ChainState(chainState.filterFor(broker.info))
      }
      handleSelfChainState(chainState.tips)

    case BlockFlowSynchronizer.ChainState(tips) =>
      handlePeerChainState(tips)

    case BlockFlowSynchronizer.Ancestors(ancestors) =>
      handleAncestors(ancestors)

    case BlockFlowSynchronizer.Skeletons(requests, responses) =>
      handleSkeletons(requests, responses)

    case BlockFlowSynchronizer.BlockDownloaded(result) =>
      handleBlockDownloaded(result)

    case Terminated(actor) => onBrokerTerminated(ActorRefT(actor))
  }
}

trait SyncState { _: BlockFlowSynchronizer =>
  import BrokerStatusTracker._
  import SyncState._

  private var isSyncing     = false
  private val bestChainTips = mutable.HashMap.empty[ChainIndex, (BrokerActor, ChainTip)]
  private var selfChainTips: Option[AVector[ChainTip]] = None
  private val syncingChains = mutable.HashMap.empty[ChainIndex, SyncStatePerChain]

  def handleBlockDownloaded(
      result: AVector[(BlockDownloadTask, AVector[Block], Boolean)]
  ): Unit = {
    val broker: BrokerActor = ActorRefT(sender())
    getBrokerStatus(broker).foreach(handleBlockDownloaded(broker, _, result))
    tryValidateMoreBlocks()
    downloadBlocks()
  }

  private def tryValidateMoreBlocks(): Unit = {
    val acc = mutable.ArrayBuffer.empty[DownloadedBlock]
    syncingChains.foreach(_._2.tryValidateMoreBlocks(acc))
    acc.groupBy(_.from).foreach { case (from, blocks) =>
      val dataOrigin = DataOrigin.InterClique(from._2)
      val addFlowData =
        DependencyHandler.AddFlowData(AVector.from(blocks.map(_.block)), dataOrigin)
      allHandlers.dependencyHandler.tell(addFlowData, from._1.ref)
    }
  }

  private def clearMissedBlocks(chainIndex: ChainIndex): Unit = {
    brokers.foreach(_._2.clearMissedBlocks(chainIndex))
  }

  private def handleBlockDownloaded(
      broker: BrokerActor,
      brokerStatus: BrokerStatus,
      result: AVector[(BlockDownloadTask, AVector[Block], Boolean)]
  ): Unit = {
    var continue = true
    var index    = 0
    while (continue && index < result.length) {
      val (task, blocks, isValid) = result(index)
      brokerStatus.removePendingTask(task)
      syncingChains.get(task.chainIndex).foreach { state =>
        if (isValid) {
          state.onBlockDownloaded(broker, brokerStatus.info, task.id, blocks)
          if (state.isSkeletonFilled) clearMissedBlocks(state.chainIndex)
        } else {
          log.warning(
            s"The broker ${brokerStatus.info.address} do not have the required blocks, " +
              s"put back the task to the queue, chain: ${state.chainIndex}, task id: ${task.id}"
          )
          state.putBack(task)
          brokerStatus.addMissedBlocks(task.chainIndex, task.id)
          continue = !checkMissedBlocks(state, task.id)
        }
      }
      index += 1
    }
  }

  private def checkMissedBlocks(state: SyncStatePerChain, taskId: TaskId): Boolean = {
    val isOriginBrokerInvalid = brokers.view
      .filter(_._2.getChainTip(state.chainIndex).exists(_.height >= taskId.to))
      .forall(_._2.containsMissedBlocks(state.chainIndex, taskId))
    if (isOriginBrokerInvalid) {
      log.info("All the brokers do not have the required blocks, stop the origin broker and resync")
      // TODO: publish the broker's misbehavior and stop the broker
      onBrokerTerminated(state.originBroker)
    }
    isOriginBrokerInvalid
  }

  def handlePeerChainState(tips: AVector[ChainTip]): Unit = {
    val brokerActor: BrokerActor = ActorRefT(sender())
    getBrokerStatus(brokerActor).foreach(_.updateTips(tips))
    tips.foreach { chainTip =>
      val chainIndex = chainTip.chainIndex
      bestChainTips.get(chainIndex) match {
        case Some((_, current)) =>
          if (chainTip.weight > current.weight) {
            bestChainTips(chainIndex) = (brokerActor, chainTip)
          }
        case None => bestChainTips(chainIndex) = (brokerActor, chainTip)
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def getSelfTip(chainIndex: ChainIndex): ChainTip = {
    assume(selfChainTips.isDefined)
    val groupIndex = brokerConfig.groupIndexOfBroker(chainIndex.from)
    selfChainTips.get(groupIndex * brokerConfig.groups + chainIndex.to.value)
  }

  private def hasBestChainTips: Boolean = {
    val chainIndexes = brokerConfig.chainIndexes
    chainIndexes.length == bestChainTips.size && chainIndexes.forall(bestChainTips.contains)
  }

  def handleSelfChainState(selfChainTips: AVector[ChainTip]): Unit = {
    this.selfChainTips = Some(selfChainTips)
    if (!isSyncing) tryStartSync()
  }

  // TODO: what should we do if the block is invalid?
  def onBlockFinalized(hash: BlockHash): Unit = {
    if (isSyncing) {
      val chainIndex = ChainIndex.from(hash)
      syncingChains.get(chainIndex).foreach(_.handleFinalizedBlock(hash))
      tryValidateMoreBlocks()
      if (isSynced) {
        resync()
      } else {
        tryMoveOn()
        downloadBlocks()
      }
    }
  }

  private def isSynced: Boolean = {
    syncingChains.forall { case (chainIndex, state) =>
      state.isSynced(getSelfTip(chainIndex))
    }
  }

  private def tryMoveOn(): Unit = {
    val requests =
      mutable.HashMap.empty[BrokerActor, mutable.ArrayBuffer[(ChainIndex, AVector[Int])]]
    syncingChains.foreach { case (chainIndex, state) =>
      state.tryMoveOn() match {
        case Some(heights) => addToMap(requests, state.originBroker, (chainIndex, heights))
        case None          => ()
      }
    }
    requests.foreach { case (broker, requestsPerActor) =>
      broker ! BrokerHandler.GetSkeletons(AVector.from(requestsPerActor))
    }
  }

  private def tryStartSync(): Unit = {
    // TODO: fallback to v1 if we cannot get the best chain tips within the specified time?
    if (hasBestChainTips) {
      val chains = mutable.ArrayBuffer.empty[(ChainIndex, BrokerActor, ChainTip, ChainTip)]
      brokerConfig.chainIndexes.foreach { chainIndex =>
        val selfTip                = getSelfTip(chainIndex)
        val (brokerActor, bestTip) = bestChainTips(chainIndex)
        if (bestTip.weight > selfTip.weight) {
          chains.addOne((chainIndex, brokerActor, bestTip, selfTip))
        }
      }
      if (chains.nonEmpty) startSync(chains)
    }
  }

  def startSync(chains: collection.Seq[(ChainIndex, BrokerActor, ChainTip, ChainTip)]): Unit = {
    assume(!isSyncing)
    isSyncing = true
    log.debug("Start syncing")

    val requestsPerBroker = mutable.HashMap
      .empty[BrokerActor, mutable.ArrayBuffer[(ChainIndex, ChainTip, ChainTip)]]
    chains.foreach { case (chainIndex, brokerActor, bestTip, selfTip) =>
      syncingChains(chainIndex) = SyncStatePerChain.apply(chainIndex, bestTip, brokerActor)
      requestsPerBroker.get(brokerActor) match {
        case Some(value) => value.addOne((chainIndex, bestTip, selfTip))
        case None =>
          requestsPerBroker(brokerActor) = mutable.ArrayBuffer((chainIndex, bestTip, selfTip))
      }
    }
    requestsPerBroker.foreach { case (broker, chainsPerBroker) =>
      val requests = chainsPerBroker.map { case (chainIndex, bestTip, selfTip) =>
        (chainIndex, bestTip, selfTip)
      }
      broker ! BrokerHandler.GetAncestors(AVector.from(requests))
    }
  }

  def handleAncestors(ancestors: AVector[(ChainIndex, Int)]): Unit = {
    val brokerActor: BrokerActor = ActorRefT(sender())
    val requests                 = mutable.ArrayBuffer.empty[(ChainIndex, AVector[Int])]
    ancestors.foreach { case (chainIndex, height) =>
      syncingChains.get(chainIndex).foreach { state =>
        state.initSkeletonHeights(brokerActor, height + 1) match {
          case Some(heights) => requests.addOne((chainIndex, heights))
          case None          => ()
        }
      }
    }
    if (requests.nonEmpty) {
      brokerActor ! BrokerHandler.GetSkeletons(AVector.from(requests))
    }
    downloadBlocks()
  }

  def handleSkeletons(
      requests: AVector[(ChainIndex, AVector[Int])],
      responses: AVector[AVector[BlockHeader]]
  ): Unit = {
    val brokerActor: BrokerActor = ActorRefT(sender())
    assume(requests.length == responses.length)
    requests.foreachWithIndex { case ((chainIndex, heights), index) =>
      val headers = responses(index)
      syncingChains.get(chainIndex).foreach(_.onSkeletonFetched(brokerActor, heights, headers))
    }
    downloadBlocks()
  }

  private def downloadBlocks(): Unit = {
    val chains = syncingChains.values.filter(!_.isTaskQueueEmpty)
    if (chains.nonEmpty) {
      val ordered  = AVector.from(chains).sortBy(_.taskSize)(Ordering[Int].reverse)
      val allTasks = mutable.HashMap.empty[BrokerActor, mutable.ArrayBuffer[BlockDownloadTask]]
      var continue = true
      while (continue) {
        val newTasks = collectTasks(ordered)
        newTasks.foreach { case (broker, task) => addToMap(allTasks, broker, task) }
        continue = newTasks.nonEmpty
      }
      allTasks.foreach { case (brokerActor, tasksPerBroker) =>
        val tasks = AVector.from(tasksPerBroker)
        log.debug(
          s"Trying to download blocks from ${remoteAddress(brokerActor)}, tasks: ${SyncState.showTasks(tasks)}"
        )
        brokerActor ! BrokerHandler.DownloadBlockTasks(tasks)
      }
    }
  }

  private def collectTasks(chains: AVector[SyncStatePerChain]) = {
    val acc = mutable.ArrayBuffer.empty[(BrokerActor, BlockDownloadTask)]
    chains.foreach { state =>
      state.nextTask { task =>
        brokers.find(_._2.canDownload(task)) match {
          case Some((broker, brokerStatus)) =>
            brokerStatus.addPendingTask(task)
            acc.addOne((broker, task))
            true
          case None => false
        }
      }
    }
    acc
  }

  private def clearSyncState(): Unit = {
    syncingChains.clear()
    isSyncing = false
    brokers.foreach(_._2.clear())
  }

  private def resync(): Unit = {
    log.debug("Clear syncing state and resync")
    clearSyncState()
    tryStartSync()
  }

  private def updateBestChainTips(terminatedBroker: BrokerActor): Unit = {
    assume(getBrokerStatus(terminatedBroker).isEmpty)
    val tipsFromBroker = bestChainTips.filter(_._2._1 == terminatedBroker)
    if (tipsFromBroker.nonEmpty) {
      tipsFromBroker.foreach { case (chainIndex, _) =>
        val selected = brokers.view
          .map { case (newBroker, status) =>
            (newBroker, status.getChainTip(chainIndex))
          }
          .collect { case (newBroker, Some(chainTip)) =>
            (newBroker, chainTip)
          }
          .maxByOption(_._2.weight)
        selected match {
          case Some(selected) => bestChainTips(chainIndex) = selected
          case None           => bestChainTips.remove(chainIndex)
        }
      }
    }
  }

  def onBrokerTerminated(broker: BrokerActor): Unit = {
    val status = getBrokerStatus(broker)
    removeBroker(broker)
    status.foreach(onBrokerTerminated(broker, _))
  }

  private def onBrokerTerminated(broker: BrokerActor, status: BrokerStatus): Unit = {
    updateBestChainTips(broker)
    if (isSyncing) {
      val needToResync = syncingChains.exists(_._2.isOriginPeer(broker))
      if (needToResync) {
        log.info(s"Resync due to the origin broker ${status.info.address} terminated")
        resync()
      } else {
        log.info(s"Reschedule the pending tasks from the terminated broker ${status.info.address}")
        val pendingTasks = status.getPendingTasks
        if (pendingTasks.nonEmpty) {
          pendingTasks.groupBy(_.chainIndex).foreach { case (chainIndex, tasks) =>
            syncingChains.get(chainIndex).foreach(_.putBack(AVector.from(tasks)))
          }
          downloadBlocks()
        }
      }
    }
  }
}

object SyncState {
  import BrokerStatusTracker.BrokerActor

  val SkeletonSize: Int = 16
  val BatchSize: Int    = maxSyncBlocksPerChain
  val MaxQueueSize: Int = SkeletonSize * BatchSize

  final case class Skeleton(from: Int, to: Int, step: Int) {
    lazy val heights: AVector[Int] = AVector.from(from.to(to, step))
  }

  def addToMap[K, V](map: mutable.HashMap[K, mutable.ArrayBuffer[V]], key: K, value: V): Unit = {
    map.get(key) match {
      case Some(acc) => acc.addOne(value)
      case None      => map(key) = mutable.ArrayBuffer(value)
    }
  }

  def addToMap[K, V](
      map: mutable.HashMap[K, mutable.ArrayBuffer[V]],
      key: K,
      values: Iterable[V]
  ): Unit = {
    map.get(key) match {
      case Some(acc) => acc.addAll(values)
      case None      => map(key) = mutable.ArrayBuffer.from(values)
    }
  }

  final case class TaskId(from: Int, to: Int) {
    def size: Int                 = to - from + 1
    override def toString: String = s"[$from .. $to]"
  }
  object TaskId {
    implicit val ordering: Ordering[TaskId] = Ordering.by(_.from)
  }

  final case class BlockDownloadTask(
      chainIndex: ChainIndex,
      from: Int,
      to: Int,
      toHeader: Option[BlockHeader]
  ) {
    def heights: AVector[Int] = AVector.from(from to to)
    def size: Int             = to - from + 1
    def id: TaskId            = TaskId(from, to)

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
    private[sync] var nextFromHeight                        = ALPH.GenesisHeight
    private[sync] var skeletonHeights: Option[AVector[Int]] = None
    private[sync] val taskIds                               = mutable.SortedSet.empty[TaskId]
    private[sync] val taskQueue                             = mutable.Queue.empty[BlockDownloadTask]

    // Although we download blocks in order of height, the blocks we receive
    // may not be sorted by height. `downloadedBlocks` is used to sort the
    // downloaded blocks by height and place them into the `blockQueue`
    private[sync] val downloadedBlocks = mutable.SortedMap.empty[TaskId, AVector[DownloadedBlock]]
    private[sync] val blockQueue       = mutable.LinkedHashMap.empty[BlockHash, DownloadedBlock]
    private[sync] var validating       = mutable.Set.empty[BlockHash]

    private def addNewTask(task: BlockDownloadTask): Unit = {
      taskIds.addOne(task.id)
      taskQueue.enqueue(task)
    }

    def nextTask(handler: BlockDownloadTask => Boolean): Unit = {
      taskQueue.headOption.foreach { task =>
        if (handler(task)) taskQueue.dequeue()
      }
    }

    def initSkeletonHeights(broker: BrokerActor, from: Int): Option[AVector[Int]] = {
      if (broker == originBroker) nextSkeletonHeights(from, MaxQueueSize) else None
    }

    private[sync] def nextSkeletonHeights(from: Int, size: Int): Option[AVector[Int]] = {
      assume(from <= bestTip.height && size > BatchSize)
      if (bestTip.height - from < BatchSize) {
        // TODO: download the latest blocks from `originBroker`
        nextFromHeight = bestTip.height + 1
        skeletonHeights = None
        val task = BlockDownloadTask(chainIndex, from, bestTip.height, None)
        logger.debug(s"Trying to download latest blocks $task, chain index: $chainIndex")
        addNewTask(task)
        None
      } else {
        val maxHeight  = math.min(bestTip.height, from + size)
        val toHeight   = (from + ((maxHeight - from + 1) / BatchSize) * BatchSize) - 1
        val fromHeight = from + BatchSize - 1
        val heights    = AVector.from(fromHeight.to(toHeight, BatchSize))
        nextFromHeight = toHeight + 1
        skeletonHeights = Some(heights)
        logger.debug(s"Moving on to the next skeleton, heights: $heights, chain index: $chainIndex")
        Some(heights)
      }
    }

    def onSkeletonFetched(
        broker: BrokerActor,
        heights: AVector[Int],
        headers: AVector[BlockHeader]
    ): Unit = {
      if (broker == originBroker && skeletonHeights.contains(heights)) {
        skeletonHeights = None
        assume(heights.length == headers.length)
        headers.foreachWithIndex { case (header, index) =>
          val toHeight   = heights(index)
          val fromHeight = toHeight - BatchSize + 1
          val task       = BlockDownloadTask(chainIndex, fromHeight, toHeight, Some(header))
          addNewTask(task)
        }
      }
    }

    def onBlockDownloaded(
        from: BrokerActor,
        info: BrokerInfo,
        taskId: TaskId,
        blocks: AVector[Block]
    ): Unit = {
      if (taskIds.contains(taskId)) {
        logger.debug(s"Add the downloaded blocks $taskId to the buffer, chain index: $chainIndex")
        val fromBroker = (from, info)
        downloadedBlocks.addOne((taskId, blocks.map(b => DownloadedBlock(b, fromBroker))))
        moveToBlockQueue()
      }
    }

    @scala.annotation.tailrec
    private def moveToBlockQueue(): Unit = {
      downloadedBlocks.headOption match {
        case Some((taskId, blocks)) if taskIds.headOption.contains(taskId) =>
          blockQueue.addAll(blocks.map(b => (b.block.hash, b)))
          taskIds.remove(taskId)
          downloadedBlocks.remove(taskId)
          moveToBlockQueue()
        case _ => ()
      }
    }

    def tryValidateMoreBlocks(acc: mutable.ArrayBuffer[DownloadedBlock]): Unit = {
      if (validating.size < BatchSize && blockQueue.nonEmpty) {
        val selected = blockQueue.view
          .filterNot(v => validating.contains(v._1))
          .take(BatchSize)
          .map(_._2)
          .toSeq
        logger.debug(
          s"Sending more blocks for validation: ${selected.size}, chain index: $chainIndex"
        )
        validating.addAll(selected.map(_.block.hash))
        acc.addAll(selected)
      }
    }

    def isSkeletonFilled: Boolean = taskIds.forall(downloadedBlocks.contains)

    def handleFinalizedBlock(hash: BlockHash): Unit = {
      blockQueue.remove(hash)
      validating.remove(hash)
      ()
    }

    def tryMoveOn(): Option[AVector[Int]] = {
      val queueSize = blockQueue.size
      if (
        nextFromHeight > ALPH.GenesisHeight && // We don't know the common ancestor height yet
        nextFromHeight <= bestTip.height &&
        skeletonHeights.isEmpty &&
        taskQueue.isEmpty &&
        queueSize <= MaxQueueSize / 2 &&
        isSkeletonFilled
      ) {
        val size = ((MaxQueueSize - queueSize) / BatchSize) * BatchSize
        nextSkeletonHeights(nextFromHeight, size)
      } else {
        None
      }
    }

    def putBack(task: BlockDownloadTask): Unit = {
      if (taskIds.contains(task.id)) {
        taskQueue.prepend(task)
      }
    }

    def putBack(tasks: AVector[BlockDownloadTask]): Unit = {
      tasks.sortBy(_.id).reverse.foreach(putBack)
    }

    def taskSize: Int             = taskQueue.length
    def isTaskQueueEmpty: Boolean = taskQueue.isEmpty

    def isSynced(selfTip: ChainTip): Boolean = selfTip.weight >= bestTip.weight

    def isOriginPeer(broker: BrokerActor): Boolean = originBroker == broker
  }

  object SyncStatePerChain {
    def apply(chainIndex: ChainIndex, bestTip: ChainTip, broker: BrokerActor): SyncStatePerChain =
      new SyncStatePerChain(broker, chainIndex, bestTip)
  }
}
