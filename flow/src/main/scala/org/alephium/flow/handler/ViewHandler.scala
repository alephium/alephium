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

package org.alephium.flow.handler

import scala.collection.mutable.ArrayBuffer

import akka.actor.{ActorRef, ActorSystem, Cancellable, Props}
import akka.pattern.pipe

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.mining.Miner
import org.alephium.flow.model.{AsyncUpdateState, BlockFlowTemplate}
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.setting.MiningSetting
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Address, BlockHash, ChainIndex, FlowData, HardFork}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util._
import org.alephium.util.EventStream.Publisher

object ViewHandler {
  val skipBuildTemplateThreshold: Duration = Duration.ofSecondsUnsafe(1)

  def build(system: ActorSystem, blockFlow: BlockFlow, namePostfix: String)(implicit
      brokerConfig: BrokerConfig,
      miningSetting: MiningSetting
  ): ActorRefT[Command] = {
    val props = Props(
      new ViewHandler(blockFlow, miningSetting.minerAddresses.map(_.map(_.lockupScript)))
    )
    val actor = ActorRefT.build[Command](system, props, s"ViewHandler$namePostfix")
    system.eventStream.subscribe(actor.ref, classOf[InterCliqueManager.SyncedResult])
    system.eventStream.subscribe(actor.ref, classOf[ChainHandler.FlowDataAdded])
    actor
  }

  sealed trait Command
  case object BestDepsUpdatedPreDanube      extends Command
  case object BestDepsUpdateFailedPreDanube extends Command
  final case class BestDepsUpdatedDanube(chainIndex: ChainIndex, rebuildTemplates: Boolean)
      extends Command
  final case class BestDepsUpdateFailedDanube(chainIndex: ChainIndex)      extends Command
  case object Subscribe                                                    extends Command
  case object Unsubscribe                                                  extends Command
  case object UpdateSubscribersPreDanube                                   extends Command
  final case class UpdateSubscribersDanube(chainIndex: ChainIndex)         extends Command
  case object GetMinerAddresses                                            extends Command
  final case class UpdateMinerAddresses(addresses: AVector[Address.Asset]) extends Command
  case object RebuildTemplatesComplete                                     extends Command

  sealed trait Event
  final case class NewTemplates(
      templates: IndexedSeq[IndexedSeq[BlockFlowTemplate]]
  ) extends Event
      with EventStream.Event
  final case class NewTemplate(template: BlockFlowTemplate, lazyBroadcast: Boolean)
      extends Event
      with EventStream.Event
  final case class SubscribeResult(succeeded: Boolean) extends Event

  final case class LastBlockPerChain(flowData: FlowData, height: Int, receivedAt: TimeStamp)

  def needUpdatePreDanube(chainIndex: ChainIndex)(implicit brokerConfig: BrokerConfig): Boolean = {
    brokerConfig.contains(chainIndex.from)
  }

  def needUpdateDanube(chainIndex: ChainIndex)(implicit brokerConfig: BrokerConfig): Boolean = {
    brokerConfig.contains(chainIndex.from) || chainIndex.isIntraGroup
  }

  def prepareTemplates(
      blockFlow: BlockFlow,
      minerAddresses: AVector[LockupScript.Asset]
  )(implicit brokerConfig: BrokerConfig): IOResult[IndexedSeq[IndexedSeq[BlockFlowTemplate]]] =
    IOUtils.tryExecute {
      brokerConfig.groupRange.map { fromGroup =>
        (0 until brokerConfig.groups).map { toGroup =>
          val chainIndex = ChainIndex.unsafe(fromGroup, toGroup)
          blockFlow.prepareBlockFlowUnsafe(chainIndex, minerAddresses(toGroup))
        }
      }
    }
}

