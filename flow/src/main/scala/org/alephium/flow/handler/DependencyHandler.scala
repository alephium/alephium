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
import scala.jdk.CollectionConverters._

import akka.actor.{ActorSystem, Props}

import org.alephium.flow.core.{maxSyncBlocksPerChain, BlockFlow}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.setting.NetworkSetting
import org.alephium.io.IOResult
import org.alephium.protocol.model.{Block, BlockHash, BlockHeader, ChainIndex, FlowData}
import org.alephium.util.{ActorRefT, AVector, Cache, TimeStamp}
import org.alephium.util.EventStream

object DependencyHandler {
  def build(
      system: ActorSystem,
      blockFlow: BlockFlow,
      blockHandlers: Map[ChainIndex, ActorRefT[BlockChainHandler.Command]],
      headerHandlers: Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]],
      namePostfix: String
  )(implicit networkSetting: NetworkSetting): ActorRefT[Command] = {
    val actor = ActorRefT.build[Command](
      system,
      Props(new DependencyHandler(blockFlow, blockHandlers, headerHandlers)),
      s"DependencyHandler$namePostfix"
    )
    system.eventStream.subscribe(actor.ref, classOf[ChainHandler.FlowDataAdded])
    actor
  }

  sealed trait Command
  final case class AddFlowData[T <: FlowData](datas: AVector[T], origin: DataOrigin) extends Command
  final case class Invalid(data: BlockHash)                                          extends Command
  final case object GetPendings                                                      extends Command
  case object CleanPendings                                                          extends Command

  sealed trait Event
  final case class Pendings(datas: AVector[BlockHash]) extends Event

  final case class FlowDataAlreadyExist(data: FlowData) extends EventStream.Event

  final case class PendingStatus(
      data: FlowData,
      event: ActorRefT[ChainHandler.Event],
      origin: DataOrigin,
      timestamp: TimeStamp
  )
}

class DependencyHandler(
    val blockFlow: BlockFlow,
    blockHandlers: Map[ChainIndex, ActorRefT[BlockChainHandler.Command]],
    headerHandlers: Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]]
)(implicit val networkSetting: NetworkSetting)
    extends DependencyHandlerState {
  import DependencyHandler._

  override def preStart(): Unit = {
    super.preStart()
    scheduleOnce(
      self,
      DependencyHandler.CleanPendings,
      networkSetting.dependencyExpiryPeriod.divUnsafe(2)
    )
  }

  override def receive: Receive = {
    case AddFlowData(datas, origin) =>
      val broker        = ActorRefT[ChainHandler.Event](sender())
      val missingUncles = ArrayBuffer.empty[BlockHash]
      datas.foreach(addPendingData(_, broker, origin, missingUncles))
      if (missingUncles.nonEmpty) {
        ActorRefT[BrokerHandler.Command](sender()) ! BrokerHandler.DownloadBlocks(
          AVector.from(missingUncles)
        )
      }
      processReadies()
    case ChainHandler.FlowDataAdded(data, _, _) =>
      uponDataProcessed(data)
      processReadies()
    case Invalid(hash) =>
      uponInvalidData(hash)
    case GetPendings =>
      sender() ! Pendings(AVector.from(pending.keys()))
    case CleanPendings =>
      val threshold = TimeStamp.now().minusUnsafe(networkSetting.dependencyExpiryPeriod)
      cleanPendings(pending.entries(), threshold)
      scheduleOnce(
        self,
        DependencyHandler.CleanPendings,
        networkSetting.dependencyExpiryPeriod.divUnsafe(2)
      )
  }

  def processReadies(): Unit = {
    val readies = extractReadies()
    readies.foreach {
      case PendingStatus(block: Block, broker, origin, _) =>
        blockHandlers(block.chainIndex) ! BlockChainHandler.Validate(block, broker, origin)
      case PendingStatus(header: BlockHeader, broker, origin, _) =>
        headerHandlers(header.chainIndex) ! HeaderChainHandler.Validate(header, broker, origin)
      case _ => () // dead branch
    }
  }
}

