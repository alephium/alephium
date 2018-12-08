package org.alephium.storage

import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.protocol.model.{Block, Transaction, TxInput}

trait AbsBlockPool {

  def addBlock(block: Block): Unit

  def addBlocks(blocks: Seq[Block]): Unit = {
    blocks.foreach(addBlock)
  }

  def getBlocks(locators: Seq[Keccak256]): Seq[Block] = {
    val bestChain = getBestChain
    val index     = bestChain.lastIndexWhere(block => locators.contains(block.prevBlockHash))
    bestChain.drop(index)
  }

  def getHeight(block: Block): Int = getChain(block).size

  def getChain(block: Block): Seq[Block]

  def isHeader(block: Block): Boolean

  def getBestHeader: Block

  def getBestChain: Seq[Block] = getChain(getBestHeader)

  def getHeight: Int = getBestChain.size

  def getAllHeaders: Seq[Keccak256]
}

class BlockPool extends AbsBlockPool {
  val blockStore = collection.mutable.HashMap.empty[Keccak256, Block]
  val txStore    = collection.mutable.HashMap.empty[Keccak256, Transaction]

  def addBlock(block: Block): Unit = {
    blockStore += block.hash -> block
    block.transactions.foreach { tx =>
      txStore += tx.hash -> tx
    }
  }

  def getChain(block: Block): Seq[Block] = {
    if (block.prevBlockHash == Keccak256.zero) Seq(block)
    else {
      val prevBlock = blockStore(block.prevBlockHash)
      getChain(prevBlock) :+ block
    }
  }

  def isHeader(block: Block): Boolean = {
    blockStore.contains(block.hash) &&
    !blockStore.values.exists(_.prevBlockHash == block.hash)
  }

  def getBestHeader: Block = {
    require(blockStore.nonEmpty)
    blockStore.values.maxBy(getHeight)
  }

  def getAllHeaders: Seq[Keccak256] = {
    blockStore.values.filter(isHeader).map(_.hash).toSeq
  }

  def getTxInputValue(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    transaction.unsigned.inputs.map {
      case TxInput(txHash, outputIndex) =>
        val tx       = txStore(txHash)
        val txOutput = tx.unsigned.outputs(outputIndex)
        if (txOutput.publicKey == address) txOutput.value else BigInt(0)
    }.sum
  }

  def getTxOutputValue(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    transaction.unsigned.outputs.filter(_.publicKey == address).map(_.value).sum
  }

  def getBalance(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    getTxOutputValue(transaction, address) - getTxInputValue(transaction, address)
  }

  def getBalance(block: Block, address: ED25519PublicKey): BigInt = {
    block.transactions.map(transaction => getBalance(transaction, address)).sum
  }

  def getUTXOs(address: ED25519PublicKey): (Keccak256, Seq[TxInput]) = {
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
  def getUTXOs(address: ED25519PublicKey,
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
  def getBalance(address: ED25519PublicKey): (Block, BigInt) = {
    val bestHeader = getBestHeader
    val balance    = getBestChain.map(block => getBalance(block, address)).sum
    (bestHeader, balance)
  }
}
