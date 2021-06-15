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

import akka.actor.{ActorRef, Props}

import org.alephium.flow.client.Miner
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Address, ChainIndex, TransactionTemplate}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util._
import org.alephium.util.EventStream.{Publisher, Subscriber}

object ViewHandler {
  def props(
      blockFlow: BlockFlow,
      txHandler: ActorRefT[TxHandler.Command],
      addressOpt: Option[AVector[Address]]
  )(implicit
      brokerConfig: BrokerConfig
  ): Props = Props(
    new ViewHandler(blockFlow, txHandler, addressOpt.map(_.map(_.lockupScript)))
  )

  sealed trait Command
  case object Subscribe                                         extends Command
  case object Unsubscribe                                       extends Command
  case object GetAddresses                                      extends Command
  final case class UpdateAddresses(addresses: AVector[Address]) extends Command

  sealed trait Event
  final case class ViewUpdated(
      templates: IndexedSeq[IndexedSeq[BlockFlowTemplate]]
  ) extends Event
      with EventStream.Event

  def needUpdate(chainIndex: ChainIndex)(implicit brokerConfig: BrokerConfig): Boolean = {
    brokerConfig.contains(chainIndex.from) || chainIndex.isIntraGroup
  }

  def prepareTemplates(
      blockFlow: BlockFlow,
      minerAddresses: AVector[LockupScript]
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
    txHandler: ActorRefT[TxHandler.Command],
    var minerAddressesOpt: Option[AVector[LockupScript]]
)(implicit
    val brokerConfig: BrokerConfig
) extends ViewHandlerState
    with Subscriber
    with Publisher {
  var lastUpdated: TimeStamp = TimeStamp.zero

  subscribeEvent(self, classOf[ChainHandler.FlowDataAdded])

  override def receive: Receive = {
    case ChainHandler.FlowDataAdded(data, _, addedAt) =>
      // We only update best deps for the following 2 cases:
      //  1. the block belongs to the groups of the node
      //  2. the header belongs to intra-group chain
      val chainIndex = data.chainIndex
      if (addedAt >= lastUpdated && ViewHandler.needUpdate(chainIndex)) {
        lastUpdated = TimeStamp.now()
        escapeIOError(blockFlow.updateBestDeps()) { newReadyTxs =>
          broadcastReadyTxs(newReadyTxs)
        }
      }
      updateSubscribers()

    case ViewHandler.Subscribe   => subscribe()
    case ViewHandler.Unsubscribe => unsubscribe()

    case ViewHandler.GetAddresses => sender() ! minerAddressesOpt
    case ViewHandler.UpdateAddresses(addresses) =>
      Miner.validateAddresses(addresses) match {
        case Right(_)    => minerAddressesOpt = Some(addresses.map(_.lockupScript))
        case Left(error) => log.error(s"Updating invalid miner addresses: $error")
      }
  }

  def broadcastReadyTxs(txs: AVector[TransactionTemplate]): Unit = {
    if (txs.nonEmpty) {
      // delay this broadcast so that peers have download this block
      scheduleOnce(
        txHandler.ref,
        TxHandler.Broadcast(txs),
        Duration.ofSecondsUnsafe(2)
      )
    }
  }
}

trait ViewHandlerState extends IOBaseActor {
  implicit def brokerConfig: BrokerConfig

  def blockFlow: BlockFlow
  def minerAddressesOpt: Option[AVector[LockupScript]]

  val subscribers: ArrayBuffer[ActorRef] = ArrayBuffer.empty

  def subscribe(): Unit = {
    if (!subscribers.contains(sender())) {
      subscribers.addOne(sender())
      minerAddressesOpt.foreach { minerAddresses =>
        escapeIOError(ViewHandler.prepareTemplates(blockFlow, minerAddresses)) { templates =>
          sender() ! ViewHandler.ViewUpdated(templates)
        }
      }
    }
  }

  def unsubscribe(): Unit = {
    subscribers.filterInPlace(_ != sender())
  }

  def updateSubscribers(): Unit = {
    minerAddressesOpt.foreach { minerAddresses =>
      if (subscribers.nonEmpty) {
        escapeIOError(ViewHandler.prepareTemplates(blockFlow, minerAddresses)) { templates =>
          subscribers.foreach(_ ! ViewHandler.ViewUpdated(templates))
        }
      }
    }
  }
}
