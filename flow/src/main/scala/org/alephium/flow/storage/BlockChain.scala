package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.constant.Consensus
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.util.AVector

import scala.annotation.tailrec
import scala.collection.mutable.HashMap

trait BlockChain extends BlockPool with BlockHashChain {

  protected val blocksTable: HashMap[Keccak256, Block]             = HashMap.empty
  protected val transactionsTable: HashMap[Keccak256, Transaction] = HashMap.empty

  override def numTransactions: Int = transactionsTable.size

  override def getTransaction(hash: Keccak256): Transaction = transactionsTable(hash)

  override def getBlock(hash: Keccak256): Block = blocksTable(hash)

  def add(block: Block, parentHash: Keccak256, weight: Int): AddBlockResult = {
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

  // Note: this function is mainly for testing right now
  // TODO: remove this!
  def add(block: Block, weight: Int): AddBlockResult = {
    val deps = block.blockHeader.blockDeps
    add(block, deps.last, weight)
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

  override def getBlockSlice(hash: Keccak256): AVector[Block] = {
    blockHashesTable.get(hash) match {
      case Some(node) =>
        getChain(node).map(n => getBlock(n.blockHash))
      case None =>
        AVector.empty
    }
  }

  private def getChain(node: BlockHashChain.TreeNode): AVector[BlockHashChain.TreeNode] = {
    @tailrec
    def iter(acc: AVector[BlockHashChain.TreeNode],
             current: BlockHashChain.TreeNode): AVector[BlockHashChain.TreeNode] = {
      current match {
        case n: BlockHashChain.Root => acc :+ n
        case n: BlockHashChain.Node => iter(acc :+ current, n.parent)
      }
    }
    iter(AVector.empty, node).reverse
  }

  override def getAllBlocks: Iterable[Block] =
    blockHashesTable.values.map(n => getBlock(n.blockHash))

  def getHashTarget(hash: Keccak256): BigInt = {
    val block     = getBlock(hash)
    val height    = getHeight(hash)
    val refHeight = height - Consensus.retargetInterval
    getConfirmedBlock(refHeight) match {
      case Some(refBlock) =>
        val timeSpan = block.blockHeader.timestamp - refBlock.blockHeader.timestamp
        val retarget = block.blockHeader.target * Consensus.retargetInterval * Consensus.blockTargetTime.toMillis / timeSpan
        retarget
      case None => Consensus.maxMiningTarget
    }
  }

  def show(block: Block): String = {
    val shortHash = block.shortHash
    val weight    = getWeight(block)
    val blockNum  = numBlocks - 1 // exclude genesis block
    val height    = getHeight(block)
    s"Hash: $shortHash; Weight: $weight; Height: $height/$blockNum"
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
}
