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
import org.alephium.util.{AVector, BaseActor, TimeStamp}

object ChainHandler {
  trait Event
}

abstract class ChainHandler[T <: FlowData, S <: ValidationStatus](
    blockFlow: BlockFlow,
    val chainIndex: ChainIndex,
    validator: Validation[T, S])(implicit config: PlatformProfile)
    extends BaseActor {
  import ChainHandler.Event

  val tasks = mutable.HashMap.empty[ActorRef, mutable.HashSet[Keccak256]]

  def handleDatas(datas: AVector[T], origin: DataOrigin): Unit = {
    assert(Validation.checkSubtreeOfDAG(datas))
    log.debug(s"try to add ${Utils.showHashable(datas)}")
    if (Validation.checkParentAdded(datas.head, blockFlow)) {
      tasks += sender() -> (mutable.HashSet.empty ++ datas.map(_.hash).toIterable)
      datas.foreach(handleData(_, origin))
    } else {
      log.warning(s"parent block/header is not included yet, might be DoS")
    }
  }

  def handleData(data: T, origin: DataOrigin): Unit = {
    if (blockFlow.includes(data)) {
      tasks(sender()) -= data.hash
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
    logInfo(data)
    broadcast(data, origin)
    addToFlowHandler(data, origin, sender())
    tasks(sender()) -= data.hash
    if (tasks(sender()).isEmpty) {
      origin match {
        case _: DataOrigin.Remote => sender() ! dataAddedEvent()
        case _                    => ()
      }
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
    tasks(sender()) -= data.hash
    if (tasks(sender()).isEmpty) {
      origin match {
        case _: DataOrigin.Remote => sender() ! dataAddedEvent()
        case _                    => ()
      }
    }
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
