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

import org.alephium.flow.client.Miner
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.io.IOUtils
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.message.{Message, SendHeaders}
import org.alephium.protocol.model._
import org.alephium.util._

object FlowHandler {
  def props(blockFlow: BlockFlow, eventBus: ActorRefT[EventBus.Message])(
      implicit brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig): Props =
    Props(new FlowHandler(blockFlow, eventBus))

  sealed trait Command
  final case class AddHeader(header: BlockHeader,
                             broker: ActorRefT[ChainHandler.Event],
                             origin: DataOrigin)
      extends Command
  final case class AddBlock(block: Block, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin)
      extends Command
  final case class GetBlocks(locators: AVector[Hash])                   extends Command
  final case class GetHeaders(locators: AVector[Hash])                  extends Command
  case object GetSyncLocators                                           extends Command
  final case class GetSyncInventories(locators: AVector[AVector[Hash]]) extends Command
  final case class GetIntraSyncInventories(brokerInfo: BrokerInfo)      extends Command
  final case class PrepareBlockFlow(chainIndex: ChainIndex)             extends Command
  final case class Register(miner: ActorRefT[Miner.Command])            extends Command
  case object UnRegister                                                extends Command

  sealed trait PendingData {
    def hash: Hash
    def missingDeps: mutable.HashSet[Hash]
  }
  final case class PendingBlock(block: Block,
                                missingDeps: mutable.HashSet[Hash],
                                origin: DataOrigin,
                                broker: ActorRefT[ChainHandler.Event],
                                chainHandler: ActorRefT[BlockChainHandler.Command])
      extends PendingData
      with Command {
    override def hash: Hash = block.hash
  }
  final case class PendingHeader(header: BlockHeader,
                                 missingDeps: mutable.HashSet[Hash],
                                 origin: DataOrigin,
                                 broker: ActorRefT[ChainHandler.Event],
                                 chainHandler: ActorRefT[HeaderChainHandler.Command])
      extends PendingData
      with Command {
    override def hash: Hash = header.hash
  }

  sealed trait Event
  final case class BlockFlowTemplate(index: ChainIndex,
                                     deps: AVector[Hash],
                                     target: Target,
                                     parentTs: TimeStamp,
                                     transactions: AVector[Transaction])
      extends Event
  final case class BlocksLocated(blocks: AVector[Block])           extends Event
  final case class SyncInventories(hashes: AVector[AVector[Hash]]) extends Event
  final case class SyncLocators(selfBrokerInfo: BrokerConfig, hashes: AVector[AVector[Hash]])
      extends Command {
    def filerFor(another: BrokerGroupInfo): AVector[AVector[Hash]] = {
      val (groupFrom, groupUntil) = selfBrokerInfo.calIntersection(another)
      if (groupUntil <= groupFrom) {
        AVector.empty
      } else {
        hashes.slice((groupFrom - selfBrokerInfo.groupFrom) * selfBrokerInfo.groups,
                     (groupUntil - selfBrokerInfo.groupFrom) * selfBrokerInfo.groups)
      }
    }
  }
  final case class BlockAdded(block: Block,
                              broker: ActorRefT[ChainHandler.Event],
                              origin: DataOrigin)
      extends Event
  final case class HeaderAdded(header: BlockHeader,
                               broker: ActorRefT[ChainHandler.Event],
                               origin: DataOrigin)
      extends Event
  final case class BlockNotify(header: BlockHeader, height: Int) extends EventBus.Event
}

