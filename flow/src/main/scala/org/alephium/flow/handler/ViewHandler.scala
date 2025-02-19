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
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.{ActorRef, Cancellable, Props}
import akka.pattern.pipe

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.mining.Miner
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.setting.MiningSetting
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Address, ChainIndex, HardFork}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util._
import org.alephium.util.EventStream.{Publisher, Subscriber}

object ViewHandler {
  def props(
      blockFlow: BlockFlow
  )(implicit
      brokerConfig: BrokerConfig,
      miningSetting: MiningSetting
  ): Props = Props(
    new ViewHandler(blockFlow, miningSetting.minerAddresses.map(_.map(_.lockupScript)))
  )

  sealed trait Command
  case object BestDepsUpdated                                              extends Command
  case object BestDepsUpdateFailed                                         extends Command
  case object Subscribe                                                    extends Command
  case object Unsubscribe                                                  extends Command
  case object UpdateSubscribers                                            extends Command
  case object GetMinerAddresses                                            extends Command
  final case class UpdateMinerAddresses(addresses: AVector[Address.Asset]) extends Command

  sealed trait Event
  final case class NewTemplates(
      templates: IndexedSeq[IndexedSeq[BlockFlowTemplate]]
  ) extends Event
      with EventStream.Event
  final case class NewTemplate(template: BlockFlowTemplate) extends Event with EventStream.Event
  final case class SubscribeResult(succeeded: Boolean)      extends Event

