package org.alephium.flow.handler

import scala.collection.mutable
import scala.reflect.ClassTag

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.validation._
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.platform.PlatformConfig
import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{ChainIndex, FlowData}
import org.alephium.util._

object ChainHandler {
  trait Event
}

abstract class ChainHandler[T <: FlowData: ClassTag, S <: ValidationStatus, Command](
    blockFlow: BlockFlow,
    val chainIndex: ChainIndex,
    validator: Validation[T, S])(implicit config: PlatformConfig)
    extends ChainHandlerState[T]
    with BaseActor {
  import ChainHandler.Event

  def handleDatas(datas: Forest[Hash, T],
                  broker: ActorRefT[ChainHandler.Event],
                  origin: DataOrigin): Unit = {
    checkContinuity(datas, broker) match {
      case Right(true) =>
        addTasks(broker, datas)
        handleReadies(broker, origin, t => blockFlow.contains(t.parentHash))
      case Right(false) =>
        handleMissingParent(datas, broker, origin)
      case Left(error) =>
        handleIOError(broker, error)
    }
  }

  def checkContinuity(datas: Forest[Hash, T],
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
    blockFlow.includes(data) match {
      case Right(true) =>
        log.debug(s"Data for ${data.chainIndex} already exists") // TODO: DoS prevention
        removeTask(broker, data.hash, origin)
        handleReadies(broker, origin, pending => Right(pending.parentHash == data.hash))
      case Right(false) =>
        validator.validate(data, blockFlow) match {
          case Left(e)                    => handleIOError(broker, e)
          case Right(MissingDeps(hashes)) => handleMissingDeps(data, hashes, broker, origin)
          case Right(x: InvalidStatus)    => handleInvalidData(broker, x)
          case Right(_: ValidStatus)      => handleValidData(data, broker, origin)
          case Right(unexpected)          => log.warning(s"Unexpected pattern matching: $unexpected")
        }
      case Left(error) => handleIOError(broker, error)
    }
  }

  def handleMissingParent(datas: Forest[Hash, T],
                          broker: ActorRefT[ChainHandler.Event],
                          origin: DataOrigin): Unit

  def handlePending(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    assume(blockFlow.includes(data).map(!_).getOrElse(false))
    val validationResult = validator.validateAfterDependencies(data, blockFlow)
    validationResult match {
      case Left(e)                      => handleIOError(broker, e)
      case Right(x: InvalidBlockStatus) => handleInvalidData(broker, x)
      case Right(_: ValidStatus)        => handleValidData(data, broker, origin)
      case Right(unexpected)            => log.debug(s"Unexpected pattern matching $unexpected")
    }
  }

  def handleIOError(broker: ActorRefT[ChainHandler.Event], error: IOError): Unit = {
    log.debug(s"IO failed in block/header validation: ${error.toString}")
    feedbackAndClear(broker, dataAddingFailed())
  }

  def handleMissingDeps(data: T,
                        hashes: AVector[Hash],
                        broker: ActorRefT[ChainHandler.Event],
                        origin: DataOrigin): Unit = {
    log.debug(s"${data.shortHex} missing depes: ${Utils.show(hashes)}")
    val missings = mutable.HashSet.from(hashes.toIterable)
    pendingToFlowHandler(data, missings, broker, origin, ActorRefT[Command](self))
  }

  def handleInvalidData(broker: ActorRefT[ChainHandler.Event], status: InvalidStatus): Unit = {
    log.debug(s"Failed in validation: $status")
    feedbackAndClear(broker, dataInvalid())
  }

  def handleValidData(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    log.debug(s"${data.shortHex} is validated")
    logInfo(data)
    broadcast(data, origin)
    addToFlowHandler(data, broker, origin)
  }

  def handleDataAdded(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    removeTask(broker, data.hash, origin)
    handleReadies(broker, origin, pending => Right(pending.parentHash == data.hash))
  }

  def feedbackAndClear(broker: ActorRefT[ChainHandler.Event], event: Event): Unit = {
    remove(broker)
    broker ! event
  }

  def logInfo(data: T): Unit = {
    val elapsedTime = TimeStamp.now().millis - data.timestamp.millis
    log.info(s"Potentially new block/header: ${data.shortHex}; elapsed: ${elapsedTime}ms")
  }

  def broadcast(data: T, origin: DataOrigin): Unit

  def addToFlowHandler(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit

  def pendingToFlowHandler(data: T,
                           missings: mutable.HashSet[Hash],
                           broker: ActorRefT[ChainHandler.Event],
                           origin: DataOrigin,
                           self: ActorRefT[Command]): Unit

  def dataAddedEvent(): Event

  def dataAddingFailed(): Event

  def dataInvalid(): Event
}

abstract class ChainHandlerState[T <: FlowData: ClassTag] {
  import ChainHandler.Event

  private val tasks = mutable.HashMap.empty[ActorRefT[ChainHandler.Event], Forest[Hash, T]]

  def addTasks(broker: ActorRefT[ChainHandler.Event], forest: Forest[Hash, T]): Unit = {
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

  def isProcessing(broker: ActorRefT[ChainHandler.Event], task: Hash): Boolean = {
    tasks.get(broker).exists(_.contains(task))
  }

  def removeTask(broker: ActorRefT[ChainHandler.Event], hash: Hash, origin: DataOrigin): Unit = {
    tasks(broker).removeRootNode(hash)
    if (tasks(broker).isEmpty) {
      origin match {
        case _: DataOrigin.FromClique => feedbackAndClear(broker, dataAddedEvent())
        case _                        => ()
      }
    }
  }

  def remove(broker: ActorRefT[ChainHandler.Event]): Unit = {
    tasks.remove(broker)
    ()
  }

  def feedbackAndClear(broker: ActorRefT[ChainHandler.Event], event: Event): Unit

  def dataAddedEvent(): Event
}
