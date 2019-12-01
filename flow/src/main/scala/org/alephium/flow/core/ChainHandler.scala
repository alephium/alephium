package org.alephium.flow.core

import scala.collection.mutable

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

abstract class ChainHandler[T <: FlowData, S <: ValidationStatus](
    blockFlow: BlockFlow,
    val chainIndex: ChainIndex,
    validator: Validation[T, S])(implicit config: PlatformProfile)
    extends BaseActor
    with ChainHandlerState[T] {
  import ChainHandler.Event

  val headerChain = blockFlow.getHashChain(chainIndex)

  def handleDatas(datas: Forest[Keccak256, T], origin: DataOrigin): Unit = {
    if (!isProcessing(sender())) {
      if (checkContinuity(datas)) {
        addTasks(sender(), datas)
        datas.roots.foreach(node => handleData(node.value, origin))
      } else {
        // TODO: take care of DoS attack
        val tips = headerChain.getAllTips
        sender() ! FlowHandler.CurrentTips(tips)
      }
    } else {
      // TODO: take care of DoS attack
      log.debug(s"It's processing messages from the peer, ignore for the moment")
    }
  }

  // either all the earliest blocks's parents are added into blockflow
  def checkContinuity(datas: Forest[Keccak256, T]): Boolean = {
    datas.roots.forall(node => blockFlow.contains(node.value.parentHash))
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
    feedback(dataAddingFailed())
  }

  def handleMissingDeps(data: T, hashes: AVector[Keccak256], origin: DataOrigin): Unit = {
    log.debug(s"${data.shortHex} missing depes: ${Utils.show(hashes)}")
    val missings = mutable.HashSet(hashes.toArray: _*)
    pendingToFlowHandler(data, missings, origin, sender(), self)
  }

  def handleInvalidData(status: InvalidStatus): Unit = {
    log.debug(s"Failed in validation: $status")
    feedback(dataInvalid())
  }

  def handleValidData(data: T, origin: DataOrigin): Unit = {
    logInfo(data)
    broadcast(data, origin)
    addToFlowHandler(data, origin, sender())
    removeTask(sender(), data.hash, origin)
  }

  def feedback(event: Event): Unit = {
    tasks -= sender()
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

trait ChainHandlerState[T <: FlowData] {
  import ChainHandler.Event

  val tasks = mutable.HashMap.empty[ActorRef, Forest[Keccak256, T]]

  def addTasks(sender: ActorRef, forest: Forest[Keccak256, T]): Unit = {
    tasks += sender -> forest
  }

  def isProcessing(sender: ActorRef): Boolean = {
    tasks.contains(sender)
  }

  def removeTask(sender: ActorRef, hash: Keccak256, origin: DataOrigin): Unit = {
    tasks(sender).removeRootNode(hash)
    if (tasks(sender).isEmpty) {
      origin match {
        case _: DataOrigin.Remote => feedback(dataAddedEvent())
        case _                    => ()
      }
    }
  }

  def feedback(event: Event): Unit

  def dataAddedEvent(): Event
}
