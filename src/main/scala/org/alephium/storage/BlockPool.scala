package org.alephium.storage

import java.math.BigInteger

import akka.actor.Props
import org.alephium.protocol.Genesis
import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.protocol.model.{Block, Transaction, TxInput}
import org.alephium.util.BaseActor

object BlockPool {

  def props(): Props = Props(new BlockPoolImpl())

  sealed trait Command
  case class AddBlocks(blocks: Seq[Block])                          extends Command
  case object GetBestHeader                                         extends Command
  case object GetBestChain                                          extends Command
  case class GetUTXOs(address: ED25519PublicKey, value: BigInteger) extends Command
  case class GetBalance(address: ED25519PublicKey)                  extends Command

  sealed trait Event
  case class BestHeader(header: Block)                                           extends Event
  case class BestChain(blocks: Seq[Block])                                       extends Event
  case class UTXOs(header: Keccak256, inputs: Seq[TxInput], total: BigInteger)   extends Event
  case object NoEnoughBalance                                                    extends Event
  case class Balance(address: ED25519PublicKey, block: Block, total: BigInteger) extends Event
}

// consider single chain for the moment
class BlockPoolImpl() extends BaseActor {
  import BlockPool._

  private val blockStore = collection.mutable.HashMap.empty[Keccak256, Block]
  private val txStore    = collection.mutable.HashMap.empty[Keccak256, Transaction]

  addBlock(Genesis.block)

  override def receive: Receive = {
    case AddBlocks(blocks) =>
      addBlocks(blocks)
    case GetBestHeader =>
      sender() ! BestHeader(getBestHeader)
    case GetBestChain =>
      sender() ! BestChain(getBestChain)
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

  private def getHeight(block: Block): Int = {
    getChain(block).size
  }

  private def getChain(block: Block): Seq[Block] = {
    if (block.blockHeader.blockDeps.isEmpty) Seq(block)
    else {
      val prevBlockHash = block.blockHeader.blockDeps.head
      val prevBlock     = blockStore(prevBlockHash)
      getChain(prevBlock) :+ block
    }
  }

  private def getTxInputValue(transaction: Transaction, address: ED25519PublicKey): BigInteger = {
    transaction.unsigned.inputs
      .map {
        case TxInput(txHash, outputIndex) =>
          val tx       = txStore(txHash)
          val txOutput = tx.unsigned.outputs(outputIndex)
          if (txOutput.publicKey == address) txOutput.value else BigInteger.ZERO
      }
      .foldLeft(BigInteger.ZERO)(_ add _)
  }

  private def getTxOutputValue(transaction: Transaction, address: ED25519PublicKey): BigInteger = {
    transaction.unsigned.outputs
      .filter(_.publicKey == address)
      .foldLeft(BigInteger.ZERO)(_ add _.value)
  }

  private def getBalance(transaction: Transaction, address: ED25519PublicKey): BigInteger = {
    getTxOutputValue(transaction, address) subtract getTxInputValue(transaction, address)
  }

  private def getBalance(block: Block, address: ED25519PublicKey): BigInteger = {
    block.transactions
      .map(transaction => getBalance(transaction, address))
      .foldLeft(BigInteger.ZERO)(_ add _)
  }

  private def getBestHeader: Block = {
    require(blockStore.nonEmpty)
    blockStore.values.maxBy(getHeight)
  }

  private def getBestChain: Seq[Block] = {
    getChain(getBestHeader)
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
                       value: BigInteger): Option[(Keccak256, Seq[TxInput], BigInteger)] = {
    val (header, utxos) = getUTXOs(address)
    val values = utxos
      .scanLeft(BigInteger.ZERO) {
        case (acc, TxInput(txHash, outputIndex)) =>
          val tx       = txStore(txHash)
          val txOutput = tx.unsigned.outputs(outputIndex)
          acc add txOutput.value
      }
      .tail
    val index = values.indexWhere(v => (v compareTo value) >= 0)
    if (index == -1) None else Some((header, utxos.take(index + 1), values(index)))
  }

  // calculated from best chain
  private def getBalance(address: ED25519PublicKey): (Block, BigInteger) = {
    val bestHeader = getBestHeader
    val balance =
      getBestChain.map(block => getBalance(block, address)).foldLeft(BigInteger.ZERO)(_ add _)
    (bestHeader, balance)
  }
}
