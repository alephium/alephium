package org.alephium.storage

import java.net.InetSocketAddress

import akka.actor.Props
import org.alephium.protocol.Genesis
import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.network.PeerManager
import org.alephium.protocol.model.{Block, Transaction, TxInput}
import org.alephium.util.BaseActor

object BlockPool {

  def props(): Props = Props(new BlockPool())

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
class BlockPool() extends BaseActor {
  import BlockPool._

  private val blockStore = collection.mutable.HashMap.empty[Keccak256, Block]
  private val txStore    = collection.mutable.HashMap.empty[Keccak256, Transaction]

  addBlock(Genesis.block)

  override def receive: Receive = {
    case AddBlocks(blocks) =>
      addBlocks(blocks)
    case GetBlocksAfter(locators) =>
      val newBlocks = getBlocks(locators)
      sender() ! SendBlocksAfter(locators, newBlocks)
    case GetBestHeader =>
      sender() ! BestHeader(getBestHeader)
    case GetBestChain =>
      sender() ! BestChain(getBestChain)
    case GetAllHeaders =>
      sender() ! AllHeaders(getAllHeaders)
    case GetUTXOs(address, value) =>
      getUTXOs(address, value) match {
        case Some((header, inputs, total)) =>
          sender() ! UTXOs(header, inputs, total)
        case None =>
          sender() ! NoEnoughBalance
      }
    case GetBalance(address) =>
      val (block, total) = getBalance(address)
      sender() ! Balance(address, block, total)
    case PrepareSync(remote: InetSocketAddress) =>
      // TODO: use most recent confirmed hashes as locators
      val block = getBestHeader
      sender() ! PeerManager.Sync(remote, Seq(block.hash))
  }

  private def addBlock(block: Block): Unit = {
    blockStore += block.hash -> block
    block.transactions.foreach { tx =>
      txStore += tx.hash -> tx
    }
  }

  private def addBlocks(blocks: Seq[Block]): Unit = {
    blocks.foreach(addBlock)
  }

  private def getBlocks(locators: Seq[Keccak256]): Seq[Block] = {
    val bestChain = getBestChain
    val index     = bestChain.lastIndexWhere(block => locators.contains(block.prevBlockHash))
    bestChain.drop(index)
  }

  private def getHeight(block: Block): Int = {
    getChain(block).size
  }

  private def getChain(block: Block): Seq[Block] = {
    if (block.prevBlockHash == Keccak256.zero) Seq(block)
    else {
      val prevBlock = blockStore(block.prevBlockHash)
      getChain(prevBlock) :+ block
    }
  }

  private def isHeader(block: Block): Boolean = {
    blockStore.contains(block.hash) &&
    !blockStore.values.exists(_.prevBlockHash == block.hash)
  }

  private def getTxInputValue(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    transaction.unsigned.inputs.map {
      case TxInput(txHash, outputIndex) =>
        val tx       = txStore(txHash)
        val txOutput = tx.unsigned.outputs(outputIndex)
        if (txOutput.publicKey == address) txOutput.value else BigInt(0)
    }.sum
  }

  private def getTxOutputValue(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    transaction.unsigned.outputs.filter(_.publicKey == address).map(_.value).sum
  }

  private def getBalance(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    getTxOutputValue(transaction, address) - getTxInputValue(transaction, address)
  }

  private def getBalance(block: Block, address: ED25519PublicKey): BigInt = {
    block.transactions.map(transaction => getBalance(transaction, address)).sum
  }

  private def getBestHeader: Block = {
    require(blockStore.nonEmpty)
    blockStore.values.maxBy(getHeight)
  }

  private def getBestChain: Seq[Block] = {
    getChain(getBestHeader)
  }

  private def getAllHeaders: Seq[Keccak256] = {
    blockStore.values.filter(isHeader).map(_.hash).toSeq
  }

  private def getUTXOs(address: ED25519PublicKey): (Keccak256, Seq[TxInput]) = {
    val bestChain = getBestChain
    val txosOI = for {
      block             <- bestChain
      transaction       <- block.transactions
      (txOutput, index) <- transaction.unsigned.outputs.zipWithIndex
      if txOutput.publicKey == address
    } yield TxInput(transaction.hash, index)
    val stxosOI = for {
      block       <- bestChain
      transaction <- block.transactions
      txInput     <- transaction.unsigned.inputs
    } yield txInput
    (bestChain.last.hash, txosOI diff stxosOI)
  }

  // return a number of inputs with at lease value Aleph
  private def getUTXOs(address: ED25519PublicKey,
                       value: BigInt): Option[(Keccak256, Seq[TxInput], BigInt)] = {
    val (header, utxos) = getUTXOs(address)
    val values = utxos
      .scanLeft(BigInt(0)) {
        case (acc, TxInput(txHash, outputIndex)) =>
          val tx       = txStore(txHash)
          val txOutput = tx.unsigned.outputs(outputIndex)
          acc + txOutput.value
      }
      .tail
    val index = values.indexWhere(_ >= value)
    if (index == -1) None else Some((header, utxos.take(index + 1), values(index)))
  }

  // calculated from best chain
  private def getBalance(address: ED25519PublicKey): (Block, BigInt) = {
    val bestHeader = getBestHeader
    val balance    = getBestChain.map(block => getBalance(block, address)).sum
    (bestHeader, balance)
  }
}