trait DependencyHandlerState extends IOBaseActor with EventStream.Publisher {
  import DependencyHandler.PendingStatus

  def blockFlow: BlockFlow
  def networkSetting: NetworkSetting

  def cleanPendings(
      iterator: Iterator[JMap.Entry[BlockHash, PendingStatus]],
      threshold: TimeStamp
  ): Unit = {
    val toRemove = mutable.ArrayBuffer.empty[BlockHash] // not able to remove by the iterator
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

  val cacheSize =
    maxSyncBlocksPerChain * blockFlow.brokerConfig.chainNum * 2
  val pending = Cache.fifo[BlockHash, PendingStatus] {
    (map: LinkedHashMap[BlockHash, PendingStatus], eldest: JMap.Entry[BlockHash, PendingStatus]) =>
      if (map.size > cacheSize) {
        removePending(eldest.getKey())
      }
      val threshold = TimeStamp.now().minusUnsafe(networkSetting.dependencyExpiryPeriod)
      if (eldest.getValue().timestamp <= threshold) {
        cleanPendings(map.entrySet().iterator().asScala, threshold)
      }
  }

  val missing      = mutable.HashMap.empty[BlockHash, ArrayBuffer[BlockHash]]
  val missingIndex = mutable.HashMap.empty[BlockHash, ArrayBuffer[BlockHash]]
  val readies      = mutable.HashSet.empty[BlockHash]
  val processing   = mutable.HashSet.empty[BlockHash]

  private def getDeps(flowData: FlowData): IOResult[(AVector[BlockHash], AVector[BlockHash])] = {
    val (deps, uncles) = flowData match {
      case header: BlockHeader => (header.blockDeps.deps, AVector.empty[BlockHash])
      case block: Block =>
        val hardFork = networkSetting.getHardFork(block.timestamp)
        if (hardFork.isRhoneEnabled()) {
          block.ghostUncleHashes(networkSetting) match {
            case Right(hashes) => (block.blockDeps.deps ++ hashes, hashes)
            case Left(error) =>
              log.error(s"Failed to deserialize uncles, error: $error")
              (AVector.empty[BlockHash], AVector.empty[BlockHash])
          }
        } else {
          (block.blockDeps.deps, AVector.empty[BlockHash])
        }
    }
    deps.filterNotE(blockFlow.contains).map(_ -> uncles)
  }

  def addPendingData(
      data: FlowData,
      broker: ActorRefT[ChainHandler.Event],
      origin: DataOrigin,
      missingGhostUncles: ArrayBuffer[BlockHash]
  ): Unit = {
    if (!pending.contains(data.hash)) {
      escapeIOError(blockFlow.contains(data.hash)) { existing =>
        if (!existing) {
          escapeIOError(getDeps(data)) { case (missingDeps, uncles) =>
            if (missingDeps.nonEmpty) {
              missing(data.hash) = ArrayBuffer.from(missingDeps.toIterable)

              if (uncles.nonEmpty) {
                missingGhostUncles ++= uncles.filter(hash =>
                  missingDeps.contains(hash) && !(missing.contains(hash) || readies.contains(hash))
                )
              }

              missingDeps.foreach { dep =>
                missingIndex.get(dep) match {
                  case Some(children) =>
                    if (!children.contains(data.hash)) children.addOne(data.hash)
                  case None => missingIndex(dep) = ArrayBuffer(data.hash)
                }
              }
            } else {
              readies.addOne(data.hash)
            }
          }
          // update this at the end of this function to avoid cache invalidation issues
          pending.put(data.hash, PendingStatus(data, broker, origin, TimeStamp.now()))
        } else {
          publishEvent(DependencyHandler.FlowDataAlreadyExist(data))
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
