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

import akka.actor.Props

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.validation._
import org.alephium.io.IOResult
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.model.{BlockHeader, ChainIndex}
import org.alephium.util.ActorRefT

object HeaderChainHandler {
  def props(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex
  )(implicit brokerConfig: BrokerConfig, consensusConfig: ConsensusConfig): Props =
    Props(new HeaderChainHandler(blockFlow, chainIndex))

  sealed trait Command
  final case class Validate(
      header: BlockHeader,
      broker: ActorRefT[ChainHandler.Event],
      origin: DataOrigin
  ) extends Command

  sealed trait Event                              extends ChainHandler.Event
  final case class HeaderAdded(hash: BlockHash)   extends Event
  case object HeaderAddingFailed                  extends Event
  final case class InvalidHeader(hash: BlockHash) extends Event
}

class HeaderChainHandler(
    blockFlow: BlockFlow,
    chainIndex: ChainIndex
)(implicit brokerConfig: BrokerConfig, val consensusConfig: ConsensusConfig)
    extends ChainHandler[BlockHeader, InvalidHeaderStatus, HeaderChainHandler.Command](
      blockFlow,
      chainIndex,
      HeaderValidation.build
    ) {
  import HeaderChainHandler._

  override def receive: Receive = { case Validate(header, broker, origin) =>
    handleData(header, broker, origin)
  }

  override def broadcast(header: BlockHeader, origin: DataOrigin): Unit = ()

  override def dataAddingFailed(): Event = HeaderAddingFailed

  override def dataInvalid(data: BlockHeader): Event = InvalidHeader(data.hash)

  override def addDataToBlockFlow(header: BlockHeader): IOResult[Unit] = {
    blockFlow.add(header)
  }

  override def notifyBroker(broker: ActorRefT[ChainHandler.Event], data: BlockHeader): Unit = {
    broker ! HeaderChainHandler.HeaderAdded(data.hash)
  }

  override def show(header: BlockHeader): String = showHeader(header)
}
