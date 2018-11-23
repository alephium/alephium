package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{Block, BlockHeader, Transaction}
import org.alephium.util.AVector

import scala.collection.mutable.HashMap

trait BlockChain extends BlockPool with BlockHeaderPool with BlockHashChain {

  /* BlockHeader apis */

  // Assuming the entity is in the pool
  def getBlockHeader(hash: Keccak256): BlockHeader = {
    getBlock(hash).header
  }

  def add(header: BlockHeader, weight: Int): Unit = {
    assert(false) // not allowed
  }

  def add(header: BlockHeader, parentHash: Keccak256, weight: Int): Unit = {
    assert(false) // not allowed
  }

  def getHeadersAfter(locator: Keccak256): AVector[BlockHeader] =
    getBlocksAfter(locator).map(_.header)

  /* BlockChain apis */

  protected val blocksTable: HashMap[Keccak256, Block]             = HashMap.empty
  protected val transactionsTable: HashMap[Keccak256, Transaction] = HashMap.empty

  def numTransactions: Int = transactionsTable.size

  def getTransaction(hash: Keccak256): Transaction = transactionsTable(hash)

  def getBlock(hash: Keccak256): Block = blocksTable(hash)

  def add(block: Block, weight: Int): Unit = {
    add(block, block.parentHash, weight)
  }

  def add(block: Block, parentHash: Keccak256, weight: Int): Unit = {
    assert(!contains(block.hash) && contains(parentHash))
    val parent = blockHashesTable(parentHash)
    addHash(block.hash, parent, weight)
    addBlock(block)
  }

  protected def addBlock(block: Block): Unit = {
    blocksTable += block.hash -> block
    // TODO: handle transactions later
//    block.transactions.foreach { transaction =>
//      transactionsTable += transaction.hash -> transaction
//    }
  }

  def getConfirmedBlock(height: Int): Option[Block] = {
    getConfirmedHash(height).map(getBlock)
  }

  def getBlocksAfter(locator: Keccak256): AVector[Block] =
    getHashesAfter(locator).map(getBlock)

  def getHashTarget(hash: Keccak256): BigInt = {
    val block     = getBlock(hash)
    val height    = getHeight(hash)
    val refHeight = height - config.retargetInterval
    getConfirmedBlock(refHeight) match {
      case Some(refBlock) =>
        val timeSpan = block.header.timestamp - refBlock.header.timestamp
        val retarget = block.header.target * config.retargetInterval * config.blockTargetTime.toMillis / timeSpan
        retarget
      case None => config.maxMiningTarget
    }
  }
}

object BlockChain {

  def fromGenesis(genesis: Block)(implicit config: PlatformConfig): BlockChain =
    apply(genesis, 0, 0)

  def apply(rootBlock: Block, initialHeight: Int, initialWeight: Int)(
      implicit _config: PlatformConfig): BlockChain = {

    val rootNode = BlockHashChain.Root(rootBlock.hash, initialHeight, initialWeight)

    new BlockChain {
      override implicit val config: PlatformConfig     = _config
      override protected def root: BlockHashChain.Root = rootNode

      this.addNode(rootNode)
      this.addBlock(rootBlock)
    }
  }
}
