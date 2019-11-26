package org.alephium.flow.core

import akka.actor.{ActorRef, Props}

import org.alephium.crypto.Keccak256
import org.alephium.flow.Utils
import org.alephium.flow.core.validation.{InvalidHeaderStatus, MissingDeps, Validation, ValidHeader}
import org.alephium.flow.io.IOError
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.{BlockHeader, ChainIndex}
import org.alephium.util.{AVector, BaseActor}

object HeaderChainHandler {
  def props(blockFlow: BlockFlow, chainIndex: ChainIndex, flowHandler: ActorRef)(
      implicit config: PlatformProfile): Props =
    Props(new HeaderChainHandler(blockFlow, chainIndex, flowHandler))

  sealed trait Command
  case class AddHeader(header: BlockHeader, origin: DataOrigin.Remote)
  case class AddPendingHeader(header: BlockHeader, origin: DataOrigin.Remote)
}

class HeaderChainHandler(val blockFlow: BlockFlow,
                         val chainIndex: ChainIndex,
                         flowHandler: ActorRef)(implicit val config: PlatformProfile)
    extends BaseActor
    with ChainHandlerLogger {
  import HeaderChainHandler._

  val chain: BlockHeaderPool = blockFlow.getHeaderChain(chainIndex)

  override def receive: Receive = {
    case AddHeader(header, origin)        => handleHeader(header, origin)
    case AddPendingHeader(header, origin) => handlePending(header, origin)
  }

  def handleHeader(header: BlockHeader, origin: DataOrigin.Remote): Unit = {
    if (blockFlow.contains(header)) {
      // TODO: anti-DoS
      log.debug(s"Header for ${header.chainIndex} already existed")
    } else {
      if (Validation.checkParentAdded(header, blockFlow)) {
        handleNewHeader(header, origin)
      } else {
        log.warning(s"parent header is not included yet, might be DoS")
      }
    }
  }

  def handlePending(header: BlockHeader, origin: DataOrigin.Remote): Unit = {
    assert(!blockFlow.contains(header))
    Validation.validateAfterDependencies(header, blockFlow) match {
      case Left(e)                       => handleIOError(header, e)
      case Right(x: InvalidHeaderStatus) => handleInvalidHeader(header, x, origin)
      case Right(_: ValidHeader.type)    => handleValidHeader(header)
    }
  }

  def handleNewHeader(header: BlockHeader, origin: DataOrigin.Remote): Unit = {
    Validation.validate(header, blockFlow, origin.isSyncing) match {
      case Left(e)                       => handleIOError(header, e)
      case Right(MissingDeps(hashes))    => handleMissingDeps(header, hashes, origin)
      case Right(s: InvalidHeaderStatus) => handleInvalidHeader(header, s, origin)
      case Right(_: ValidHeader.type)    => handleValidHeader(header)
    }
  }

  def handleIOError(header: BlockHeader, error: IOError): Unit = {
    log.debug(s"IO failed in header validation for ${header.shortHex}: ${error.toString}")
  }

  def handleMissingDeps(header: BlockHeader,
                        hashes: AVector[Keccak256],
                        origin: DataOrigin.Remote): Unit = {
    log.debug(s"${header.shortHex} missing depes: ${Utils.show(hashes)}")
    val missings = scala.collection.mutable.HashSet(hashes.toArray: _*)
    flowHandler ! FlowHandler.PendingHeader(header, missings, origin, sender(), self)
  }

  def handleInvalidHeader(header: BlockHeader,
                          status: InvalidHeaderStatus,
                          origin: DataOrigin.Remote): Unit = {
    log.debug(s"Failed in header validation for ${header.shortHex} from $origin: $status")
  }

  def handleValidHeader(header: BlockHeader): Unit = {
    logInfo(header)
    flowHandler.tell(FlowHandler.AddHeader(header), sender())
  }
}
