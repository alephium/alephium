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
import scala.reflect.ClassTag

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.validation._
import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.BlockHash
import org.alephium.protocol.model.{ChainIndex, FlowData}
import org.alephium.util._

object ChainHandler {
  trait Event
}

abstract class ChainHandler[T <: FlowData: ClassTag, S <: InvalidStatus, Command](
    blockFlow: BlockFlow,
    val chainIndex: ChainIndex,
    validator: Validation[T, S])
    extends ChainHandlerState[T]
    with IOBaseActor {
  import ChainHandler.Event

  // TODO: validate continuity
  def handleDatas(datas: Forest[BlockHash, T],
                  broker: ActorRefT[ChainHandler.Event],
                  origin: DataOrigin): Unit = {
    addTasks(broker, datas)
    handleReadies(broker, origin, t => blockFlow.contains(t.parentHash))
  }

  def checkContinuity(datas: Forest[BlockHash, T],
                      broker: ActorRefT[ChainHandler.Event]): IOResult[Boolean] = {
    AVector.from(datas.roots).forallE { node =>
      blockFlow
        .contains(node.value.parentHash)
        .map(_ || isProcessing(broker, node.value.parentHash))
    }
  }

  def handleReadies(broker: ActorRefT[ChainHandler.Event],
                    origin: DataOrigin,
                    predict: T => IOResult[Boolean]): Unit = {
    extractReady(broker, predict) match {
      case Right(readies) => readies.foreach(handleData(_, broker, origin))
      case Left(error)    => handleIOError(broker, error)
    }
  }

  def handleData(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    log.debug(s"Try to add ${data.shortHex}")
    blockFlow.contains(data.hash) match {
      case Right(true) =>
        log.debug(s"Data for ${data.chainIndex} already exists") // TODO: DoS prevention
        handleReadies(broker, origin, pending => Right(pending.parentHash == data.hash))
      case Right(false) =>
        validator.validate(data, blockFlow) match {
          case Left(Left(e))                    => handleIOError(broker, e)
          case Left(Right(MissingDeps(hashes))) => handleMissingDeps(data, hashes, broker, origin)
          case Left(Right(x: InvalidStatus))    => handleInvalidData(data, broker, x)
          case Right(_)                         => handleValidData(data, broker, origin)
        }
      case Left(error) => handleIOError(broker, error)
    }
  }

  def handlePending(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    assume(!blockFlow.containsUnsafe(data.hash))
    val validationResult = validator.validateAfterDependencies(data, blockFlow)
    validationResult match {
      case Left(Left(e))  => handleIOError(broker, e)
      case Left(Right(x)) => handleInvalidData(data, broker, x)
      case Right(_)       => handleValidData(data, broker, origin)
    }
  }

  def handleIOError(broker: ActorRefT[ChainHandler.Event], error: IOError): Unit = {
    log.error(s"IO failed in block/header validation: ${error.toString}")
    feedbackAndClear(broker, dataAddingFailed())
  }

  def handleMissingDeps(data: T,
                        hashes: AVector[BlockHash],
                        broker: ActorRefT[ChainHandler.Event],
                        origin: DataOrigin): Unit = {
    log.debug(s"${data.shortHex} missing deps: ${Utils.showDigest(hashes)}")
    val missings = mutable.HashSet.from(hashes.toIterable)
    pendingToFlowHandler(data, missings, broker, origin, ActorRefT[Command](self))
  }

  def handleInvalidData(data: T,
                        broker: ActorRefT[ChainHandler.Event],
                        status: InvalidStatus): Unit = {
    log.error(s"Failed in validate ${data.shortHex}: $status")
    feedbackAndClear(broker, dataInvalid(data))
  }

  def handleValidData(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    log.debug(s"${data.shortHex} is validated")
    logInfo(data)
    escapeIOError(blockFlow.isRecent(data)) { isRecent =>
      if (isRecent) {
        broadcast(data, origin)
      }
      addToFlowHandler(data, broker, origin)
    }
  }

  def handleDataAdded(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    removeTask(broker, data)
    handleReadies(broker, origin, pending => Right(pending.parentHash == data.hash))
  }

  def feedbackAndClear(broker: ActorRefT[ChainHandler.Event], event: Event): Unit = {
    remove(broker)
    broker ! event
  }

  def logInfo(data: T): Unit = {
    val elapsed = (TimeStamp.now() -- data.timestamp).getOrElse(Duration.zero)
    log.info(s"Potentially new block/header: ${data.shortHex}; elapsed: $elapsed")
  }

  def broadcast(data: T, origin: DataOrigin): Unit

  def addToFlowHandler(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit

  def pendingToFlowHandler(data: T,
                           missings: mutable.HashSet[BlockHash],
                           broker: ActorRefT[ChainHandler.Event],
                           origin: DataOrigin,
                           self: ActorRefT[Command]): Unit

  def dataAddedEvent(data: T): Event

  def dataAddingFailed(): Event

  def dataInvalid(data: T): Event
}

abstract class ChainHandlerState[T <: FlowData: ClassTag] {
  import ChainHandler.Event

  private val tasks = mutable.HashMap.empty[ActorRefT[ChainHandler.Event], Forest[BlockHash, T]]

  def addTasks(broker: ActorRefT[ChainHandler.Event], forest: Forest[BlockHash, T]): Unit = {
    tasks.get(broker) match {
      case Some(existing) => existing.simpleMerge(forest)
      case None           => tasks(broker) = forest
    }
  }

  def extractReady(broker: ActorRefT[ChainHandler.Event],
                   predict: T => IOResult[Boolean]): IOResult[AVector[T]] = {
    tasks.get(broker) match {
      case Some(forest) =>
        AVector.from(forest.roots.view.map(_.value)).filterE(predict)
      case None => Right(AVector.empty)
    }
  }

  def isProcessing(broker: ActorRefT[ChainHandler.Event], task: BlockHash): Boolean = {
    tasks.get(broker).exists(_.contains(task))
  }

  def removeTask(broker: ActorRefT[ChainHandler.Event], data: T): Unit = {
    tasks(broker).removeRootNode(data.hash)
    broker ! dataAddedEvent(data)
    if (tasks(broker).isEmpty) {
      remove(broker)
    }
  }

  def remove(broker: ActorRefT[ChainHandler.Event]): Unit = {
    tasks.remove(broker)
    ()
  }

  def feedbackAndClear(broker: ActorRefT[ChainHandler.Event], event: Event): Unit

  def dataAddedEvent(data: T): Event
}