class ViewHandler(
    val blockFlow: BlockFlow,
    var minerAddressesOpt: Option[AVector[LockupScript.Asset]]
)(implicit
    val brokerConfig: BrokerConfig,
    val miningSetting: MiningSetting
) extends ViewHandlerState
    with BlockFlowUpdaterPreDanubeState
    with BlockFlowUpdaterDanubeState
    with Publisher
    with InterCliqueManager.NodeSyncStatus {
  override def receive: Receive = handle orElse updateNodeSyncStatus

  // scalastyle:off cyclomatic.complexity method.length
  def handle: Receive = {
    case ChainHandler.FlowDataAdded(data, _, _) =>
      val hardFork = getHardForkNow()
      // We only update best deps for the following 2 cases:
      //  1. the block belongs to the groups of the node
      //  2. the header belongs to intra-group chain
      if (isNodeSynced) {
        if (hardFork.isDanubeEnabled() && ViewHandler.needUpdateDanube(data.chainIndex)) {
          requestDanubeUpdate(data)
          tryUpdateBestViewDanube(data.chainIndex)
        }
        if (!hardFork.isDanubeEnabled() && ViewHandler.needUpdatePreDanube(data.chainIndex)) {
          preDanubeUpdateState.requestUpdate()
          tryUpdateBestViewPreDanube()
        }
      }
    case ViewHandler.BestDepsUpdatedPreDanube =>
      preDanubeUpdateState.setCompleted()
      updateSubscribersPreDanube()
      if (isNodeSynced) {
        // Handle pending updates
        tryUpdateBestViewPreDanube()
      }
    case ViewHandler.BestDepsUpdateFailedPreDanube =>
      preDanubeUpdateState.setCompleted()
      log.warning("Updating pre-danube blockflow deps failed")

    case ViewHandler.BestDepsUpdatedDanube(chainIndex, rebuildTemplates) =>
      setDanubeUpdateCompleted(chainIndex)
      if (isNodeSynced) {
        scheduleUpdateDanube(chainIndex, rebuildTemplates)
        tryUpdateBestViewDanube(chainIndex)
      }
    case ViewHandler.BestDepsUpdateFailedDanube(chainIndex) =>
      setDanubeUpdateCompleted(chainIndex)
      log.warning("Updating danube blockflow deps failed")

    case ViewHandler.Subscribe                           => subscribe()
    case ViewHandler.Unsubscribe                         => unsubscribe()
    case ViewHandler.UpdateSubscribersPreDanube          => updateSubscribersPreDanube()
    case ViewHandler.UpdateSubscribersDanube(chainIndex) => updateSubscribersDanube(chainIndex)
    case ViewHandler.RebuildTemplatesComplete =>
      rebuildTemplatesState.setCompleted()
      rescheduleUpdateDanube()
      if (minerAddressesOpt.isDefined && subscribers.nonEmpty) tryRebuildTemplates()
    case ViewHandler.GetMinerAddresses => sender() ! minerAddressesOpt
    case ViewHandler.UpdateMinerAddresses(addresses) =>
      Miner.validateAddresses(addresses) match {
        case Right(_)    => minerAddressesOpt = Some(addresses.map(_.lockupScript))
        case Left(error) => log.error(s"Updating invalid miner addresses: $error")
      }
  }
  // scalastyle:on cyclomatic.complexity method.length
}

trait ViewHandlerState extends IOBaseActor {
  implicit def brokerConfig: BrokerConfig
  implicit def miningSetting: MiningSetting

  def blockFlow: BlockFlow
  def minerAddressesOpt: Option[AVector[LockupScript.Asset]]
  def isNodeSynced: Boolean

  var updateScheduledPreDanube: Option[Cancellable]     = None
  val updateScheduledDanube: Array[Option[Cancellable]] = Array.fill(brokerConfig.chainNum)(None)
  val subscribers: ArrayBuffer[ActorRef]                = ArrayBuffer.empty
  val rebuildTemplatesState                             = AsyncUpdateState()

  def subscribe(): Unit = {
    if (subscribers.contains(sender())) {
      log.info(s"The actor is already subscribed")
      sender() ! ViewHandler.SubscribeResult(true)
    } else if (!isNodeSynced) {
      failedInSubscribe(s"The node is not synced yet")
    } else {
      minerAddressesOpt match {
        case Some(_) =>
          subscribers.addOne(sender())
          updateSubscribersPreDanube()
          scheduleUpdatePreDanube()
          sender() ! ViewHandler.SubscribeResult(true)
        case None =>
          failedInSubscribe(s"Unable to subscribe the miner, as miner addresses are not set")
      }
    }
  }

  def failedInSubscribe(message: String): Unit = {
    log.warning(message)
    sender() ! ViewHandler.SubscribeResult(false)
  }

  def scheduleUpdatePreDanube(): Unit = {
    updateScheduledPreDanube.foreach(_.cancel())
    val hardFork = getHardForkNow()
    if (!hardFork.isDanubeEnabled()) {
      updateScheduledPreDanube = Some(
        scheduleCancellableOnce(
          self,
          ViewHandler.UpdateSubscribersPreDanube,
          miningSetting.pollingInterval
        )
      )
    }
  }

