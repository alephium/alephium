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

import org.alephium.flow.core.{BlockFlow, BlockHashChain}
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.setting.NetworkSetting
import org.alephium.flow.validation._
import org.alephium.io.IOResult
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.message.{Message, SendBlocks, SendHeaders}
import org.alephium.protocol.model.{Block, ChainIndex, TransactionTemplate}
import org.alephium.util.{ActorRefT, AVector, EventBus, EventStream}

object BlockChainHandler {
  def props(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      txHandler: ActorRefT[TxHandler.Command],
      eventBus: ActorRefT[EventBus.Message]
  )(implicit
      brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig,
      networkSetting: NetworkSetting
  ): Props =
    Props(new BlockChainHandler(blockFlow, chainIndex, txHandler, eventBus))

  sealed trait Command
  final case class Validate(block: Block, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin)
      extends Command

  sealed trait Event                             extends ChainHandler.Event
  final case class BlockAdded(hash: BlockHash)   extends Event
  case object BlockAddingFailed                  extends Event
  final case class InvalidBlock(hash: BlockHash) extends Event
}

class BlockChainHandler(
    blockFlow: BlockFlow,
    chainIndex: ChainIndex,
    txHandler: ActorRefT[TxHandler.Command],
    eventBus: ActorRefT[EventBus.Message]
)(implicit
    brokerConfig: BrokerConfig,
    val consensusConfig: ConsensusConfig,
    networkSetting: NetworkSetting
) extends ChainHandler[Block, InvalidBlockStatus, BlockChainHandler.Command](
      blockFlow,
      txHandler,
      chainIndex,
      BlockValidation.build
    )
    with EventStream.Publisher {
  import BlockChainHandler._

  val headerChain: BlockHashChain = blockFlow.getHashChain(chainIndex)

  override def receive: Receive = { case Validate(block, broker, origin) =>
    handleData(block, broker, origin)
  }

  override def broadcast(block: Block, origin: DataOrigin): Unit = {
    val blockMessage =
      Message.serialize(SendBlocks(AVector(block)), networkSetting.networkType)
    val headerMessage =
      Message.serialize(SendHeaders(AVector(block.header)), networkSetting.networkType)
    if (brokerConfig.contains(block.chainIndex.from)) {
      val isRecent = blockFlow.isRecent(block)
      val event    = CliqueManager.BroadCastBlock(block, blockMessage, headerMessage, origin, isRecent)
      publishEvent(event)
    }
  }

  override def dataAddingFailed(): Event = BlockAddingFailed

  override def dataInvalid(data: Block): Event = InvalidBlock(data.hash)

  override def addDataToBlockFlow(block: Block): IOResult[AVector[TransactionTemplate]] = {
    blockFlow.addNew(block)
  }

  override def notifyBroker(broker: ActorRefT[ChainHandler.Event], block: Block): Unit = {
    broker ! BlockChainHandler.BlockAdded(block.hash)
    escapeIOError(blockFlow.getHeight(block)) { height =>
      eventBus ! BlockNotify(block.header, height)
    }
  }

  override def show(block: Block): String = {
    showHeader(block.header) + s" #tx: ${block.transactions.length}"
  }
}
