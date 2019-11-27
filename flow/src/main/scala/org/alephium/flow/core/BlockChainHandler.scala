package org.alephium.flow.core

import scala.collection.mutable

import akka.actor.{ActorRef, Props}

import org.alephium.crypto.Keccak256
import org.alephium.flow.Utils
import org.alephium.flow.core.validation._
import org.alephium.flow.io.IOError
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.message.{Message, SendBlocks, SendHeaders}
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AVector, BaseActor}

object BlockChainHandler {
  def props(blockFlow: BlockFlow,
            chainIndex: ChainIndex,
            cliqueManager: ActorRef,
            flowHandler: ActorRef)(implicit config: PlatformProfile): Props =
    Props(new BlockChainHandler(blockFlow, chainIndex, cliqueManager, flowHandler))

  sealed trait Command
  case class AddBlocks(blocks: AVector[Block], origin: DataOrigin) extends Command
  case class AddPendingBlock(block: Block, origin: DataOrigin)     extends Command

  sealed trait Event
  case class BlocksAdded(chainIndex: ChainIndex) extends Event
  case object BlocksAddingFailed                 extends Event
  case object InvalidBlocks                      extends Event
}

class BlockChainHandler(val blockFlow: BlockFlow,
                        val chainIndex: ChainIndex,
                        cliqueManager: ActorRef,
                        flowHandler: ActorRef)(implicit val config: PlatformProfile)
    extends BaseActor
    with ChainHandlerLogger {
  import BlockChainHandler._

  val chain: BlockPool = blockFlow.getBlockChain(chainIndex)
  val tasks            = mutable.HashMap.empty[ActorRef, mutable.HashSet[Keccak256]]

  override def receive: Receive = {
    case AddBlocks(blocks, origin)      => handleBlocks(blocks, origin)
    case AddPendingBlock(block, origin) => handlePendingBlock(block, origin)
  }

  def handleBlocks(blocks: AVector[Block], origin: DataOrigin): Unit = {
    assert(Validation.checkSubtreeOfDAG(blocks))
    log.debug(s"try to add ${Utils.showHashable(blocks)}")
    if (Validation.checkParentAdded(blocks.head, blockFlow)) {
      tasks += sender() -> (mutable.HashSet.empty ++ blocks.map(_.hash).toIterable)
      blocks.foreach(handleBlock(_, origin))
    } else {
      log.warning(s"parent block is not included yet, might be DoS")
    }
  }

  def handlePendingBlock(block: Block, origin: DataOrigin): Unit = {
    assert(!blockFlow.contains(block))
    val validationResult = Validation.validateAfterDependencies(block, blockFlow)
    validationResult match {
      case Left(e)                      => handleIOError(e)
      case Right(x: InvalidBlockStatus) => handleInvalidBlock(x)
      case Right(_: ValidBlock.type)    => handleValidBlock(block, origin)
    }
  }

  def handleBlock(block: Block, origin: DataOrigin): Unit = {
    if (blockFlow.contains(block)) {
      tasks(sender()) -= block.hash
      // TODO: anti-DoS
      log.debug(s"Block for ${block.chainIndex} already exists")
    } else {
      Validation.validate(block, blockFlow, origin.isSyncing) match {
        case Left(e)                      => handleIOError(e)
        case Right(MissingDeps(hashes))   => handleMissingDeps(block, hashes, origin)
        case Right(x: InvalidBlockStatus) => handleInvalidBlock(x)
        case Right(_: ValidBlock.type)    => handleValidBlock(block, origin)
      }
    }
  }

  def handleValidBlock(block: Block, origin: DataOrigin): Unit = {
    logInfo(block.header)
    broadcast(block, origin)
    flowHandler.tell(FlowHandler.AddBlock(block, origin), sender())
    tasks(sender()) -= block.hash
    if (tasks(sender()).isEmpty) {
      origin match {
        case _: DataOrigin.Remote => sender() ! BlocksAdded(chainIndex)
        case _                    => ()
      }
    }
  }

  def handleIOError(e: IOError): Unit = {
    log.debug(s"IO failed in block validation: ${e.toString}")
    feedback(BlocksAddingFailed)
  }

  def handleMissingDeps(block: Block, hashes: AVector[Keccak256], origin: DataOrigin): Unit = {
    log.debug(s"${block.shortHex} missing depes: ${Utils.show(hashes)}")
    val missings = scala.collection.mutable.HashSet(hashes.toArray: _*)
    flowHandler ! FlowHandler.PendingBlock(block, missings, origin, sender(), self)
  }

  def handleInvalidBlock(status: InvalidBlockStatus): Unit = {
    log.debug(s"Failed in block validation: $status")
    feedback(InvalidBlocks)
  }

  def feedback(event: Event): Unit = {
    tasks -= sender()
    sender() ! event
  }

  def broadcast(block: Block, origin: DataOrigin): Unit = {
    val blockMessage  = Message.serialize(SendBlocks(AVector(block)))
    val headerMessage = Message.serialize(SendHeaders(AVector(block.header)))
    if (config.brokerInfo.contains(block.chainIndex.from)) {
      cliqueManager ! CliqueManager.BroadCastBlock(block, blockMessage, headerMessage, origin)
    }
  }
}
