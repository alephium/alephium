package org.alephium.storage

import org.alephium.protocol.Genesis
import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.protocol.model.{Block, Transaction, TxInput}
import org.alephium.util.UInt

trait BlockPool {
  def addBlock(block: Block): Unit

  def addBlocks(blocks: Seq[Block]): Unit

  def getBestHeader: Block

  def getBestChain: Seq[Block]

  def getUTXOs(address: ED25519PublicKey): Seq[TxInput]

  def getUTXOs(address: ED25519PublicKey, value: UInt): Option[(Seq[TxInput], UInt)]

  def getBalance(address: ED25519PublicKey): (Block, UInt)
}

object BlockPool {
  def apply(): BlockPool = new BlockPoolImpl()
}

// consider single chain for the moment
class BlockPoolImpl() extends BlockPool {
  private val blockStore = collection.mutable.HashMap.empty[Keccak256, Block]
  private val txStore    = collection.mutable.HashMap.empty[Keccak256, Transaction]

  override def addBlock(block: Block): Unit = {
    blockStore += block.hash -> block
    block.transactions.foreach { tx =>
      txStore += tx.hash -> tx
    }
  }

  addBlock(Genesis.block)

  override def addBlocks(blocks: Seq[Block]): Unit = {
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

  private def getTxInputValue(transaction: Transaction, address: ED25519PublicKey): UInt = {
    transaction.unsigned.inputs
      .map {
        case TxInput(txHash, outputIndex) =>
          val tx       = txStore(txHash)
          val txOutput = tx.unsigned.outputs(outputIndex)
          if (txOutput.publicKey == address) txOutput.value else UInt.zero
      }
      .foldLeft(UInt.zero)(_ plus _)
  }

  private def getTxOutputValue(transaction: Transaction, address: ED25519PublicKey): UInt = {
    transaction.unsigned.outputs
      .filter(_.publicKey == address)
      .foldLeft(UInt.zero)(_ plus _.value)
  }

  private def getBalance(transaction: Transaction, address: ED25519PublicKey): UInt = {
    getTxOutputValue(transaction, address) minus getTxInputValue(transaction, address)
  }

  private def getBalance(block: Block, address: ED25519PublicKey): UInt = {
    block.transactions
      .map(transaction => getBalance(transaction, address))
      .foldLeft(UInt.zero)(_ plus _)
  }

  override def getBestHeader: Block = {
    require(blockStore.nonEmpty)
    blockStore.values.maxBy(getHeight)
  }

  override def getBestChain: Seq[Block] = {
    getChain(getBestHeader)
  }

  override def getUTXOs(address: ED25519PublicKey): Seq[TxInput] = {
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
    txosOI diff stxosOI
  }

  // return a number of inputs with at lease value Aleph
  override def getUTXOs(address: ED25519PublicKey, value: UInt): Option[(Seq[TxInput], UInt)] = {
    val utxos = getUTXOs(address)
    val values = utxos
      .scanLeft(UInt.zero) {
        case (acc, TxInput(txHash, outputIndex)) =>
          val tx       = txStore(txHash)
          val txOutput = tx.unsigned.outputs(outputIndex)
          acc plus txOutput.value
      }
      .tail
    val index = values.indexWhere(_ >= value)
    if (index == -1) None else Some((utxos.take(index + 1), values(index)))
  }

  // calculated from best chain
  override def getBalance(address: ED25519PublicKey): (Block, UInt) = {
    val bestHeader = getBestHeader
    val balance =
      getBestChain.map(block => getBalance(block, address)).foldLeft(UInt.zero)(_ plus _)
    (bestHeader, balance)
  }
}
