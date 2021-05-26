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
import scala.collection.mutable.ArrayBuffer

import akka.actor.Props

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.protocol.BlockHash
import org.alephium.protocol.model.{Block, BlockHeader, ChainIndex, FlowData}
import org.alephium.util.{ActorRefT, AVector}
import org.alephium.util.EventStream.Subscriber

object DependencyHandler {
  def props(
      blockFlow: BlockFlow,
      blockHandlers: Map[ChainIndex, ActorRefT[BlockChainHandler.Command]],
      headerHandlers: Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]]
  ): Props =
    Props(new DependencyHandler(blockFlow, blockHandlers, headerHandlers))

  sealed trait Command
  final case class AddFlowData[T <: FlowData](datas: AVector[T], origin: DataOrigin) extends Command
  final case class Invalid(data: BlockHash)                                          extends Command
  final case object GetPendings                                                      extends Command

  sealed trait Event
  final case class Pendings(datas: AVector[BlockHash]) extends Event
}

class DependencyHandler(
    val blockFlow: BlockFlow,
    blockHandlers: Map[ChainIndex, ActorRefT[BlockChainHandler.Command]],
    headerHandlers: Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]]
) extends DependencyHandlerState
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
      sender() ! Pendings(AVector.from(pending.keys))
  }

  def processReadies(): Unit = {
    val readies = extractReadies()
    readies.foreach {
      case (block: Block, broker, origin) =>
        blockHandlers(block.chainIndex) ! BlockChainHandler.Validate(block, broker, origin)
      case (header: BlockHeader, broker, origin) =>
        headerHandlers(header.chainIndex) ! HeaderChainHandler.Validate(header, broker, origin)
      case _ => () // dead branch
    }
  }
}

trait DependencyHandlerState extends IOBaseActor {
  def blockFlow: BlockFlow

  val pending =
    mutable.HashMap.empty[BlockHash, (FlowData, ActorRefT[ChainHandler.Event], DataOrigin)]
  val missing      = mutable.HashMap.empty[BlockHash, ArrayBuffer[BlockHash]]
  val missingIndex = mutable.HashMap.empty[BlockHash, ArrayBuffer[BlockHash]]
  val readies      = mutable.HashSet.empty[BlockHash]
  val processing   = mutable.HashSet.empty[BlockHash]

  def addPendingData(
      data: FlowData,
      broker: ActorRefT[ChainHandler.Event],
      origin: DataOrigin
  ): Unit = {
    escapeIOError(blockFlow.contains(data.hash)) { existing =>
      if (!existing && !pending.contains(data.hash)) {
        pending(data.hash) = (data, broker, origin)

        escapeIOError(data.blockDeps.deps.filterNotE(blockFlow.contains)) { missingDeps =>
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
      }
    }
  }

  def extractReadies(): AVector[(FlowData, ActorRefT[ChainHandler.Event], DataOrigin)] = {
    val result = AVector.from(readies.view.map(pending.apply))
    processing.addAll(readies)
    readies.clear()
    result
  }

  def uponDataProcessed(data: FlowData): Unit = {
    processing.remove(data.hash)

    missingIndex.get(data.hash).foreach { children =>
      children.foreach { child =>
        val childMissing = missing(child)
        childMissing -= data.hash
        if (childMissing.isEmpty) {
          missing.remove(child)
          readies.addOne(child)
        }
      }
    }
    missingIndex.remove(data.hash)

    pending.remove(data.hash)
    ()
  }

  def uponInvalidData(hash: BlockHash): Unit = {
    readies -= hash
    processing -= hash

    invalidPending(hash)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def invalidPending(hash: BlockHash): Unit = {
    pending -= hash
    missing -= hash
    missingIndex.get(hash).foreach { children =>
      missingIndex -= hash
      children.foreach(invalidPending)
    }
  }
}
