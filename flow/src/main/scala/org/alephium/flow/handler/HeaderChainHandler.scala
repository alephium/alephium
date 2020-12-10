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

import scala.collection.mutable

import akka.actor.Props

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.validation._
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.model.{BlockHeader, ChainIndex}
import org.alephium.util.{ActorRefT, Forest}

object HeaderChainHandler {
  def props(blockFlow: BlockFlow,
            chainIndex: ChainIndex,
            flowHandler: ActorRefT[FlowHandler.Command])(implicit brokerConfig: BrokerConfig,
                                                         consensusConfig: ConsensusConfig): Props =
    Props(new HeaderChainHandler(blockFlow, chainIndex, flowHandler))

  def addOneHeader(header: BlockHeader, origin: DataOrigin): AddHeaders = {
    val forest = Forest.build[BlockHash, BlockHeader](header, _.hash)
    AddHeaders(forest, origin)
  }

  sealed trait Command
  final case class AddHeaders(header: Forest[BlockHash, BlockHeader], origin: DataOrigin)
      extends Command
  final case class AddPendingHeader(header: BlockHeader,
                                    broker: ActorRefT[ChainHandler.Event],
                                    origin: DataOrigin)
      extends Command

  sealed trait Event                              extends ChainHandler.Event
  final case class HeaderAdded(hash: BlockHash)   extends Event
  case object HeaderAddingFailed                  extends Event
  final case class InvalidHeader(hash: BlockHash) extends Event
}

class HeaderChainHandler(blockFlow: BlockFlow,
                         chainIndex: ChainIndex,
                         flowHandler: ActorRefT[FlowHandler.Command])(
    implicit brokerConfig: BrokerConfig,
    consensusConfig: ConsensusConfig)
    extends ChainHandler[BlockHeader, InvalidHeaderStatus, HeaderChainHandler.Command](
      blockFlow,
      chainIndex,
      HeaderValidation.build) {
  import HeaderChainHandler._

  override def receive: Receive = {
    case AddHeaders(headers, origin) =>
      handleDatas(headers, ActorRefT[ChainHandler.Event](sender()), origin)
    case AddPendingHeader(header, broker, origin)        => handlePending(header, broker, origin)
    case FlowHandler.HeaderAdded(header, broker, origin) => handleDataAdded(header, broker, origin)
  }

  override def broadcast(header: BlockHeader, origin: DataOrigin): Unit = ()

  override def addToFlowHandler(header: BlockHeader,
                                broker: ActorRefT[ChainHandler.Event],
                                origin: DataOrigin): Unit = {
    flowHandler ! FlowHandler.AddHeader(header, broker, origin)
  }

  override def pendingToFlowHandler(header: BlockHeader,
                                    missings: mutable.HashSet[BlockHash],
                                    broker: ActorRefT[ChainHandler.Event],
                                    origin: DataOrigin,
                                    self: ActorRefT[Command]): Unit = {
    flowHandler ! FlowHandler.PendingHeader(header, missings, origin, broker, self)
  }

  override def dataAddedEvent(data: BlockHeader): Event = HeaderAdded(data.hash)

  override def dataAddingFailed(): Event = HeaderAddingFailed

  override def dataInvalid(data: BlockHeader): Event = InvalidHeader(data.hash)
}
