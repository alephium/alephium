package org.alephium.flow.core

import scala.collection.mutable
import scala.reflect.ClassTag

import akka.actor.ActorRef

import org.alephium.crypto.Keccak256
import org.alephium.flow.Utils
import org.alephium.flow.core.validation._
import org.alephium.flow.io.IOError
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.{ChainIndex, FlowData}
import org.alephium.util.{AVector, BaseActor, Forest, TimeStamp}

object ChainHandler {
  trait Event
}

abstract class ChainHandler[T <: FlowData: ClassTag, S <: ValidationStatus](
    blockFlow: BlockFlow,
    val chainIndex: ChainIndex,
    validator: Validation[T, S])(implicit config: PlatformProfile)
    extends ChainHandlerState[T]
    with BaseActor {
  import ChainHandler.Event

  def handleDatas(datas: Forest[Keccak256, T], broker: ActorRef, origin: DataOrigin): Unit = {
    if (checkContinuity(datas, broker)) {
      addTasks(broker, datas)
      handleReadies(broker, origin, t => blockFlow.contains(t.parentHash))
    } else {
      handleMissingParent(datas, broker, origin)
    }
  }

  def checkContinuity(datas: Forest[Keccak256, T], broker: ActorRef): Boolean = {
    datas.roots.forall { node =>
      blockFlow.contains(node.value.parentHash) || isProcessing(broker, node.value.parentHash)
    }
  }

  def handleReadies(broker: ActorRef, origin: DataOrigin, predict: T => Boolean): Unit = {
    val readies = extractReady(broker, predict)
    readies.foreach(handleData(_, broker, origin))
  }

  def handleData(data: T, broker: ActorRef, origin: DataOrigin): Unit = {
    log.debug(s"try to add ${data.shortHex}")
    if (blockFlow.includes(data)) {
      log.debug(s"Data for ${data.chainIndex} already exists") // TODO: DoS prevention
      removeTask(broker, data.hash, origin)
      handleReadies(broker, origin, _.parentHash == data.hash)
    } else {
      validator.validate(data, blockFlow, origin.isSyncing) match {
        case Left(e)                    => handleIOError(broker, e)
        case Right(MissingDeps(hashes)) => handleMissingDeps(data, hashes, broker, origin)
        case Right(x: InvalidStatus)    => handleInvalidData(broker, x)
        case Right(s) =>
          assert(s.isInstanceOf[ValidStatus]) // avoid and double check exhaustive matching issues
          handleValidData(data, broker, origin)
      }
    }
  }

  def handleMissingParent(datas: Forest[Keccak256, T], broker: ActorRef, origin: DataOrigin): Unit

  def handlePending(data: T, broker: ActorRef, origin: DataOrigin): Unit = {
    assert(!blockFlow.includes(data))
    val validationResult = validator.validateAfterDependencies(data, blockFlow)
    validationResult match {
      case Left(e)                      => handleIOError(broker, e)
      case Right(x: InvalidBlockStatus) => handleInvalidData(broker, x)
      case Right(s) =>
        assert(s.isInstanceOf[ValidStatus]) // avoid and double check exhaustive matching issues
        handleValidData(data, broker, origin)
    }
  }

  def handleIOError(broker: ActorRef, error: IOError): Unit = {
    log.debug(s"IO failed in block/header validation: ${error.toString}")
    feedbackAndClear(broker, dataAddingFailed())
  }

  def handleMissingDeps(data: T,
                        hashes: AVector[Keccak256],
                        broker: ActorRef,
                        origin: DataOrigin): Unit = {
    log.debug(s"${data.shortHex} missing depes: ${Utils.show(hashes)}")
    val missings = mutable.HashSet(hashes.toArray: _*)
    pendingToFlowHandler(data, missings, broker, origin, self)
  }

  def handleInvalidData(broker: ActorRef, status: InvalidStatus): Unit = {
    log.debug(s"Failed in validation: $status")
    feedbackAndClear(broker, dataInvalid())
  }

  def handleValidData(data: T, broker: ActorRef, origin: DataOrigin): Unit = {
    log.debug(s"${data.shortHex} is validated")
    logInfo(data)
    broadcast(data, origin)
    addToFlowHandler(data, broker, origin)
  }

  def handleDataAdded(data: T, broker: ActorRef, origin: DataOrigin): Unit = {
    removeTask(broker, data.hash, origin)
    handleReadies(broker, origin, _.parentHash == data.hash)
  }

  def feedbackAndClear(broker: ActorRef, event: Event): Unit = {
    remove(broker)
    broker ! event
  }

  def logInfo(data: T): Unit = {
    val elapsedTime = TimeStamp.now().millis - data.timestamp.millis
    log.info(s"Potentially new block/header: ${data.shortHex}; elapsed: ${elapsedTime}ms")
  }

  def broadcast(data: T, origin: DataOrigin): Unit

  def addToFlowHandler(data: T, broker: ActorRef, origin: DataOrigin): Unit

  def pendingToFlowHandler(data: T,
                           missings: mutable.HashSet[Keccak256],
                           broker: ActorRef,
                           origin: DataOrigin,
                           self: ActorRef): Unit

  def dataAddedEvent(): Event

  def dataAddingFailed(): Event

  def dataInvalid(): Event
}

abstract class ChainHandlerState[T <: FlowData: ClassTag] {
  import ChainHandler.Event

  private val tasks = mutable.HashMap.empty[ActorRef, Forest[Keccak256, T]]

  def addTasks(broker: ActorRef, forest: Forest[Keccak256, T]): Unit = {
    tasks.get(broker) match {
      case Some(existing) => existing.simpleMerge(forest)
      case None           => tasks(broker) = forest
    }
  }

  def extractReady(broker: ActorRef, predict: T => Boolean): AVector[T] = {
    tasks.get(broker) match {
      case Some(forest) =>
        val readies = forest.roots.view.map(_.value).filter(predict)
        AVector.unsafe(readies.toArray)
      case None => AVector.empty
    }
  }

  def isProcessing(broker: ActorRef, task: Keccak256): Boolean = {
    tasks.get(broker).exists(_.contains(task))
  }

  def removeTask(broker: ActorRef, hash: Keccak256, origin: DataOrigin): Unit = {
    tasks(broker).removeRootNode(hash)
    if (tasks(broker).isEmpty) {
      origin match {
        case _: DataOrigin.FromClique => feedbackAndClear(broker, dataAddedEvent())
        case _                        => ()
      }
    }
  }

  def remove(broker: ActorRef): Unit = {
    tasks.remove(broker)
    ()
  }

  def feedbackAndClear(broker: ActorRef, event: Event): Unit

  def dataAddedEvent(): Event
}