// TODO: set AddHeader and AddBlock with highest priority
// Queue all the work related to miner, rpc server, etc. in this actor
class FlowHandler(blockFlow: BlockFlow, eventBus: ActorRefT[EventBus.Message])(
    implicit brokerConfig: BrokerConfig,
    consensusConfig: ConsensusConfig)
    extends IOBaseActor
    with FlowHandlerState {
  import FlowHandler._

  override def statusSizeLimit: Int = brokerConfig.brokerNum * 100 // TODO: config this

  override def receive: Receive = handleWith(None)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
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
    case GetBlocks(locators: AVector[Hash]) =>
      locators.flatMapE(blockFlow.getBlocksAfter) match {
        case Left(error) =>
          log.warning(s"IO Failure while getting blocks: $error")
        case Right(blocks) =>
          sender() ! BlocksLocated(blocks)
      }
    case PrepareBlockFlow(chainIndex) => prepareBlockFlow(chainIndex)
    case AddHeader(header, broker, origin: DataOrigin) =>
      handleHeader(minerOpt, header, broker, origin)
    case AddBlock(block, broker, origin) =>
      handleBlock(minerOpt, block, broker, origin)
    case pending: PendingData => handlePending(pending)
    case Register(miner)      => context become handleWith(Some(miner))
    case UnRegister           => context become handleWith(None)
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

  def handleHeader(minerOpt: Option[ActorRefT[Miner.Command]],
                   header: BlockHeader,
                   broker: ActorRefT[ChainHandler.Event],
                   origin: DataOrigin): Unit = {
    blockFlow.contains(header) match {
      case Right(true) =>
        log.debug(s"Blockheader ${header.shortHex} exists already")
      case Right(false) =>
        blockFlow.add(header) match {
          case Left(error) => handleIOError(error)
          case Right(_) =>
            sender() ! FlowHandler.HeaderAdded(header, broker, origin)
            updateUponNewData(header.hash)
            minerOpt.foreach(_ ! Miner.UpdateTemplate)
            logInfo(header)
        }
      case Left(error) => handleIOError(error)
    }
  }

  def handleBlock(minerOpt: Option[ActorRefT[Miner.Command]],
                  block: Block,
                  broker: ActorRefT[ChainHandler.Event],
                  origin: DataOrigin): Unit = {
    escapeIOError(blockFlow.contains(block)) { isIncluded =>
      if (!isIncluded) {
        blockFlow.add(block) match {
          case Left(error) => handleIOError(error)
          case Right(_) =>
            sender() ! FlowHandler.BlockAdded(block, broker, origin)
            updateUponNewData(block.hash)
            origin match {
              case DataOrigin.Local => ()
              case _: DataOrigin.FromClique =>
                minerOpt.foreach(_ ! Miner.UpdateTemplate)
            }
            logInfo(block.header)
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

  def handlePending(pending: PendingData): Unit = {
    val missings = pending.missingDeps
    escapeIOError(IOUtils.tryExecute(missings.filterInPlace(!blockFlow.containsUnsafe(_)))) { _ =>
      if (missings.isEmpty) {
        feedback(pending)
      } else {
        addStatus(pending)
      }
    }
  }

  def updateUponNewData(hash: Hash): Unit = {
    val readies = updateStatus(hash)
    if (readies.nonEmpty) {
      log.debug(s"There are #${readies.size} pending blocks/header ready for further processing")
    }
    readies.foreach(feedback)
  }

  def feedback(pending: PendingData): Unit = pending match {
    case PendingBlock(block, _, origin, broker, chainHandler) =>
      chainHandler ! BlockChainHandler.AddPendingBlock(block, broker, origin)
    case PendingHeader(header, _, origin, broker, chainHandler) =>
      chainHandler ! HeaderChainHandler.AddPendingHeader(header, broker, origin)
  }

  def logInfo(header: BlockHeader): Unit = {
    val total = blockFlow.numHashes
    val index = header.chainIndex
    val chain = blockFlow.getHeaderChain(header)
    val heights = for {
      i <- 0 until brokerConfig.groups
      j <- 0 until brokerConfig.groups
      height = blockFlow.getHashChain(ChainIndex.unsafe(i, j)).maxHeight.getOrElse(-1)
    } yield s"$i-$j:$height"
    val heightsInfo = heights.mkString(", ")
    val targetRatio =
      (BigDecimal(header.target.value) / BigDecimal(consensusConfig.maxMiningTarget.value)).toFloat
    val timeSpan = {
      chain.getBlockHeader(header.parentHash) match {
        case Left(_) => "?ms"
        case Right(parentHeader) =>
          val span = header.timestamp.millis - parentHeader.timestamp.millis
          s"${span}ms"
      }
    }
    log.info(s"$index; total: $total; ${chain
      .show(header.hash)}; heights: $heightsInfo; targetRatio: $targetRatio, timeSpan: $timeSpan")
  }
}

trait FlowHandlerState {
  import FlowHandler._

  def statusSizeLimit: Int

  var counter: Int  = 0
  val pendingStatus = mutable.SortedMap.empty[Int, PendingData]
  val pendingHashes = mutable.Set.empty[Hash]

  def increaseAndCounter(): Int = {
    counter += 1
    counter
  }

  def addStatus(pending: PendingData): Unit = {
    if (!pendingHashes.contains(pending.hash)) {
      pendingStatus.put(increaseAndCounter(), pending)
      pendingHashes.add(pending.hash)
      checkSizeLimit()
    }
  }

  def updateStatus(hash: Hash): IndexedSeq[PendingData] = {
    val toRemove = pendingStatus.collect[Int] {
      case (ts, status) if status.missingDeps.remove(hash) && status.missingDeps.isEmpty =>
        ts
    }
    val data = toRemove.map(pendingStatus(_))
    toRemove.foreach(removeStatus)
    data.toIndexedSeq
  }

  def removeStatus(id: Int): Unit = {
    pendingStatus.remove(id).foreach { data =>
      pendingHashes.remove(data.hash)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def checkSizeLimit(): Unit = {
    if (pendingStatus.size > statusSizeLimit) {
      val toRemove = pendingStatus.head._1
      removeStatus(toRemove)
    }
  }
}
