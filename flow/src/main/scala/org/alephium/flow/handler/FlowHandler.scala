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

import akka.actor.{Props, Stash}

import org.alephium.flow.client.Miner
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.message.{Message, SendHeaders}
import org.alephium.protocol.model._
import org.alephium.util._

object FlowHandler {
  def props(blockFlow: BlockFlow, eventBus: ActorRefT[EventBus.Message])(implicit
      brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig
  ): Props =
    Props(new FlowHandler(blockFlow, eventBus))

  sealed trait Command
  final case class SetHandler(handler: ActorRefT[DependencyHandler.Command]) extends Command
  final case class AddHeader(
      header: BlockHeader,
      broker: ActorRefT[ChainHandler.Event],
      origin: DataOrigin
  ) extends Command
  final case class AddBlock(block: Block, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin)
      extends Command
  final case class GetBlocks(locators: AVector[BlockHash])                   extends Command
  final case class GetHeaders(locators: AVector[BlockHash])                  extends Command
  case object GetSyncLocators                                                extends Command
  final case class GetSyncInventories(locators: AVector[AVector[BlockHash]]) extends Command
  final case class GetIntraSyncInventories(brokerInfo: BrokerInfo)           extends Command
  final case class PrepareBlockFlow(chainIndex: ChainIndex)                  extends Command
  final case class Register(miner: ActorRefT[Miner.Command])                 extends Command
  case object UnRegister                                                     extends Command

  sealed trait Event
  final case class BlockFlowTemplate(
      index: ChainIndex,
      deps: AVector[BlockHash],
      target: Target,
      parentTs: TimeStamp,
      transactions: AVector[Transaction]
  )                                                                     extends Event
  final case class BlocksLocated(blocks: AVector[Block])                extends Event
  final case class SyncInventories(hashes: AVector[AVector[BlockHash]]) extends Event
  final case class SyncLocators(selfBrokerInfo: BrokerConfig, hashes: AVector[AVector[BlockHash]])
      extends Command {
    def filerFor(another: BrokerGroupInfo): AVector[AVector[BlockHash]] = {
      val (groupFrom, groupUntil) = selfBrokerInfo.calIntersection(another)
      if (groupUntil <= groupFrom) {
        AVector.empty
      } else {
        hashes.slice(
          (groupFrom - selfBrokerInfo.groupFrom) * selfBrokerInfo.groups,
          (groupUntil - selfBrokerInfo.groupFrom) * selfBrokerInfo.groups
        )
      }
    }
  }
  final case class BlockNotify(header: BlockHeader, height: Int) extends EventBus.Event
}

