package org.alephium.storage

import org.alephium.crypto.{ED25519PublicKey, Keccak256}
//import org.alephium.flow.ChainSlice
import org.alephium.protocol.model.{Block, Transaction, TxInput}

trait BlockPool {

  def numBlocks: Int

  def numTransactions: Int

  def maxWeight: Int

  def contains(hash: Keccak256): Boolean
  def contains(block: Block): Boolean = contains(block.hash)

  // Assuming the hash is in the pool
  def getBlock(hash: Keccak256): Block

  def getBlocks(locators: Seq[Keccak256]): Seq[Block] = {
    val blocks = locators.map(getBlocks)
    blocks.foldLeft(Seq.empty[Block]) {
      case (acc, newBlocks) =>
        val toAdd = newBlocks.filterNot(acc.contains)
        acc ++ toAdd
    }
  }

  def getBlocks(locator: Keccak256): Seq[Block]

  def getHeight(hash: Keccak256): Int
  def getHeight(block: Block): Int = getHeight(block.hash)

  def getWeight(hash: Keccak256): Int
  def getWeight(block: Block): Int = getWeight(block.hash)

  // TODO: use ChainSlice instead of Seq[Block]
  def getChainSlice(block: Block): Seq[Block]

  // Assuming the hash or block is in the pool
  def isHeader(hash: Keccak256): Boolean
  def isHeader(block: Block): Boolean = isHeader(block.hash)

  def getBestHeader: Block

  def getBestChain: Seq[Block] = getChainSlice(getBestHeader)

  def getAllHeaders: Seq[Keccak256]

  def getAllBlocks: Iterable[Block]

  def isBefore(hash1: Keccak256, hash2: Keccak256): Boolean

  // TODO: have a safe version
  def getTransaction(hash: Keccak256): Transaction

  def getTxInputValue(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    transaction.unsigned.inputs.map {
      case TxInput(txHash, outputIndex) =>
        val tx       = getTransaction(txHash)
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
          val tx       = getTransaction(txHash)
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
