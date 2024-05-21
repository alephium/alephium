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

import java.util.{LinkedHashMap, Map => JMap}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import akka.actor.Props

import org.alephium.flow.core.{maxForkDepth => systemMaxForkDepth, maxSyncBlocksPerChain, BlockFlow}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.model.{Block, BlockHash, BlockHeader, ChainIndex, FlowData}
import org.alephium.util.{ActorRefT, AVector, Cache, TimeStamp}
import org.alephium.util.EventStream.{Publisher, Subscriber}

object DependencyHandler {
  def props(
      blockFlow: BlockFlow,
      blockHandlers: Map[ChainIndex, ActorRefT[BlockChainHandler.Command]],
      headerHandlers: Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]]
  )(implicit networkSetting: NetworkSetting): Props =
    Props(new DependencyHandler(blockFlow, blockHandlers, headerHandlers))

  sealed trait Command
  final case class AddFlowData[T <: FlowData](datas: AVector[T], origin: DataOrigin) extends Command
  final case class Invalid(data: BlockHash)                                          extends Command
  final case object GetPendings                                                      extends Command

  sealed trait Event
  final case class Pendings(datas: AVector[BlockHash]) extends Event

  final case class PendingStatus(
      data: FlowData,
      event: ActorRefT[ChainHandler.Event],
      origin: DataOrigin,
      timestamp: TimeStamp
  )
}

class DependencyHandler(
    val blockFlow: BlockFlow,
    val blockHandlers: Map[ChainIndex, ActorRefT[BlockChainHandler.Command]],
    val headerHandlers: Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]]
)(implicit val networkSetting: NetworkSetting)
    extends DependencyHandlerState
    with Subscriber {
  import DependencyHandler._

  subscribeEvent(self, classOf[ChainHandler.FlowDataAdded])

  override def receive: Receive = {
    case AddFlowData(datas, origin) =>
      val broker = ActorRefT[ChainHandler.Event](sender())
      datas.foreach(addPendingData(_, broker, origin))
      processReadies()
    case ChainHandler.FlowDataAdded(data, _, _) =>
      uponDataProcessed(data)
      processReadies()
    case Invalid(hash) =>
      uponInvalidData(hash)
    case GetPendings =>
      sender() ! Pendings(AVector.from(pending.keys()))
  }
}

trait DependencyHandlerState extends IOBaseActor with Publisher {
  import DependencyHandler.PendingStatus