// TODO: set AddHeader and AddBlock with highest priority
// Queue all the work related to miner, rpc server, etc. in this actor
class FlowHandler(blockFlow: BlockFlow, eventBus: ActorRefT[EventBus.Message])(implicit
    brokerConfig: BrokerConfig,
    consensusConfig: ConsensusConfig
) extends IOBaseActor
    with Stash {
  import FlowHandler._

  private var dependencyHandler: ActorRefT[DependencyHandler.Command] = _

  override def receive: Receive = {
    case SetHandler(handler) =>
      dependencyHandler = handler
      unstashAll()
      context become handleWith(None)
    case _ => stash()
  }

  def handleWith(minerOpt: Option[ActorRefT[Miner.Command]]): Receive =
    handleRelay(minerOpt) orElse handleSync

  def handleRelay(minerOpt: Option[ActorRefT[Miner.Command]]): Receive = {
    case GetHeaders(locators) =>
      locators.flatMapE(blockFlow.getHeadersAfter) match {
        case Left(error) =>
          log.warning(s"Failure while getting block headers: $error")
        case Right(headers) =>
          sender() ! Message(SendHeaders(headers))
      }
    case GetBlocks(locators: AVector[BlockHash]) =>
      locators.flatMapE(blockFlow.getBlocksAfter) match {
        case Left(error) =>
          log.warning(s"IO Failure while getting blocks: $error")
        case Right(blocks) =>
          sender() ! BlocksLocated(blocks)
      }
    case PrepareBlockFlow(chainIndex)    => prepareBlockFlow(chainIndex)
    case AddHeader(header, broker, _)    => handleHeader(minerOpt, header, broker)
    case AddBlock(block, broker, origin) => handleBlock(minerOpt, block, broker, origin)
    case Register(miner)                 => context become handleWith(Some(miner))
    case UnRegister                      => context become handleWith(None)
  }

  def handleSync: Receive = {
    case GetSyncLocators =>
      escapeIOError(blockFlow.getSyncLocators()) { locators =>
        sender() ! SyncLocators(brokerConfig, locators)
      }
    case GetSyncInventories(locators) =>
      escapeIOError(blockFlow.getSyncInventories(locators)) { inventories =>
        sender() ! SyncInventories(inventories)
      }
    case GetIntraSyncInventories(brokerInfo) =>
      escapeIOError(blockFlow.getIntraSyncInventories(brokerInfo)) { inventories =>
        sender() ! SyncInventories(inventories)
      }
  }

  def prepareBlockFlow(chainIndex: ChainIndex): Unit = {
    assume(brokerConfig.contains(chainIndex.from))
    val template = blockFlow.prepareBlockFlow(chainIndex)
    template match {
      case Left(error) =>
        log.warning(s"Failure while computing best dependencies: ${error.toString}")
      case Right(message) =>
        sender() ! message
    }
  }

  def handleHeader(
      minerOpt: Option[ActorRefT[Miner.Command]],
      header: BlockHeader,
      broker: ActorRefT[ChainHandler.Event]
  ): Unit = {
    blockFlow.contains(header) match {
      case Right(true) =>
        log.debug(s"Blockheader ${header.shortHex} exists already")
      case Right(false) =>
        blockFlow.add(header) match {
          case Left(error) => handleIOError(error)
          case Right(_) =>
            dependencyHandler ! DependencyHandler.FlowDataAdded(header)
            broker ! HeaderChainHandler.HeaderAdded(header.hash)
            minerOpt.foreach(_ ! Miner.UpdateTemplate)
            log.info(show(header))
        }
      case Left(error) => handleIOError(error)
    }
  }

  def handleBlock(
      minerOpt: Option[ActorRefT[Miner.Command]],
      block: Block,
      broker: ActorRefT[ChainHandler.Event],
      origin: DataOrigin
  ): Unit = {
    escapeIOError(blockFlow.contains(block)) { isIncluded =>
      if (!isIncluded) {
        blockFlow.add(block) match {
          case Left(error) => handleIOError(error)
          case Right(_) =>
            dependencyHandler ! DependencyHandler.FlowDataAdded(block)
            broker ! BlockChainHandler.BlockAdded(block.hash)
            origin match {
              case DataOrigin.Local => ()
              case _: DataOrigin.FromClique =>
                minerOpt.foreach(_ ! Miner.UpdateTemplate)
            }
            log.info(show(block))
            notify(block)
        }
      }
    }
  }

  def notify(block: Block): Unit = {
    escapeIOError(blockFlow.getHeight(block)) { height =>
      eventBus ! BlockNotify(block.header, height)
    }
  }

  def show(header: BlockHeader): String = {
    val total = blockFlow.numHashes
    val index = header.chainIndex
    val chain = blockFlow.getHeaderChain(header)
    val targetRatio =
      (BigDecimal(header.target.value) / BigDecimal(consensusConfig.maxMiningTarget.value)).toFloat
    val blockTime = {
      chain.getBlockHeader(header.parentHash) match {
        case Left(_) => "?ms"
        case Right(parentHeader) =>
          val span = header.timestamp.millis - parentHeader.timestamp.millis
          s"${span}ms"
      }
    }
    s"hash: ${header.shortHex}; $index; ${chain.showHeight(header.hash)}; total: $total; targetRatio: $targetRatio, blockTime: $blockTime"
  }

  def show(block: Block): String = {
    show(block.header) + s" #tx: ${block.transactions.length}"
  }
}
