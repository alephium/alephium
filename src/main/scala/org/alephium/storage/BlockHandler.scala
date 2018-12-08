package org.alephium.storage

import java.net.InetSocketAddress

import akka.actor.Props
import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.network.PeerManager
import org.alephium.protocol.Genesis
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
  case class SendBlocksAfter(locators: Seq[Keccak256], blocks: Seq[Block])   extends Event
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

  override def receive: Receive = {
    case AddBlocks(blocks) =>
      blockPool.addBlocks(blocks)
      log.debug(s"Add ${blocks.size} blocks, now the height is ${blockPool.getHeight}")
    case GetBlocksAfter(locators) =>
      val newBlocks = blockPool.getBlocks(locators)
      sender() ! SendBlocksAfter(locators, newBlocks)
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
      // TODO: use most recent confirmed hashes as locators
      val block = blockPool.getBestHeader
      sender() ! PeerManager.Sync(remote, Seq(block.hash))
  }
}
