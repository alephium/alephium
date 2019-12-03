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

  val headerChain = blockFlow.getHashChain(chainIndex)

  def handleDatas(datas: Forest[Keccak256, T], origin: DataOrigin): Unit = {
    if (checkContinuity(datas)) {
      addTasks(sender(), datas)
      val readies = extractReady(sender(), t => blockFlow.contains(t.parentHash))
      readies.foreach(handleData(_, origin))
    } else {
      handleMissingParent(datas, origin)
    }
  }

  def checkContinuity(datas: Forest[Keccak256, T]): Boolean = {
    datas.roots.forall { node =>
      blockFlow.contains(node.value.parentHash) || isProcessing(sender(), node.value.parentHash)
    }
  }

  def handleData(data: T, origin: DataOrigin): Unit = {
    log.debug(s"try to add ${data.shortHex}")
    if (blockFlow.includes(data)) {
      removeTask(sender(), data.hash, origin)
      log.debug(s"Data for ${data.chainIndex} already exists") // TODO: anti-DoS
    } else {
      validator.validate(data, blockFlow, origin.isSyncing) match {
        case Left(e)                    => handleIOError(e)
        case Right(MissingDeps(hashes)) => handleMissingDeps(data, hashes, origin)
        case Right(x: InvalidStatus)    => handleInvalidData(x)
        case Right(s) =>
          assert(s.isInstanceOf[ValidStatus]) // avoid and double check exhaustive matching issues
          handleValidData(data, origin)
      }
    }
  }

  def handleMissingParent(datas: Forest[Keccak256, T], origin: DataOrigin): Unit

  def handlePending(data: T, origin: DataOrigin): Unit = {
    assert(!blockFlow.includes(data))
    val validationResult = validator.validateAfterDependencies(data, blockFlow)
    validationResult match {
      case Left(e)                      => handleIOError(e)
      case Right(x: InvalidBlockStatus) => handleInvalidData(x)
      case Right(s) =>
        assert(s.isInstanceOf[ValidStatus]) // avoid and double check exhaustive matching issues
        handleValidData(data, origin)
    }
  }

  def handleIOError(error: IOError): Unit = {
    log.debug(s"IO failed in block/header validation: ${error.toString}")
    feedbackAndClear(dataAddingFailed())
  }

  def handleMissingDeps(data: T, hashes: AVector[Keccak256], origin: DataOrigin): Unit = {
    log.debug(s"${data.shortHex} missing depes: ${Utils.show(hashes)}")
    val missings = mutable.HashSet(hashes.toArray: _*)
    pendingToFlowHandler(data, missings, origin, sender(), self)
  }

  def handleInvalidData(status: InvalidStatus): Unit = {
    log.debug(s"Failed in validation: $status")
    feedbackAndClear(dataInvalid())
  }

  def handleValidData(data: T, origin: DataOrigin): Unit = {
    logInfo(data)
    broadcast(data, origin)
    addToFlowHandler(data, origin, sender())
    removeTask(sender(), data.hash, origin)
  }

  def feedbackAndClear(event: Event): Unit = {
    remove(sender())
    sender() ! event
  }

  def logInfo(data: T): Unit = {
    val elapsedTime = TimeStamp.now().diff(data.timestamp)
    log.info(s"Potentially new block/header: ${data.shortHex}; elapsed: $elapsedTime")
  }

  def broadcast(data: T, origin: DataOrigin): Unit

  def addToFlowHandler(data: T, origin: DataOrigin, sender: ActorRef): Unit

  def pendingToFlowHandler(data: T,
                           missings: mutable.HashSet[Keccak256],
                           origin: DataOrigin,
                           sender: ActorRef,
                           self: ActorRef): Unit

  def dataAddedEvent(): Event

  def dataAddingFailed(): Event

  def dataInvalid(): Event
}

abstract class ChainHandlerState[T <: FlowData: ClassTag] {
  import ChainHandler.Event

  private val tasks = mutable.HashMap.empty[ActorRef, Forest[Keccak256, T]]

  def addTasks(sender: ActorRef, forest: Forest[Keccak256, T]): Unit = {
    tasks.get(sender) match {
      case Some(existing) => existing.simpleMerge(forest)
      case None           => tasks(sender) = forest
    }
  }

  def extractReady(sender: ActorRef, predict: T => Boolean): AVector[T] = {
    tasks.get(sender) match {
      case Some(forest) =>
        val readies = forest.roots.view.map(_.value).filter(predict).toList
        readies.foreach(t => forest.removeRootNode(t.hash)) // might avoid t.hash later
        AVector.from(readies)
      case None => AVector.empty
    }
  }

  def isProcessing(sender: ActorRef, task: Keccak256): Boolean = {
    tasks.get(sender).map(_.contains(task)) match {
      case Some(bool) => bool
      case None       => false
    }
  }

  def removeTask(sender: ActorRef, hash: Keccak256, origin: DataOrigin): Unit = {
    tasks(sender).removeRootNode(hash)
    if (tasks(sender).isEmpty) {
      origin match {
        case _: DataOrigin.FromClique => feedbackAndClear(dataAddedEvent())
        case _                        => ()
      }
    }
  }

  def remove(sender: ActorRef): Unit = {
    tasks.remove(sender)
    ()
  }

  def feedbackAndClear(event: Event): Unit

  def dataAddedEvent(): Event
}
