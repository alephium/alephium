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

import java.math.BigInteger
import java.net.InetSocketAddress

import scala.collection.mutable
import scala.util.Random

import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.{ActorSystem, Cancellable, Props, Terminated}

import org.alephium.flow.core.{maxSyncBlocksPerChain, BlockFlow, BlockHashPool}
import org.alephium.flow.handler._
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network._
import org.alephium.flow.network.broker.{BrokerHandler, ChainTipInfo, MisbehaviorManager}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.P2PVersion
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector, Duration, TimeStamp}
import org.alephium.util.EventStream.Publisher

// scalastyle:off file.size.limit
object BlockFlowSynchronizer {
  def build(system: ActorSystem, blockFlow: BlockFlow, allHandlers: AllHandlers)(implicit
      networkSetting: NetworkSetting,
      brokerConfig: BrokerConfig
  ): ActorRefT[Command] = {
    val actor =
      ActorRefT.build[Command](system, Props(new BlockFlowSynchronizer(blockFlow, allHandlers)))
    system.eventStream.subscribe(actor.ref, classOf[InterCliqueManager.SyncedResult])
    system.eventStream.subscribe(actor.ref, classOf[InterCliqueManager.HandShaked])
    system.eventStream.subscribe(actor.ref, classOf[ChainHandler.FlowDataValidationEvent])
    system.eventStream.subscribe(actor.ref, classOf[DependencyHandler.FlowDataAlreadyExist])
    system.eventStream.subscribe(actor.ref, classOf[DependencyHandler.PendingFlowDataEvicted])
    actor
  }

  sealed trait Command
  sealed trait V2Command                              extends Command
  case object Sync                                    extends Command
  final case class BlockAnnouncement(hash: BlockHash) extends Command
  final case class UpdateChainState(tips: AVector[ChainTip], remoteNearlySynced: Boolean)
      extends V2Command
  final case class UpdateAncestors(chains: AVector[(ChainIndex, Int)]) extends V2Command
  final case class UpdateSkeletons(
      requests: AVector[(ChainIndex, BlockHeightRange)],
      responses: AVector[AVector[BlockHeader]]
  ) extends V2Command
  final case class UpdateBlockDownloaded(
      result: AVector[(SyncState.BlockDownloadTask, AVector[Block], Boolean)]
  ) extends V2Command
  case object ContinueDownload                            extends V2Command
  private[sync] case object RecoverFromDependencyEviction extends V2Command
  final case class AddFlowData[T <: FlowData](datas: AVector[T], dataOrigin: DataOrigin)
      extends Command
}

