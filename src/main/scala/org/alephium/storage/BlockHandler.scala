package org.alephium.storage

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.network.PeerManager
import org.alephium.protocol.Genesis
import org.alephium.protocol.message.{Message, SendBlocks}
import org.alephium.protocol.model.{Block, TxInput}
import org.alephium.util.BaseActor

object BlockHandler {

  def props(): Props = Props(new BlockHandler())

  sealed trait Command
  case class AddBlocks(blocks: Seq[Block])                      extends Command
  case class GetBlocksAfter(locators: Seq[Keccak256])           extends Command
  case object GetBestHeader                                     extends Command
  case object GetBestChain                                      extends Command
  case object GetAllHeaders                                     extends Command
  case class GetUTXOs(address: ED25519PublicKey, value: BigInt) extends Command
  case class GetBalance(address: ED25519PublicKey)              extends Command
  case class PrepareSync(remote: InetSocketAddress)             extends Command

  sealed trait Event
//  case class SendBlocksAfter(locators: Seq[Keccak256], blocks: Seq[Block])   extends Event
  case class BestHeader(header: Block)                                       extends Event
  case class BestChain(blocks: Seq[Block])                                   extends Event
  case class AllHeaders(headers: Seq[Keccak256])                             extends Event
  case class UTXOs(header: Keccak256, inputs: Seq[TxInput], total: BigInt)   extends Event
  case object NoEnoughBalance                                                extends Event
  case class Balance(address: ED25519PublicKey, block: Block, total: BigInt) extends Event
}

// consider single chain for the moment
class BlockHandler() extends BaseActor {
  import BlockHandler._

  val blockPool = ForksTree(Genesis.block)

  override def receive: Receive = awaitPeerManager

  def awaitPeerManager: Receive = {
    case PeerManager.Hello => context.become(handleWith(sender()))
  }

  def handleWith(peerManager: ActorRef): Receive = {
    case AddBlocks(blocks) =>
      val ok = blockPool.addBlocks(blocks)
      if (ok) {
        log.debug(
          s"Add ${blocks.size} blocks, #blocks: ${blockPool.numBlocks}, #height: ${blockPool.getHeight}")
        if (blocks.size == 1) {
          log.debug(s"Got new block, broadcast it")
          peerManager ! PeerManager.BroadCast(Message(SendBlocks(blocks)), sender())
        }
      } else {
        log.warning(s"Failed to add a new block")
      }
    case GetBlocksAfter(locators) =>
      val newBlocks = blockPool.getBlocks(locators)
      sender() ! Message(SendBlocks(newBlocks))
    case GetBestHeader =>
      sender() ! BestHeader(blockPool.getBestHeader)
    case GetBestChain =>
      sender() ! BestChain(blockPool.getBestChain)
    case GetAllHeaders =>
      sender() ! AllHeaders(blockPool.getAllHeaders)
    case GetUTXOs(address, value) =>
      blockPool.getUTXOs(address, value) match {
        case Some((header, inputs, total)) =>
          sender() ! UTXOs(header, inputs, total)
        case None =>
          sender() ! NoEnoughBalance
      }
    case GetBalance(address) =>
      val (block, total) = blockPool.getBalance(address)
      sender() ! Balance(address, block, total)
    case PrepareSync(remote: InetSocketAddress) =>
      // TODO: improve sync algorithm
      val headers = blockPool.getAllHeaders
      sender() ! PeerManager.Sync(remote, headers)
  }
}
