package org.alephium.storage

import org.alephium.constant.Protocol
import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.protocol.model.{Block, Transaction, TxInput}

// consider single chain for the moment
class BlockPool() {
  val blockStore = collection.mutable.HashMap.empty[Keccak256, Block]
  val txStore    = collection.mutable.HashMap.empty[Keccak256, Transaction]

  def addBlock(block: Block): Unit = {
    blockStore += block.hash -> block
    block.transactions.foreach { tx =>
      txStore += tx.hash -> tx
    }
  }

  addBlock(Protocol.Genesis.block)

  def addBlocks(blocks: Seq[Block]): Unit = {
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

  private def getTxInputValue(transaction: Transaction, address: ED25519PublicKey): Int = {
    transaction.unsigned.inputs.map {
      case TxInput(txHash, outputIndex) =>
        val tx       = txStore(txHash)
        val txOutput = tx.unsigned.outputs(outputIndex)
        if (txOutput.publicKey == address) txOutput.value else 0
    }.sum
  }
  private def getTxOutputValue(transaction: Transaction, address: ED25519PublicKey): Int = {
    transaction.unsigned.outputs.map { txOutput =>
      if (txOutput.publicKey == address) txOutput.value else 0
    }.sum
  }

  private def getBalance(transaction: Transaction, address: ED25519PublicKey): Int = {
    getTxOutputValue(transaction, address) - getTxInputValue(transaction, address)
  }

  private def getBalance(block: Block, address: ED25519PublicKey): Int = {
    block.transactions.map(transaction => getBalance(transaction, address)).sum
  }

  def getBestHeader: Block = {
    require(blockStore.nonEmpty)
    blockStore.values.maxBy(getHeight)
  }

  def getBestChain: Seq[Block] = {
    getChain(getBestHeader)
  }

  def getUTXOs(address: ED25519PublicKey): Seq[TxInput] = {
    val bestChain = getBestChain
    for {
      block             <- bestChain
      transaction       <- block.transactions
      (txOutput, index) <- transaction.unsigned.outputs.zipWithIndex
      if txOutput.publicKey == address
    } yield TxInput(transaction.hash, index)
  }

  // return a number of inputs with at lease value Aleph
  def getUTXOs(address: ED25519PublicKey, value: Int): Option[(Seq[TxInput], Int)] = {
    val utxos = getUTXOs(address)
    val values = utxos.scanLeft(0) {
      case (acc, TxInput(txHash, outputIndex)) =>
        val tx       = txStore(txHash)
        val txOutput = tx.unsigned.outputs(outputIndex)
        acc + txOutput.value
    }
    val index = values.tail.indexWhere(_ >= value)
    if (index == -1) None else Some((utxos.take(index + 1), values(index)))
  }

  // calculated from best chain
  def getBalance(address: ED25519PublicKey): (Block, Int) = {
    (getBestHeader, getBestChain.map(block => getBalance(block, address)).sum)
  }
}