class BlockFlowSynchronizer(val blockflow: BlockFlow, val allHandlers: AllHandlers)(implicit
    val networkSetting: NetworkSetting,
    val brokerConfig: BrokerConfig
) extends IOBaseActor
    with Publisher
    with BlockFetcher
    with BrokerStatusTracker
    with InterCliqueManager.NodeSyncStatus
    with BlockFlowSynchronizerV2 {
  import BlockFlowSynchronizer._
  import BrokerStatusTracker._

  override def preStart(): Unit = {
    super.preStart()
    scheduleSync()
  }

  override def receive: Receive = common orElse handleV2 orElse updateNodeSyncStatus

  def common: Receive = {
    case InterCliqueManager.HandShaked(broker, remoteBrokerInfo, _, clientInfo, p2pVersion) =>
      addBroker(broker, remoteBrokerInfo, clientInfo, p2pVersion)

    case BlockAnnouncement(hash) =>
      // When the node is synced, it should download new blocks only through block announcements.
      // Ignoring them may trigger a new round of synchronization using v2.
      if (!isSyncingUsingV2 || isNearSynced) handleBlockAnnouncement(hash)

    case AddFlowData(datas, dataOrigin) =>
      // When the node is synced, it should download new blocks only through block announcements.
      // Ignoring them may trigger a new round of synchronization using v2.
      val accepted = if (!isSyncingUsingV2 || isNearSynced) {
        requestedDependencyHashes.subtractAll(datas.map(_.hash))
        datas
      } else {
        datas.filter(data => requestedDependencyHashes.remove(data.hash))
      }
      if (accepted.nonEmpty) {
        val message = DependencyHandler.AddFlowData(accepted, dataOrigin)
        allHandlers.dependencyHandler.tell(message, sender())
      }
  }

  def addBroker(
      broker: BrokerActor,
      brokerInfo: BrokerInfo,
      clientInfo: String,
      p2pVersion: P2PVersion
  ): Unit = {
    log.debug(s"HandShaked with ${brokerInfo.address}")
    context.watch(broker.ref)
    brokers += broker -> BrokerStatus(brokerInfo, p2pVersion, clientInfo)
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

trait BlockFlowSynchronizerV2 extends SyncState {
  _: BlockFlowSynchronizer =>
  protected def handleSyncCommandV2(): Unit = {
    log.debug("Sync V2: Send chain state to the network")
    allHandlers.flowHandler ! FlowHandler.GetChainState
  }

  private[sync] val nearlySyncedRemoteBrokers = mutable.Set.empty[BrokerStatusTracker.BrokerActor]

  private def sendChainStateToPeers(chainState: FlowHandler.UpdateChainState): Unit = {
    val peers = samplePeers()
    peers.foreach { case (actor, broker) =>
      actor ! BrokerHandler.SendChainState(chainState.filterFor(broker.info))
    }
    nearlySyncedRemoteBrokers.view
      .filter(b => !peers.exists(_._1 == b))
      .foreach { broker =>
        getBrokerStatus(broker).foreach { remote =>
          broker ! BrokerHandler.SendChainState(chainState.filterFor(remote.info))
        }
      }
    nearlySyncedRemoteBrokers.clear()
  }

  def handleV2: Receive = {
    case BlockFlowSynchronizer.Sync =>
      if (brokers.nonEmpty) {
        handleSyncCommandV2()
        auditMissingDependencies()
        auditStalledValidation()
      }
      scheduleSync()

    case chainState: FlowHandler.UpdateChainState =>
      sendChainStateToPeers(chainState)
      handleSelfChainState(chainState.tips)

    case BlockFlowSynchronizer.UpdateChainState(tips, remoteNearlySynced) =>
      handlePeerChainState(tips, remoteNearlySynced)

    case BlockFlowSynchronizer.UpdateAncestors(ancestors) =>
      handleAncestors(ancestors)

    case BlockFlowSynchronizer.UpdateSkeletons(requests, responses) =>
      handleSkeletons(requests, responses)

    case BlockFlowSynchronizer.UpdateBlockDownloaded(result) =>
      handleBlockDownloaded(result)

    case BlockFlowSynchronizer.ContinueDownload =>
      downloadBlocks()

    case event: ChainHandler.FlowDataValidationEvent =>
      onBlockProcessedV2(event)

    case DependencyHandler.FlowDataAlreadyExist(data) =>
      onBlockProcessed(data)

    case event: DependencyHandler.PendingFlowDataEvicted =>
      handlePendingFlowDataEvicted(event)

    case DependencyHandler.Pendings(hashes) =>
      handleDependencyPendings(hashes)

    case dependencies: DependencyHandler.MissingDependencies =>
      handleMissingDependencies(dependencies)

    case BlockFlowSynchronizer.RecoverFromDependencyEviction =>
      recoverFromDependencyEviction()

    case Terminated(actor) => onBrokerTerminated(ActorRefT(actor))
  }
}

// scalastyle:off number.of.methods
trait SyncState { _: BlockFlowSynchronizer =>
  import BrokerStatusTracker._
  import SyncState._

  private[sync] var isSyncingUsingV2 = false
  private[sync] val bestChainTips    = FlattenIndexedArray.empty[(BrokerActor, ChainTip)]
  private[sync] val selfChainTips    = FlattenIndexedArray.empty[ChainTip]
  private[sync] val syncingChains    = FlattenIndexedArray.empty[SyncStatePerChain]
  private[sync] var activeSyncTarget: Option[RemoteSyncTarget] = None
  private var _isNearSynced                                    = false

  private[sync] val evictedPendingHashes = mutable.HashSet.empty[BlockHash]
  private[sync] var dependencyEvictionRecoveryTask: Option[Cancellable] = None
  private[sync] val auditedValidatingHashes        = mutable.HashSet.empty[BlockHash]
  private[sync] var dependencyAuditInFlight        = false
  private[sync] var nextDependencyAuditAt          = TimeStamp.zero
  private[sync] var missingDependencyQueryInFlight = false
  private[sync] var nextMissingDependencyQueryAt   = TimeStamp.zero
  private[sync] val requestedDependencyHashes      = mutable.HashSet.empty[BlockHash]
  private[sync] val dependencyFetchPeers =
    mutable.HashMap.empty[BlockHash, mutable.HashSet[BrokerActor]]

  private[sync] val invalidSyncTargets = mutable.LinkedHashSet.empty[RemoteSyncTargetId]
  private[sync] val deferredSyncTargets =
    mutable.LinkedHashMap.empty[RemoteSyncTargetId, TimeStamp]

  private[sync] def isNearSynced: Boolean = _isNearSynced

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
    syncingChains.foreach(_.tryValidateMoreBlocks(acc, isNearSynced))
    validateMoreBlocks(acc)
  }

  private def tryValidateMoreBlocksFromChain(chainState: SyncStatePerChain): Unit = {
    val acc = mutable.ArrayBuffer.empty[DownloadedBlock]
    chainState.tryValidateMoreBlocks(acc, isNearSynced)
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

  @inline private[sync] def allV2BrokersMissBlocks(
      chainIndex: ChainIndex,
      batchId: BlockBatch
  ): Boolean = {
    brokers.view
      .forall(_._2.missOrUnableDownload(chainIndex, batchId))
  }

  private def handleMissedBlocks(state: SyncStatePerChain, batchId: BlockBatch): Unit = {
    if (allV2BrokersMissBlocks(state.chainIndex, batchId)) {
      // No one can fill in the skeleton, disconnect from the origin peer and restart the sync
      // once we receive the `Terminated` message.
      log.error(
        "All the brokers do not have the required blocks, stop the origin broker and resync"
      )
      val misbehavior = MisbehaviorManager.InvalidFlowData(remoteAddress(state.originBroker))
      publishEvent(misbehavior)
      markActiveSyncTargetDeferred(
        s"no connected broker could provide ${state.chainIndex.prettyString} batch $batchId"
      )
      context.stop(state.originBroker.ref)
    }
  }

  def handlePeerChainState(tips: AVector[ChainTip], remoteNearlySynced: Boolean): Unit = {
    val brokerActor: BrokerActor = ActorRefT(sender())
    if (remoteNearlySynced) {
      nearlySyncedRemoteBrokers.addOne(brokerActor)
    } else {
      nearlySyncedRemoteBrokers.remove(brokerActor)
    }

    getBrokerStatus(brokerActor).foreach(_.updateTips(tips))
    recomputeBestChainTips()

    _isNearSynced = checkIsNearSynced
  }

  private[sync] def recomputeBestChainTips(): Unit = {
    bestChainTips.reset()
    brokerConfig.chainIndexes.foreach { chainIndex =>
      val selected = brokers.view
        .flatMap { case (broker, status) =>
          status.getChainTip(chainIndex).map(broker -> _)
        }
        .reduceOption { (current, candidate) =>
          if (compareChainTips(candidate._2, current._2) > 0) candidate else current
        }
      bestChainTips(chainIndex) = selected
    }
  }

  private[sync] def remoteSyncTargets: AVector[RemoteSyncTarget] = {
    val targets = brokers
      .groupBy(_._2.info.cliqueId)
      .iterator
      .flatMap { case (cliqueId, cliqueBrokers) =>
        val chains = AVector.from(brokerConfig.chainIndexes.iterator.flatMap { chainIndex =>
          cliqueBrokers.view
            .flatMap { case (broker, status) =>
              status.getChainTip(chainIndex).map(tip => (chainIndex, broker, tip))
            }
            .reduceOption { (current, candidate) =>
              if (compareChainTips(candidate._3, current._3) > 0) candidate else current
            }
        })
        if (chains.nonEmpty) Some(RemoteSyncTarget(cliqueId, chains)) else None
      }
      .toSeq
    AVector.from(targets)
  }

  private[sync] def selectSyncTarget(): Option[SelectedSyncTarget] = {
    selectSyncTarget(TimeStamp.now())
  }

  private[sync] def selectSyncTarget(now: TimeStamp): Option[SelectedSyncTarget] = {
    deferredSyncTargets.filterInPlace { case (_, expiresAt) => expiresAt > now }
    remoteSyncTargets.toIterable
      .filter { target =>
        !invalidSyncTargets.contains(target.id) && !deferredSyncTargets.contains(target.id)
      }
      .flatMap { target =>
        val usefulChains = target.chains.filter { case (chainIndex, _, remoteTip) =>
          selfChainTips(chainIndex).exists(compareChainTips(remoteTip, _) > 0)
        }
        if (usefulChains.isEmpty) {
          None
        } else {
          val workAdvantage = usefulChains.fold(BigInteger.ZERO) {
            case (acc, (chainIndex, _, remoteTip)) =>
              selfChainTips(chainIndex).fold(acc) { selfTip =>
                acc.add(remoteTip.weight.value.subtract(selfTip.weight.value))
              }
          }
          Some(SelectedSyncTarget(target, usefulChains, workAdvantage))
        }
      }
      .reduceOption { (current, candidate) =>
        val workComparison = candidate.workAdvantage.compareTo(current.workAdvantage)
        if (
          workComparison > 0 ||
          (workComparison == 0 && candidate.target.cliqueId > current.target.cliqueId)
        ) {
          candidate
        } else {
          current
        }
      }
  }

  private def checkIsNearSynced: Boolean = {
    selfChainTips.nonEmpty && selfChainTips.forall { selfTip =>
      bestChainTips(selfTip.chainIndex).exists { case (_, bestTip) =>
        (bestTip.height - selfTip.height) < maxSyncBlocksPerChain
      }
    }
  }

  def handleSelfChainState(chainTips: AVector[ChainTip]): Unit = {
    chainTips.foreach { chainTip =>
      this.selfChainTips(chainTip.chainIndex) = Some(chainTip)
    }
    _isNearSynced = checkIsNearSynced
    if (!isSyncingUsingV2) {
      tryStartSync()
    } else if (isSynced) {
      tryStartNextSyncRound()
    }
  }

  def onBlockProcessedV2(event: ChainHandler.FlowDataValidationEvent): Unit = {
    if (isSyncingUsingV2) {
      val isBlockValid = event match {
        case _: ChainHandler.FlowDataAdded   => true
        case _: ChainHandler.InvalidFlowData => false
      }
      val block = event.data
      if (isBlockValid) {
        onBlockProcessed(block)
      } else {
        log.info(s"Block ${block.hash.toHexString} is invalid, resync")
        val isFromOriginBroker = syncingChains(block.chainIndex).exists { state =>
          state.validating.contains(block.hash) &&
          getBrokerStatus(state.originBroker).exists(status => event.origin.isFrom(status.info))
        }
        if (isFromOriginBroker) {
          invalidateActiveSyncTarget(s"${block.hash.shortHex} failed validation")
        }
        resync()
      }
    }
  }

  protected[this] def onBlockProcessed(data: FlowData): Unit = {
    onBlockProcessed(data.chainIndex, data.hash)
  }

  private def onBlockProcessed(chainIndex: ChainIndex, hash: BlockHash): Unit = {
    syncingChains(chainIndex).foreach { chainState =>
      chainState.handleFinalizedBlock(hash)
      tryValidateMoreBlocksFromChain(chainState)
      tryMoveOn(chainState)
    }
  }

  private def hasEvictedValidatingBlocks: Boolean = {
    syncingChains.exists(_.validating.exists(evictedPendingHashes.contains))
  }

  private def scheduleDependencyRecovery(hashes: AVector[BlockHash], reason: String): Unit = {
    val active = hashes.filter { hash =>
      syncingChains(ChainIndex.from(hash)).exists(_.validating.contains(hash))
    }
    if (isSyncingUsingV2 && active.nonEmpty) {
      evictedPendingHashes.addAll(active)
      if (dependencyEvictionRecoveryTask.isEmpty) {
        log.debug(s"Schedule a Sync V2 retry because $reason")
        dependencyEvictionRecoveryTask = Some(
          scheduleCancellableOnce(
            self,
            BlockFlowSynchronizer.RecoverFromDependencyEviction,
            networkSetting.syncExpiryPeriod
          )
        )
      }
    }
  }

  private[sync] def handlePendingFlowDataEvicted(
      event: DependencyHandler.PendingFlowDataEvicted
  ): Unit = {
    scheduleDependencyRecovery(
      event.hashes,
      s"${event.hashes.length} pending flow data entries were evicted due to ${event.reason}"
    )
  }

  private[sync] def auditStalledValidation(): Unit = {
    auditStalledValidation(TimeStamp.now())
  }

  private[sync] def auditStalledValidation(now: TimeStamp): Unit = {
    if (
      isSyncingUsingV2 &&
      !dependencyAuditInFlight &&
      dependencyEvictionRecoveryTask.isEmpty &&
      now >= nextDependencyAuditAt
    ) {
      syncingChains.foreach { state =>
        if (state.isValidationStalled(now, networkSetting.dependencyExpiryPeriod)) {
          auditedValidatingHashes.addAll(state.validating)
        }
      }
      if (auditedValidatingHashes.nonEmpty) {
        dependencyAuditInFlight = true
        nextDependencyAuditAt = now.plusUnsafe(networkSetting.syncExpiryPeriod)
        allHandlers.dependencyHandler.tell(DependencyHandler.GetPendings, self)
      }
    }
  }

  private[sync] def missingDependencyRetryPeriod: Duration = {
    val rateLimiterWindow = getRateLimiterWindowSize
    if (networkSetting.syncExpiryPeriod > rateLimiterWindow) {
      networkSetting.syncExpiryPeriod
    } else {
      rateLimiterWindow
    }
  }

  private[sync] def auditMissingDependencies(): Unit = {
    auditMissingDependencies(TimeStamp.now())
  }

  private[sync] def auditMissingDependencies(now: TimeStamp): Unit = {
    if (
      isSyncingUsingV2 &&
      !missingDependencyQueryInFlight &&
      dependencyEvictionRecoveryTask.isEmpty &&
      now >= nextMissingDependencyQueryAt
    ) {
      val roots = mutable.LinkedHashSet.empty[BlockHash]
      syncingChains.foreach { state =>
        if (state.isValidationStalled(now, missingDependencyRetryPeriod)) {
          roots.addAll(state.validating)
        }
      }
      if (roots.nonEmpty) {
        missingDependencyQueryInFlight = true
        nextMissingDependencyQueryAt = now.plusUnsafe(missingDependencyRetryPeriod)
        requestedDependencyHashes.clear()
        allHandlers.dependencyHandler.tell(
          DependencyHandler.GetMissingDependencies(
            AVector.from(roots),
            MaxMissingDependenciesPerQuery
          ),
          self
        )
      }
    }
  }

  private def isActivelyValidating(hash: BlockHash): Boolean = {
    syncingChains(ChainIndex.from(hash)).exists(_.validating.contains(hash))
  }

  private[sync] def handleMissingDependencies(
      dependencies: DependencyHandler.MissingDependencies
  ): Unit = {
    if (missingDependencyQueryInFlight) {
      missingDependencyQueryInFlight = false
      val activeRoots  = dependencies.roots.filter(isActivelyValidating)
      val pendingRoots = dependencies.pendingRoots.toSet
      val absentRoots  = activeRoots.filterNot(pendingRoots.contains)
      val missing      = dependencies.hashes
      val missingSet   = missing.toSet
      dependencyFetchPeers.filterInPlace { case (hash, _) => missingSet.contains(hash) }
      requestedDependencyHashes.filterInPlace(missingSet.contains)

      escapeIOError(absentRoots.partitionE(blockflow.contains)) { case (stored, dropped) =>
        stored.foreach(hash => onBlockProcessed(ChainIndex.from(hash), hash))
        if (dropped.nonEmpty) {
          deferActiveSyncTarget(
            s"${dropped.length} validating blocks disappeared from the dependency cache"
          )
        } else if (missing.nonEmpty) {
          requestMissingDependencies(missing)
        }
      }
    }
  }

  private def requestMissingDependencies(hashes: AVector[BlockHash]): Unit = {
    val targetCliqueId = activeSyncTarget.map(_.cliqueId)
    val assignments    = mutable.HashMap.empty[BrokerActor, Int]
    val selected       = mutable.ArrayBuffer.empty[(BlockHash, BrokerActor, Boolean)]
    var unavailable: Option[BlockHash] = None

    hashes.foreach { hash =>
      if (unavailable.isEmpty) {
        val attempted = dependencyFetchPeers.getOrElse(hash, mutable.HashSet.empty)
        val candidates = if (attempted.size >= BlockFetcher.MaxDownloadTimes) {
          Seq.empty
        } else {
          brokers.zipWithIndex.filter { case ((broker, status), _) =>
            status.info.contains(ChainIndex.from(hash).from) && !attempted.contains(broker)
          }
        }
        candidates.minByOption { case ((broker, status), index) =>
          val targetPriority = if (targetCliqueId.contains(status.info.cliqueId)) 0 else 1
          (targetPriority, assignments.getOrElse(broker, 0), index)
        } match {
          case Some(((broker, _), _)) =>
            assignments.updateWith(broker)(_.map(_ + 1).orElse(Some(1)))
            selected.addOne((hash, broker, ChainIndex.from(hash).relateTo(brokerConfig)))
          case None => unavailable = Some(hash)
        }
      }
    }

    unavailable match {
      case Some(hash) =>
        deferActiveSyncTarget(s"dependency ${hash.shortHex} is unavailable after bounded retries")
      case None =>
        val requests = mutable.HashMap.empty[(BrokerActor, Boolean), mutable.ArrayBuffer[BlockHash]]
        selected.foreach { case (hash, broker, isBlock) =>
          dependencyFetchPeers.getOrElseUpdate(hash, mutable.HashSet.empty).addOne(broker)
          requestedDependencyHashes.addOne(hash)
          SyncState.addToMap(requests, broker -> isBlock, hash)
        }
        requests.foreachEntry {
          case ((broker, true), requested) =>
            broker ! BrokerHandler.DownloadBlocks(AVector.from(requested))
          case ((broker, false), requested) =>
            broker ! BrokerHandler.DownloadHeaders(AVector.from(requested))
        }
    }
  }

  private[sync] def handleDependencyPendings(pendingHashes: AVector[BlockHash]): Unit = {
    if (dependencyAuditInFlight) {
      dependencyAuditInFlight = false
      val pending = pendingHashes.toSet
      val missing = AVector.from(auditedValidatingHashes.filterNot(pending.contains))
      auditedValidatingHashes.clear()
      escapeIOError(missing.partitionE(blockflow.contains)) { case (stored, dropped) =>
        stored.foreach(hash => onBlockProcessed(ChainIndex.from(hash), hash))
        scheduleDependencyRecovery(
          dropped,
          s"${dropped.length} stalled validating blocks disappeared from the dependency cache"
        )
      }
    }
  }

  private[sync] def recoverFromDependencyEviction(): Unit = {
    dependencyEvictionRecoveryTask.foreach(_.cancel())
    dependencyEvictionRecoveryTask = None
    if (isSyncingUsingV2 && hasEvictedValidatingBlocks) {
      log.debug(
        s"Switch Sync V2 target after ${evictedPendingHashes.size} pending flow data entries were evicted"
      )
      deferActiveSyncTarget(
        s"${evictedPendingHashes.size} active pending flow data entries were evicted"
      )
    } else {
      evictedPendingHashes.clear()
    }
  }

  private def rememberInvalidSyncTarget(targetId: RemoteSyncTargetId): Unit = {
    if (invalidSyncTargets.size >= MaxQuarantinedSyncTargets) {
      invalidSyncTargets.headOption.foreach(invalidSyncTargets.subtractOne)
    }
    invalidSyncTargets.addOne(targetId)
    ()
  }

  private def rememberDeferredSyncTarget(
      targetId: RemoteSyncTargetId,
      expiresAt: TimeStamp
  ): Unit = {
    if (
      deferredSyncTargets.size >= MaxQuarantinedSyncTargets &&
      !deferredSyncTargets.contains(targetId)
    ) {
      deferredSyncTargets.headOption.foreach { case (oldest, _) =>
        deferredSyncTargets.remove(oldest)
      }
    }
    deferredSyncTargets.put(targetId, expiresAt)
    ()
  }

  private[sync] def invalidateActiveSyncTarget(reason: String): Unit = {
    activeSyncTarget.foreach { target =>
      log.debug(s"Reject Sync V2 target ${target.id}: $reason")
      rememberInvalidSyncTarget(target.id)
    }
  }

  private[sync] def markActiveSyncTargetDeferred(reason: String): Unit = {
    activeSyncTarget.foreach { target =>
      val expiresAt = TimeStamp.now().plusUnsafe(networkSetting.dependencyExpiryPeriod)
      log.debug(s"Defer Sync V2 target ${target.id} until $expiresAt: $reason")
      rememberDeferredSyncTarget(target.id, expiresAt)
    }
  }

  private[sync] def deferActiveSyncTarget(reason: String): Unit = {
    markActiveSyncTargetDeferred(reason)
    resync()
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
    selectSyncTarget().foreach { selected =>
      val chains = AVector.from(selected.chains.iterator.flatMap {
        case (chainIndex, broker, remoteTip) =>
          selfChainTips(chainIndex).map(selfTip => (chainIndex, broker, remoteTip, selfTip))
      })
      if (chains.nonEmpty) startSync(selected.target, chains)
    }
  }

  // The sync process consists of three steps:
  // 1. Find the common ancestor height `h` between the local node and the origin peer
  // 2. Start constructing the header chain skeletons from `h + 1` using the origin peer
  // 3. Download blocks from all nodes to fill in the header chain skeletons. If no one can
  //    fill in the skeleton it's assumed invalid and the origin peer is dropped
  def startSync(
      target: RemoteSyncTarget,
      chains: AVector[(ChainIndex, BrokerActor, ChainTip, ChainTip)]
  ): Unit = {
    assume(!isSyncingUsingV2)
    isSyncingUsingV2 = true
    activeSyncTarget = Some(target)
    log.debug(s"Start syncing from clique ${target.cliqueId}")

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

  @inline private[sync] def calcFromHeight(ancestorHeight: Int): Int = {
    Math.max(ALPH.GenesisHeight, ancestorHeight - ALPH.MaxGhostUncleAge) + 1
  }

  def handleAncestors(ancestors: AVector[(ChainIndex, Int)]): Unit = {
    val brokerActor: BrokerActor = ActorRefT(sender())
    val requests                 = mutable.ArrayBuffer.empty[(ChainIndex, BlockHeightRange)]
    ancestors.foreach { case (chainIndex, ancestorHeight) =>
      syncingChains(chainIndex).foreach { state =>
        val fromHeight = calcFromHeight(ancestorHeight)
        state.initSkeletonHeights(brokerActor, fromHeight) match {
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

  private[sync] var continueDownloadTask: Option[Cancellable] = None

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
      continueDownloadTask.foreach(_.cancel())
      continueDownloadTask = if (allTasks.isEmpty) {
        Some(
          scheduleCancellableOnce(
            self,
            BlockFlowSynchronizer.ContinueDownload,
            continueDownloadDelay()
          )
        )
      } else {
        None
      }
    }
  }

  private[sync] def continueDownloadDelay(): Duration = {
    val fallback = getRateLimiterWindowSize.divUnsafe(2)
    syncingChains.array.iterator
      .collect { case Some(chain) if !chain.isTaskQueueEmpty => chain }
      .flatMap { chain =>
        chain.nextTaskOption.iterator.flatMap { task =>
          val statuses = if (task.toHeader.isDefined) {
            brokers.iterator.map(_._2)
          } else {
            getBrokerStatus(chain.originBroker).iterator
          }
          statuses.flatMap(_.timeUntilDownloadAvailable(task))
        }
      }
      .minOption
      .getOrElse(fallback)
  }

  private def collectAndAssignTasks(
      chains: AVector[SyncStatePerChain]
  ): mutable.HashMap[BrokerActor, mutable.ArrayBuffer[BlockDownloadTask]] = {
    val orderedChains = chains.sortBy(_.taskSize)(Ordering[Int].reverse)
    val selector      = SyncState.CircularSelector(brokers)
    val acc           = mutable.HashMap.empty[BrokerActor, mutable.ArrayBuffer[BlockDownloadTask]]

    @scala.annotation.tailrec
    def iter(): mutable.HashMap[BrokerActor, mutable.ArrayBuffer[BlockDownloadTask]] = {
      val continue = collectAndAssignTasks(orderedChains, selector, acc)
      if (continue) iter() else acc
    }

    iter()
  }

  private def collectAndAssignTasks(
      orderedChains: AVector[SyncStatePerChain],
      selector: CircularSelector[(BrokerActor, BrokerStatus)],
      acc: mutable.HashMap[BrokerActor, mutable.ArrayBuffer[BlockDownloadTask]]
  ) = {
    var size = 0
    orderedChains.foreach { state =>
      state.nextTask { task =>
        val selectedBroker = if (task.toHeader.isDefined) {
          selector.next(_._2.canDownload(task))
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
    continueDownloadTask.foreach(_.cancel())
    continueDownloadTask = None
    dependencyEvictionRecoveryTask.foreach(_.cancel())
    dependencyEvictionRecoveryTask = None
    evictedPendingHashes.clear()
    auditedValidatingHashes.clear()
    dependencyAuditInFlight = false
    nextDependencyAuditAt = TimeStamp.zero
    missingDependencyQueryInFlight = false
    nextMissingDependencyQueryAt = TimeStamp.zero
    requestedDependencyHashes.clear()
    dependencyFetchPeers.clear()
    syncingChains.reset()
    activeSyncTarget = None
    isSyncingUsingV2 = false
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
    selectSyncTarget().exists { selected =>
      selected.chains.exists { case (chainIndex, _, latestBestTip) =>
        syncingChains(chainIndex) match {
          case Some(state) => compareChainTips(latestBestTip, state.bestTip) > 0
          case None => selfChainTips(chainIndex).exists(compareChainTips(latestBestTip, _) > 0)
        }
      }
    }
  }

  @inline private def tryStartNextSyncRound(): Unit = {
    if (needToStartNextSyncRound()) {
      resync()
    } else {
      // No peer's best tip is better than the node's own best tip, which means
      // the node is synced. Clear the sync state to reduce memory footprint.
      clearSyncingState()
    }
  }

  def onBrokerTerminated(broker: BrokerActor): Unit = {
    val status = getBrokerStatus(broker)
    removeBroker(broker)
    recomputeBestChainTips()
    status.foreach(onBrokerTerminated(broker, _))
  }

  private def onBrokerTerminated(broker: BrokerActor, status: BrokerStatus): Unit = {
    if (isSyncingUsingV2) {
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
// scalastyle:on number.of.methods

object SyncState {
  import BrokerStatusTracker.BrokerActor

  val SkeletonSize: Int                   = 16
  val BatchSize: Int                      = 128
  val MaxQueueSize: Int                   = SkeletonSize * BatchSize
  val MaxValidationBlocksWhenSynced: Int  = 5
  val MaxMissingDependenciesPerQuery: Int = MaxRequestNum
  val MaxQuarantinedSyncTargets: Int      = 1024

  @inline def compareChainTips(tip0: ChainTip, tip1: ChainTip): Int = {
    BlockHashPool.compareWeight(tip0.hash, tip0.weight, tip1.hash, tip1.weight)
  }

  final case class RemoteSyncTarget(
      cliqueId: CliqueId,
      chains: AVector[(ChainIndex, BrokerActor, ChainTip)]
  ) {
    lazy val id: RemoteSyncTargetId = {
      RemoteSyncTargetId(
        cliqueId,
        chains.map { case (chainIndex, _, tip) => chainIndex -> tip.hash }
      )
    }
  }

  final case class RemoteSyncTargetId(
      cliqueId: CliqueId,
      tips: AVector[(ChainIndex, BlockHash)]
  )

  final case class SelectedSyncTarget(
      target: RemoteSyncTarget,
      chains: AVector[(ChainIndex, BrokerActor, ChainTip)],
      workAdvantage: BigInteger
  )

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
      toHeader: Option[BlockHeader],
      toHash: Option[BlockHash]
  ) {
    def heightRange: BlockHeightRange     = BlockHeightRange.from(fromHeight, toHeight, 1)
    def size: Int                         = toHeight - fromHeight + 1
    def id: BlockBatch                    = BlockBatch(fromHeight, toHeight)
    def expectedToHash: Option[BlockHash] = toHash.orElse(toHeader.map(_.hash))

    override def toString: String = s"${chainIndex.from.value}->${chainIndex.to.value}:$id"
  }

  object BlockDownloadTask {
    def apply(
        chainIndex: ChainIndex,
        fromHeight: Int,
        toHeight: Int,
        toHeader: Option[BlockHeader]
    ): BlockDownloadTask = {
      new BlockDownloadTask(chainIndex, fromHeight, toHeight, toHeader, None)
    }
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
    private[sync] var lastValidationProgressAt = TimeStamp.now()

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
        val task = BlockDownloadTask(chainIndex, from, bestTip.height, None, Some(bestTip.hash))
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

    def tryValidateMoreBlocks(
        acc: mutable.ArrayBuffer[DownloadedBlock],
        isNearSynced: Boolean
    ): Unit = {
      val size = if (isNearSynced) MaxValidationBlocksWhenSynced else maxSyncBlocksPerChain
      if (validating.size < size && pendingQueue.nonEmpty) {
        val selected = pendingQueue.view.take(size).map(_._2).toSeq
        logger.debug(
          s"Sending more blocks for validation: ${selected.size}, chain index: $chainIndex"
        )
        val hashes = selected.map(_.block.hash)
        validating.addAll(hashes)
        lastValidationProgressAt = TimeStamp.now()
        pendingQueue.subtractAll(hashes)
        acc.addAll(selected)
      }
    }

    def isSkeletonFilled: Boolean = batchIds.forall(downloadedBlocks.contains)

    def handleFinalizedBlock(hash: BlockHash): Unit = {
      if (validating.remove(hash)) {
        lastValidationProgressAt = TimeStamp.now()
      }
      ()
    }

    def isValidationStalled(now: TimeStamp, timeout: Duration): Boolean = {
      validating.nonEmpty && lastValidationProgressAt.plusUnsafe(timeout) <= now
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

    def nextTaskOption: Option[BlockDownloadTask] = taskQueue.headOption
    def taskSize: Int                             = taskQueue.length
    def isTaskQueueEmpty: Boolean                 = taskQueue.isEmpty

    def isSynced(selfTip: ChainTip): Boolean = {
      compareChainTips(selfTip, bestTip) >= 0 || (
        // When syncing different chains from different nodes, it is possible that a block has already
        // been downloaded and sent to the `DependencyHandler`, but due to dependencies not being ready,
        // it cannot be added to the blockchain. This means that the self tip has not reached the best
        // tip yet.
        // In this case, wait until every downloaded block has also completed dependency validation
        // before considering the chain synced and starting the next round.
        nextFromHeight > bestTip.height &&
          skeletonHeightRange.isEmpty &&
          batchIds.isEmpty &&
          pendingQueue.isEmpty &&
          validating.isEmpty
      )
    }

    def isOriginPeer(broker: BrokerActor): Boolean = originBroker == broker
  }

  object SyncStatePerChain {
    def apply(chainIndex: ChainIndex, bestTip: ChainTip, broker: BrokerActor): SyncStatePerChain =
      new SyncStatePerChain(broker, chainIndex, bestTip)
  }

  final class CircularSelector[T](val elements: scala.collection.Seq[T], index: Int) {
    private var currentIndex: Int = index
    def next(cond: T => Boolean): Option[T] = {
      val result = find(cond)
      currentIndex += 1
      if (currentIndex == elements.length) currentIndex = 0
      result
    }

    private def find(cond: T => Boolean): Option[T] = {
      val result = elements.view.slice(currentIndex, elements.length).find(cond)
      if (result.isDefined) {
        result
      } else {
        elements.view.slice(0, currentIndex).find(cond)
      }
    }
  }

  object CircularSelector {
    def apply[T](elements: scala.collection.Seq[T]): CircularSelector[T] =
      new CircularSelector(elements, Random.nextInt(elements.length))
  }
}