  def blockFlow: BlockFlow
  def blockHandlers: Map[ChainIndex, ActorRefT[BlockChainHandler.Command]]
  def headerHandlers: Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]]
  def networkSetting: NetworkSetting
  val maxForkDepth: Int = systemMaxForkDepth

  val cacheSize =
    maxSyncBlocksPerChain * blockFlow.brokerConfig.chainNum * 2
  val pending = Cache.fifo[BlockHash, PendingStatus] {
    (map: LinkedHashMap[BlockHash, PendingStatus], eldest: JMap.Entry[BlockHash, PendingStatus]) =>
      if (map.size > cacheSize) {
        removePending(eldest.getKey())
      }
      val threshold = TimeStamp.now().minusUnsafe(networkSetting.dependencyExpiryPeriod)
      if (eldest.getValue().timestamp <= threshold) {
        val toRemove = mutable.ArrayBuffer.empty[BlockHash] // not able to remove by the iterator
        val iterator = map.entrySet().iterator()
        var continue = true
        while (continue && iterator.hasNext) {
          val entry = iterator.next()
          if (entry.getValue().timestamp <= threshold) {
            toRemove.addOne(entry.getKey())
          } else {
            continue = false
          }
        }
        toRemove.foreach(removePending)
      }
  }

  val missing      = mutable.HashMap.empty[BlockHash, ArrayBuffer[BlockHash]]
  val missingIndex = mutable.HashMap.empty[BlockHash, ArrayBuffer[BlockHash]]
  val readies      = mutable.HashSet.empty[BlockHash]
  val processing   = mutable.HashSet.empty[BlockHash]

  private def getDeps(flowData: FlowData): AVector[BlockHash] = {
    flowData match {
      case header: BlockHeader => header.blockDeps.deps
      case block: Block =>
        val hardFork = networkSetting.getHardFork(block.timestamp)
        if (hardFork.isRhoneEnabled()) {
          block.ghostUncleHashes(networkSetting) match {
            case Right(hashes) => block.blockDeps.deps ++ hashes
            case Left(error) =>
              log.error(s"Failed to deserialize uncles, error: $error")
              AVector.empty
          }
        } else {
          block.blockDeps.deps
        }
    }
  }

  def addPendingData(
      data: FlowData,
      broker: ActorRefT[ChainHandler.Event],
      origin: DataOrigin
  ): Unit = {
    if (!pending.contains(data.hash)) {
      escapeIOError(blockFlow.contains(data.hash)) { existing =>
        if (!existing) {
          escapeIOError(getDeps(data).filterNotE(blockFlow.contains)) { missingDeps =>
            if (missingDeps.nonEmpty) {
              missing(data.hash) = ArrayBuffer.from(missingDeps.toIterable)
            }

            missingDeps.foreach { dep =>
              missingIndex.get(dep) match {
                case Some(children) => if (!children.contains(data.hash)) children.addOne(data.hash)
                case None           => missingIndex(dep) = ArrayBuffer(data.hash)
              }
            }

            if (missingDeps.isEmpty) readies.addOne(data.hash)
          }
          // update this at the end of this function to avoid cache invalidation issues
          pending.put(data.hash, PendingStatus(data, broker, origin, TimeStamp.now()))
        }
      }
    }
  }

  def extractReadies(): AVector[PendingStatus] = {
    val result = AVector.from(readies.view.map(pending.unsafe))
    processing.addAll(readies)
    readies.clear()
    result
  }

  @inline private def processReadyBlock(
      block: Block,
      broker: ActorRefT[ChainHandler.Event],
      origin: DataOrigin
  ): Unit = {
    val blockChain = blockFlow.getBlockChain(block.chainIndex)
    blockChain.validateBlockHeight(block, maxForkDepth) match {
      case Right(true) =>
        blockHandlers(block.chainIndex) ! BlockChainHandler.Validate(block, broker, origin)
      case Right(false) =>
        origin match {
          case DataOrigin.InterClique(brokerInfo) =>
            publishEvent(MisbehaviorManager.DeepForkBlock(brokerInfo.address))
          case _ =>
            log.error(s"Received invalid block from $origin, block hash: ${block.hash.toHexString}")
        }
        removePending(block.hash)
      case Left(error) => // this should never happen
        log.error(s"IO error in validating block height: $error")
        removePending(block.hash)
    }
  }

  def processReadies(): Unit = {
    val readies = extractReadies()
    readies.foreach {
      case PendingStatus(block: Block, broker, origin, _) =>
        processReadyBlock(block, broker, origin)
      case PendingStatus(header: BlockHeader, broker, origin, _) =>
        headerHandlers(header.chainIndex) ! HeaderChainHandler.Validate(header, broker, origin)
      case _ => () // dead branch
    }
  }

  def uponDataProcessed(data: FlowData): Unit = {
    processing.remove(data.hash)

    missingIndex.remove(data.hash).foreach { children =>
      children.foreach { child =>
        val childMissing = missing(child)
        childMissing -= data.hash
        if (childMissing.isEmpty) {
          missing.remove(child)
          readies.addOne(child)
        }
      }
    }

    pending.remove(data.hash)
    ()
  }

  def uponInvalidData(hash: BlockHash): Unit = {
    removePending(hash)
  }

  def removePending(hash: BlockHash): Unit = {
    _removePending(hash)
    readies -= hash
    processing -= hash
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def _removePending(hash: BlockHash): Unit = {
    pending.remove(hash)

    missingIndex.remove(hash).foreach { newHashes =>
      newHashes.foreach(_removePending)
    }

    missing.remove(hash).foreach { oldHashes =>
      oldHashes.foreach { oldHash =>
        missingIndex.get(oldHash).foreach { pending =>
          pending -= hash
          if (pending.isEmpty) {
            missingIndex.remove(oldHash)
          }
        }
      }
    }
  }
}
