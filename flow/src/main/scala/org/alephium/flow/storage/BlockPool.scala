package org.alephium.flow.storage

import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.protocol.model.{Block, BlockHeader, Transaction, TxInput}
import org.alephium.util.AVector

trait BlockPool extends BlockHeaderPool {

  /* BlockHeader apis */

  // Assuming the entity is in the pool
  override def getBlockHeader(hash: Keccak256): BlockHeader = {
    getBlock(hash).blockHeader
  }

  override def add(block: BlockHeader, weight: Int): AddBlockHeaderResult = {
    AddBlockHeaderResult.Other("add blockheader to block pool is not allowed")
  }

  override def add(block: BlockHeader, parentHash: Keccak256, weight: Int): AddBlockHeaderResult = {
    AddBlockHeaderResult.Other("add blockheader to block pool is not allowed")
  }

  /* Block apis */

  def numTransactions: Int

  def contains(block: Block): Boolean = contains(block.hash)

  // Assuming the hash is in the pool
  def getBlock(hash: Keccak256): Block

  def add(block: Block, weight: Int): AddBlockResult

  def add(block: Block, parentHash: Keccak256, weight: Int): AddBlockResult

  def getBlocks(locators: AVector[Keccak256]): AVector[Block] = {
    val blocks = locators.map(getBlocks)
    blocks.fold(AVector.empty[Block]) {
      case (acc, newBlocks) =>
        val toAdd = newBlocks.filterNot(acc.contains)
        acc ++ toAdd
    }
  }

  def getBlocks(locator: Keccak256): AVector[Block]

  def getHeight(block: Block): Int = getHeight(block.hash)

  def getWeight(block: Block): Int = getWeight(block.hash)

  // TODO: use ChainSlice instead of AVector[Block]
  def getBlockSlice(hash: Keccak256): AVector[Block] = {
    getBlockHashSlice(hash).map(getBlock)
  }
  def getBlockSlice(block: Block): AVector[Block] = getBlockSlice(block.hash)

  def isTip(block: Block): Boolean = isTip(block.hash)

  def getBestBlockChain: AVector[Block] = getBlockSlice(getBestTip)

  def getAllBlocks: Iterable[Block] = getAllBlockHashes.map(getBlock)

  // TODO: have a safe version
  def getTransaction(hash: Keccak256): Transaction

  def getTxInputValue(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    transaction.unsigned.inputs.sumBy {
      case TxInput(txHash, outputIndex) =>
        val tx       = getTransaction(txHash)
        val txOutput = tx.unsigned.outputs(outputIndex)
        if (txOutput.publicKey == address) txOutput.value else BigInt(0)
    }
  }

  def getTxOutputValue(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    transaction.unsigned.outputs.filter(_.publicKey == address).sumBy(_.value)
  }

  def getBalance(transaction: Transaction, address: ED25519PublicKey): BigInt = {
    getTxOutputValue(transaction, address) - getTxInputValue(transaction, address)
  }

  def getBalance(block: Block, address: ED25519PublicKey): BigInt = {
    block.transactions.sumBy(transaction => getBalance(transaction, address))
  }

  // calculated from best chain
  def getBalance(address: ED25519PublicKey): (Keccak256, BigInt) = {
    val bestTip = getBestTip
    val balance = getBestBlockChain.sumBy(block => getBalance(block, address))
    (bestTip, balance)
  }
}

sealed trait AddBlockResult

object AddBlockResult {
  case object Success extends AddBlockResult

  trait Failure extends AddBlockResult
  case object AlreadyExisted extends Failure {
    override def toString: String = "Block already exist"
  }
  case class MissingDeps(deps: AVector[Keccak256]) extends Failure {
    override def toString: String = s"Missing #${deps.length - 1} deps"
  }
  case class Other(message: String) extends Failure {
    override def toString: String = s"Failed in adding block: $message"
  }
}