  protected def scheduleUpdateDanube(chainIndex: ChainIndex): Unit = {
    val index = chainIndex.flattenIndex
    updateScheduledDanube(index).foreach(_.cancel())
    updateScheduledDanube(index) = Some(
      scheduleCancellableOnce(
        self,
        ViewHandler.UpdateSubscribersDanube(chainIndex),
        miningSetting.pollingInterval
      )
    )
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  protected def tryRebuildTemplates(): Unit = {
    if (rebuildTemplatesState.isUpdating) {
      log.debug("Skip rebuilding templates due to pending updates")
    }
    if (rebuildTemplatesState.tryUpdate()) {
      assume(minerAddressesOpt.isDefined)
      val minerAddresses = minerAddressesOpt.get
      val subscribers    = AVector.from(this.subscribers)
      poolAsync {
        escapeIOError(ViewHandler.prepareTemplates(blockFlow, minerAddresses)) { templates =>
          subscribers.foreach(_ ! ViewHandler.NewTemplates(templates))
        }
        ViewHandler.RebuildTemplatesComplete
      }.pipeTo(self)
      ()
    }
  }

  protected def rescheduleUpdateDanube(): Unit = {
    brokerConfig.chainIndexes.foreach(scheduleUpdateDanube)
  }

  def scheduleUpdateDanube(chainIndex: ChainIndex, rebuildTemplates: Boolean): Unit = {
    if (
      brokerConfig.contains(chainIndex.from) &&
      minerAddressesOpt.nonEmpty &&
      subscribers.nonEmpty
    ) {
      if (rebuildTemplates) {
        rebuildTemplatesState.requestUpdate()
        tryRebuildTemplates()
      } else {
        scheduleUpdateDanube(chainIndex)
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def updateSubscribersDanube(chainIndex: ChainIndex): Unit = {
    if (minerAddressesOpt.nonEmpty && subscribers.nonEmpty) {
      val minerAddress = minerAddressesOpt.get(chainIndex.to.value)
      poolAsync {
        escapeIOError(blockFlow.prepareBlockFlow(chainIndex, minerAddress)) { template =>
          subscribers.foreach(_ ! ViewHandler.NewTemplate(template, lazyBroadcast = true))
        }
      }
      scheduleUpdateDanube(chainIndex)
    }
  }

  def unsubscribe(): Unit = {
    subscribers.filterInPlace(_ != sender())
    if (subscribers.isEmpty) {
      updateScheduledPreDanube.foreach(_.cancel())
      updateScheduledPreDanube = None
      updateScheduledDanube.foreach(_.foreach(_.cancel()))
      brokerConfig.chainIndexes.foreach { chainIndex =>
        updateScheduledDanube(chainIndex.flattenIndex) = None
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def updateSubscribersPreDanube(): Unit = {
    if (isNodeSynced) {
      if (minerAddressesOpt.nonEmpty && subscribers.nonEmpty) {
        val minerAddresses = minerAddressesOpt.get
        escapeIOError(ViewHandler.prepareTemplates(blockFlow, minerAddresses)) { templates =>
          subscribers.foreach(_ ! ViewHandler.NewTemplates(templates))
        }
        scheduleUpdatePreDanube()
      }
    } else if (subscribers.nonEmpty) {
      log.warning(s"The node is not synced, unsubscribe all actors")
      subscribers.foreach(_ ! ViewHandler.SubscribeResult(false))
    }
  }

  def getHardForkNow(): HardFork = {
    blockFlow.networkConfig.getHardFork(TimeStamp.now())
  }
}

trait BlockFlowUpdaterPreDanubeState extends IOBaseActor {
  def blockFlow: BlockFlow
  protected[handler] val preDanubeUpdateState: AsyncUpdateState = AsyncUpdateState()

  def tryUpdateBestViewPreDanube(): Unit = {
    if (preDanubeUpdateState.isUpdating) {
      log.debug("Skip updating pre-danube best deps due to pending updates")
    }
    if (preDanubeUpdateState.tryUpdate()) {
      poolAsync[ViewHandler.Command] {
        val now          = TimeStamp.now()
        val hardForkSoon = blockFlow.networkConfig.getHardFork(now.plusSecondsUnsafe(10))
        val updateResult = if (hardForkSoon.isDanubeEnabled()) {
          // If Danube will be enabled within the next 10 seconds
          for {
            _ <- blockFlow.updateBestFlowSkeleton()
            _ <- blockFlow.updateViewPreDanube()
          } yield ()
        } else {
          // If Danube is not enabled and won't be soon
          blockFlow.updateViewPreDanube()
        }
        updateResult match {
          case Left(_)  => ViewHandler.BestDepsUpdateFailedPreDanube
          case Right(_) => ViewHandler.BestDepsUpdatedPreDanube
        }
      }.pipeTo(self)
      ()
    }
  }
}

trait BlockFlowUpdaterDanubeState extends IOBaseActor {
  def blockFlow: BlockFlow
  implicit def brokerConfig: BrokerConfig
  def minerAddressesOpt: Option[AVector[LockupScript.Asset]]
  def subscribers: scala.collection.Seq[ActorRef]

  protected[handler] val danubeUpdateStates: Array[AsyncUpdateState] =
    Array.fill(brokerConfig.chainNum)(AsyncUpdateState())

  protected[handler] val lastBlocks: Array[Option[ViewHandler.LastBlockPerChain]] =
    Array.fill(brokerConfig.chainNum)(None)

  def requestDanubeUpdate(flowData: FlowData): Unit = {
    val index = flowData.chainIndex.flattenIndex
    val now   = TimeStamp.now()
    escapeIOError(blockFlow.getHeight(flowData.hash)) { height =>
      lastBlocks(index) match {
        case Some(last) =>
          if (!shouldSkipBuildTemplate(last, flowData.hash, height, now)) {
            requestDanubeUpdate(flowData, height, now)
          }
        case None => requestDanubeUpdate(flowData, height, now)
      }
    }
  }
  @inline private[handler] def shouldSkipBuildTemplate(
      last: ViewHandler.LastBlockPerChain,
      currentHash: BlockHash,
      currentHeight: Int,
      now: TimeStamp
  ): Boolean = {
    currentHeight == last.height &&
    last.receivedAt.isBefore(now) &&
    now.deltaUnsafe(last.receivedAt) < ViewHandler.skipBuildTemplateThreshold &&
    blockFlow.blockHashOrdering.lt(currentHash, last.flowData.hash)
  }

  @inline private def requestDanubeUpdate(flowData: FlowData, height: Int, ts: TimeStamp): Unit = {
    val index = flowData.chainIndex.flattenIndex
    lastBlocks(index) = Some(ViewHandler.LastBlockPerChain(flowData, height, ts))
    danubeUpdateStates(index).requestUpdate()
  }

  def setDanubeUpdateCompleted(chainIndex: ChainIndex): Unit = {
    danubeUpdateStates(chainIndex.flattenIndex).setCompleted()
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def tryUpdateBestViewDanube(chainIndex: ChainIndex): Unit = {
    val statePerChain = danubeUpdateStates(chainIndex.flattenIndex)
    if (statePerChain.isUpdating) {
      log.debug("Skip updating danube best deps due to pending updates")
    }
    if (statePerChain.tryUpdate()) {
      val minerAddress   = minerAddressesOpt.map(_.apply(chainIndex.to.value))
      val allSubscribers = AVector.from(subscribers)
      poolAsync[ViewHandler.Command] {
        val result = for {
          rebuildTemplates <- blockFlow.updateViewPerChainIndexDanube(chainIndex)
          needToUpdate = brokerConfig.contains(chainIndex.from)
          _ <-
            if (
              !rebuildTemplates && needToUpdate && minerAddress.nonEmpty && allSubscribers.nonEmpty
            ) {
              updateBlockTemplate(chainIndex, minerAddress.get, allSubscribers)
            } else {
              Right(())
            }
        } yield rebuildTemplates
        result match {
          case Right(rebuildTemplates) =>
            ViewHandler.BestDepsUpdatedDanube(chainIndex, rebuildTemplates)
          case Left(error) =>
            log.error(s"Failed to update best view: $error")
            ViewHandler.BestDepsUpdateFailedDanube(chainIndex)
        }
      }.pipeTo(self)
      ()
    }
  }

  private def updateBlockTemplate(
      chainIndex: ChainIndex,
      minerAddress: LockupScript.Asset,
      subscribers: AVector[ActorRef]
  ): IOResult[Unit] = {
    assume(minerAddress.groupIndex == chainIndex.to)
    blockFlow.prepareBlockFlow(chainIndex, minerAddress).map { template =>
      subscribers.foreach(_ ! ViewHandler.NewTemplate(template, lazyBroadcast = false))
    }
  }
}