  def needUpdate(chainIndex: ChainIndex)(implicit brokerConfig: BrokerConfig): Boolean = {
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

  def prepareTemplate(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      minerAddresses: AVector[LockupScript.Asset]
  ): IOResult[BlockFlowTemplate] = {
    IOUtils.tryExecute {
      blockFlow.prepareBlockFlowUnsafe(chainIndex, minerAddresses(chainIndex.to.value))
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
    with BlockFlowUpdaterState
    with Subscriber
    with Publisher
    with InterCliqueManager.NodeSyncStatus {
  subscribeEvent(self, classOf[ChainHandler.FlowDataAdded])

  override def receive: Receive = handle orElse updateNodeSyncStatus

  def handle: Receive = {
    case ChainHandler.FlowDataAdded(data, _, _) =>
      val hardFork = getHardForkNow()
      // We only update best deps for the following 2 cases:
      //  1. the block belongs to the groups of the node
      //  2. the header belongs to intra-group chain
      if (isNodeSynced && ViewHandler.needUpdate(data.chainIndex)) {
        updatingBestViewCount += 1
        tryUpdateBestView(hardFork)
      }
      if (hardFork.isDanubeEnabled()) {
        updateSubscribers(hardFork, Some(data.chainIndex))
      }
    case ViewHandler.BestDepsUpdated =>
      updatingBestDeps = false
      val hardFork = getHardForkNow()
      // If Danube is not enabled, we update the subscribers
      // If Danube is enabled, we don't need to update the subscribers, as it's done when the data event is received
      if (!hardFork.isDanubeEnabled()) { updateSubscribers(hardFork, None) }
      if (isNodeSynced) {
        tryUpdateBestView(hardFork)
      }
    case ViewHandler.BestDepsUpdateFailed =>
      updatingBestDeps = false
      log.warning("Updating blockflow deps failed")

    case ViewHandler.Subscribe   => subscribe()
    case ViewHandler.Unsubscribe => unsubscribe()
    case ViewHandler.UpdateSubscribers =>
      updateSubscribers(getHardForkNow(), None)

    case ViewHandler.GetMinerAddresses => sender() ! minerAddressesOpt
    case ViewHandler.UpdateMinerAddresses(addresses) =>
      Miner.validateAddresses(addresses) match {
        case Right(_)    => minerAddressesOpt = Some(addresses.map(_.lockupScript))
        case Left(error) => log.error(s"Updating invalid miner addresses: $error")
      }
  }
}

trait ViewHandlerState extends IOBaseActor {
  implicit def brokerConfig: BrokerConfig
  implicit def miningSetting: MiningSetting

  def blockFlow: BlockFlow
  def minerAddressesOpt: Option[AVector[LockupScript.Asset]]
  def isNodeSynced: Boolean

  var updateScheduled: Option[Cancellable] = None
  val subscribers: ArrayBuffer[ActorRef]   = ArrayBuffer.empty

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
          updateSubscribers(getHardForkNow(), None)
          scheduleUpdate()
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

  def scheduleUpdate(): Unit = {
    updateScheduled.foreach(_.cancel())
    updateScheduled = Some(
      scheduleCancellableOnce(
        self,
        ViewHandler.UpdateSubscribers,
        miningSetting.pollingInterval
      )
    )
  }

  def unsubscribe(): Unit = {
    subscribers.filterInPlace(_ != sender())
    if (subscribers.isEmpty) {
      updateScheduled.foreach(_.cancel())
      updateScheduled = None
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def updateSubscribers(hardFork: HardFork, chainIndexOpt: Option[ChainIndex]): Unit = {
    if (isNodeSynced) {
      if (minerAddressesOpt.nonEmpty && subscribers.nonEmpty) {
        val minerAddresses = minerAddressesOpt.get
        if (hardFork.isDanubeEnabled() && chainIndexOpt.nonEmpty) {
          val chainIndex = chainIndexOpt.get
          if (brokerConfig.contains(chainIndex.from)) {
            escapeIOError(
              ViewHandler.prepareTemplate(blockFlow, chainIndex, minerAddresses)
            ) { template =>
              subscribers.foreach(_ ! ViewHandler.NewTemplate(template))
            }
          }
        } else {
          escapeIOError(ViewHandler.prepareTemplates(blockFlow, minerAddresses)) { templates =>
            subscribers.foreach(_ ! ViewHandler.NewTemplates(templates))
          }
        }
        scheduleUpdate()
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

trait BlockFlowUpdaterState extends IOBaseActor {
  def blockFlow: BlockFlow
  protected[handler] var updatingBestViewCount: Int = 0
  protected[handler] var updatingBestDeps: Boolean  = false

  implicit def executionContext: ExecutionContext = context.dispatcher

  def tryUpdateBestViewPreDanube(): Unit = {
    if (updatingBestViewCount > 0 && !updatingBestDeps) {
      updatingBestViewCount = 0
      updatingBestDeps = true
      Future[ViewHandler.Command] {
        val now          = TimeStamp.now()
        val hardForkSoon = blockFlow.networkConfig.getHardFork(now.plusSecondsUnsafe(10))
        val updateResult = if (hardForkSoon.isDanubeEnabled()) {
          // If Danube will be enabled within the next 10 seconds
          for {
            _ <- blockFlow.updateBestFlowSkeleton()
            _ <- blockFlow.updateBestDeps()
          } yield ()
        } else {
          // If Danube is not enabled and won't be soon
          blockFlow.updateBestDeps()
        }
        updateResult match {
          case Left(_)  => ViewHandler.BestDepsUpdateFailed
          case Right(_) => ViewHandler.BestDepsUpdated
        }
      }.pipeTo(self)
      ()
    }
    if (updatingBestDeps) {
      log.debug("Skip updating best deps due to pending updates")
    }
  }

  def tryUpdateBestViewDanube(): Unit = {
    blockFlow.updateBestFlowSkeleton() match {
      case Left(error) => log.error(s"Failed to update best flow skeleton: $error")
      case Right(_)    => ()
    }
  }

  def tryUpdateBestView(hardForkNow: HardFork): Unit = {
    if (hardForkNow.isDanubeEnabled()) {
      tryUpdateBestViewDanube()
    } else {
      tryUpdateBestViewPreDanube()
    }
  }
}
