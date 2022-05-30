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
import akka.util.ByteString
import io.prometheus.client.{Counter, Gauge, Histogram}

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{InterCliqueManager, IntraCliqueManager}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.flow.validation._
import org.alephium.io.IOResult
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.message.{Message, NewBlock, NewHeader}
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.protocol.vm.{LogConfig, WorldState}
import org.alephium.util.{ActorRefT, EventBus, EventStream}

object BlockChainHandler {
  def props(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      eventBus: ActorRefT[EventBus.Message]
  )(implicit
      brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig,
      networkSetting: NetworkSetting,
      logConfig: LogConfig
  ): Props =
    Props(new BlockChainHandler(blockFlow, chainIndex, eventBus))

  sealed trait Command
  final case class Validate(block: Block, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin)
      extends Command

  sealed trait Event                           extends ChainHandler.Event
  final case class BlockAdded(hash: BlockHash) extends Event
  case object BlockAddingFailed                extends Event
  final case class InvalidBlock(hash: BlockHash, reason: InvalidBlockStatus) extends Event

  val blocksTotal: Gauge = Gauge
    .build(
      "alephium_blocks_total",
      "Total number of blocks"
    )
    .labelNames("chain_from", "chain_to")
    .register()

  val blocksReceivedTotal: Counter = Counter
    .build(
      "alephium_blocks_received_total",
      "Total number of blocks received"
    )
    .labelNames("chain_from", "chain_to")
    .register()

  val transactionsReceivedTotal: Counter = Counter
    .build(
      "alephium_transactions_received_total",
      "Total number of transactions received"
    )
    .labelNames("chain_from", "chain_to")
    .register()
}

class BlockChainHandler(
    blockFlow: BlockFlow,
    chainIndex: ChainIndex,
    eventBus: ActorRefT[EventBus.Message]
)(implicit
    brokerConfig: BrokerConfig,
    val consensusConfig: ConsensusConfig,
    networkSetting: NetworkSetting,
    logConfig: LogConfig
) extends ChainHandler[Block, InvalidBlockStatus, Option[WorldState.Cached], BlockValidation](
      blockFlow,
      chainIndex,
      BlockValidation.build
    )
    with EventStream.Publisher
    with InterCliqueManager.NodeSyncStatus {
  import BlockChainHandler._

  override def receive: Receive = validate orElse updateNodeSyncStatus

  def validate: Receive = { case Validate(block, broker, origin) =>
    handleData(block, broker, origin)
  }

  def validateWithSideEffect(
      block: Block,
      origin: DataOrigin
  ): ValidationResult[InvalidBlockStatus, Option[WorldState.Cached]] = {
    for {
      _ <- validator.headerValidation.validate(block.header, blockFlow)
      blockMsgOpt = {
        blockFlow.cacheHeaderVerifiedBlock(block)
        interCliqueBroadcast(block, origin)
      }
      sideResult <- validator.checkBlockAfterHeader(block, blockFlow)
    } yield {
      intraCliqueBroadCast(block, blockMsgOpt, origin)
      sideResult
    }
  }

  private def interCliqueBroadcast(
      block: Block,
      origin: DataOrigin
  ): Option[ByteString] = {
    Option.when(brokerConfig.contains(block.chainIndex.from) && isNodeSynced) {
      val blockMessage = Message.serialize(NewBlock(block))
      val event        = InterCliqueManager.BroadCastBlock(block, blockMessage, origin)
      publishEvent(event)
      blockMessage
    }
  }

  private def intraCliqueBroadCast(
      block: Block,
      blockMsgOpt: Option[ByteString],
      origin: DataOrigin
  ): Unit = {
    val broadcastIntraClique = brokerConfig.brokerNum != 1
    if (brokerConfig.contains(block.chainIndex.from) && broadcastIntraClique) {
      val blockMsg  = blockMsgOpt.getOrElse(Message.serialize(NewBlock(block)))
      val headerMsg = Message.serialize(NewHeader(block.header))
      val event     = IntraCliqueManager.BroadCastBlock(block, blockMsg, headerMsg, origin)
      publishEvent(event)
    }
  }

  override def dataAddingFailed(): Event = BlockAddingFailed

  override def dataInvalid(data: Block, reason: InvalidBlockStatus): Event =
    InvalidBlock(data.hash, reason)

  override def addDataToBlockFlow(
      block: Block,
      validationSideEffect: Option[WorldState.Cached]
  ): IOResult[Unit] = {
    blockFlow.add(block, validationSideEffect)
  }

  override def notifyBroker(broker: ActorRefT[ChainHandler.Event], block: Block): Unit = {
    broker ! BlockAdded(block.hash)
    if (brokerConfig.contains(block.chainIndex.from)) {
      escapeIOError(blockFlow.getHeight(block)) { height =>
        eventBus ! BlockNotify(block, height)
      }
    }
  }

  override def show(block: Block): String = {
    showHeader(block.header) + s"; #tx: ${block.transactions.length}"
  }

  private val blocksTotalLabeled = blocksTotal.labels(chainIndexFromString, chainIndexToString)
  private val blocksReceivedTotalLabeled =
    blocksReceivedTotal.labels(chainIndexFromString, chainIndexToString)
  private val transactionsReceivedTotalLabeled =
    transactionsReceivedTotal.labels(chainIndexFromString, chainIndexToString)
  override def measure(block: Block): Unit = {
    val chain             = measureCommon(block.header)
    val numOfTransactions = block.transactions.length

    blocksTotalLabeled.set(chain.numHashes.toDouble)
    blocksReceivedTotalLabeled.inc()
    transactionsReceivedTotalLabeled.inc(numOfTransactions.toDouble)
  }

  val chainValidationTotalLabeled: Counter.Child = ChainHandler.chainValidationTotal.labels("Block")
  val chainValidationDurationMilliSecondsLabeled: Histogram.Child =
    ChainHandler.chainValidationDurationMilliSeconds.labels("Block")
}
