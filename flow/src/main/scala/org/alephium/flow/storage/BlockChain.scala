package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.util.AVector

import scala.collection.mutable.HashMap

trait BlockChain extends BlockPool with BlockHashChain {

  protected val blocksTable: HashMap[Keccak256, Block]             = HashMap.empty
  protected val transactionsTable: HashMap[Keccak256, Transaction] = HashMap.empty

  override def numTransactions: Int = transactionsTable.size

  override def getTransaction(hash: Keccak256): Transaction = transactionsTable(hash)

  override def getBlock(hash: Keccak256): Block = blocksTable(hash)

  override def add(block: Block, weight: Int): AddBlockResult = {
    add(block, block.parentHash, weight)
  }

  override def add(block: Block, parentHash: Keccak256, weight: Int): AddBlockResult = {
    blockHashesTable.get(block.hash) match {
      case Some(_) => AddBlockResult.AlreadyExisted
      case None =>
        blockHashesTable.get(parentHash) match {
          case Some(parent) =>
            val newNode = BlockHashChain.Node(block.hash, parent, parent.height + 1, weight)
            parent.successors += newNode
            _add(newNode, block)
            AddBlockResult.Success
          case None =>
            AddBlockResult.MissingDeps(AVector(parentHash))
        }
    }
  }

  private def _add(node: BlockHashChain.Node, block: Block): Unit = {
    addNode(node)
    addBlock(block)
  }

  private def addBlock(block: Block): Unit = {
    blocksTable += block.hash -> block

    block.transactions.foreach { transaction =>
      transactionsTable += transaction.hash -> transaction
    }
  }

  def getConfirmedBlock(height: Int): Option[Block] = {
    getConfirmedHash(height).map(getBlock)
  }

  override def getBlocks(locator: Keccak256): AVector[Block] = {
    blockHashesTable.get(locator) match {
      case Some(node) => getBlocksAfter(node)
      case None       => AVector.empty[Block]
    }
  }

  private def getBlocksAfter(node: BlockHashChain.TreeNode): AVector[Block] = {
    if (node.isLeaf) AVector.empty[Block]
    else {
      val buffer = node.successors.foldLeft(node.successors.map(n => getBlock(n.blockHash))) {
        case (blocks, successor) =>
          blocks ++ getBlocksAfter(successor).toIterable
      }
      AVector.from(buffer)
    }
  }

  def getHashTarget(hash: Keccak256): BigInt = {
    val block     = getBlock(hash)
    val height    = getHeight(hash)
    val refHeight = height - config.retargetInterval
    getConfirmedBlock(refHeight) match {
      case Some(refBlock) =>
        val timeSpan = block.blockHeader.timestamp - refBlock.blockHeader.timestamp
        val retarget = block.blockHeader.target * config.retargetInterval * config.blockTargetTime.toMillis / timeSpan
        retarget
      case None => config.maxMiningTarget
    }
  }

  def show(block: Block): String = {
    val shortHash = block.shortHash
    val weight    = getWeight(block)
    val blockNum  = numHashes - 1 // exclude genesis block
    val height    = getHeight(block)
    s"Hash: $shortHash; Weight: $weight; Height: $height/$blockNum"
  }
}

object BlockChain {

  def fromGenesis(genesis: Block)(implicit config: PlatformConfig): BlockChain =
    apply(genesis, 0, 0)

  def apply(rootBlock: Block, initialHeight: Int, initialWeight: Int)(
      implicit _config: PlatformConfig): BlockChain = {

    val rootNode = BlockHashChain.Root(rootBlock.hash, initialHeight, initialWeight)

    new BlockChain {
      override implicit val config = _config

      override def root: BlockHashChain.Root = rootNode

      val hash = root.blockHash
      blocksTable += hash -> rootBlock
      addNode(rootNode)
    }
  }
}
