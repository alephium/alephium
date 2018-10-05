package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.{Block, Transaction}

import scala.annotation.tailrec
import scala.collection.mutable.{ArrayBuffer, HashMap}

class ForksTree(root: ForksTree.Root) extends SingleChain {

  private val blocksTable: HashMap[Keccak256, ForksTree.TreeNode] = HashMap.empty
  private val transactionsTable: HashMap[Keccak256, Transaction]  = HashMap.empty

  private def updateTable(node: ForksTree.TreeNode): Unit = {
    blocksTable += node.block.hash -> node
    node.block.transactions.foreach { transaction =>
      transactionsTable += transaction.hash -> transaction
    }
  }

  private def postOrderTraverse(f: ForksTree.TreeNode => Unit): Unit = {
    def iter(node: ForksTree.TreeNode): Unit = {
      if (!node.isLeaf) node.successors.foreach(iter)
      f(node)
    }
    iter(root)
  }

  // Initialization
  {
    postOrderTraverse(updateTable)
  }

  override def numBlocks: Int = blocksTable.size

  override def numTransactions: Int = transactionsTable.size

  override def maxHeight: Int = blocksTable.values.map(_.height).max

  override def maxWeight: Int = blocksTable.values.map(_.weight).max

  override def contains(hash: Keccak256): Boolean = blocksTable.contains(hash)

  override def add(block: Block, parentHash: Keccak256, weight: Int): AddBlockResult = {
    blocksTable.get(block.hash) match {
      case Some(_) => AddBlockResult.AlreadyExisted
      case None =>
        blocksTable.get(parentHash) match {
          case Some(parent) =>
            val newNode = ForksTree.Node(block, parent, parent.height + 1, weight)
            parent.successors += newNode
            updateTable(newNode)
            AddBlockResult.Success
          case None =>
            AddBlockResult.MissingDeps(Seq(parentHash))
        }
    }
  }

  override def getBlock(hash: Keccak256): Block = blocksTable(hash).block

  override def getBlocks(locator: Keccak256): Seq[Block] = {
    blocksTable.get(locator) match {
      case Some(node) => getBlocksAfter(node)
      case None       => Seq.empty[Block]
    }
  }

  private def getBlocksAfter(node: ForksTree.TreeNode): Seq[Block] = {
    if (node.isLeaf) Seq.empty[Block]
    else {
      node.successors.foldLeft(node.successors.map(_.block)) {
        case (blocks, successor) =>
          blocks ++ getBlocksAfter(successor)
      }
    }
  }

  override def getHeight(hash: Keccak256): Int = {
    assert(contains(hash))
    blocksTable(hash).height
  }

  override def getWeight(hash: Keccak256): Int = {
    assert(contains(hash))
    blocksTable(hash).weight
  }

  private def getChain(node: ForksTree.TreeNode): Seq[ForksTree.TreeNode] = {
    @tailrec
    def iter(acc: Seq[ForksTree.TreeNode], current: ForksTree.TreeNode): Seq[ForksTree.TreeNode] = {
      current match {
        case n: ForksTree.Root => n +: acc
        case n: ForksTree.Node => iter(current +: acc, n.parent)
      }
    }
    iter(Seq.empty, node)
  }

  override def getBlockSlice(hash: Keccak256): Seq[Block] = {
    blocksTable.get(hash) match {
      case Some(node) =>
        getChain(node).map(_.block)
      case None =>
        Seq.empty
    }
  }

  override def isTip(hash: Keccak256): Boolean = {
    blocksTable.get(hash) match {
      case Some(node) =>
        node.isLeaf
      case None =>
        false
    }
  }

  override def getBestTip: Keccak256 = {
    getAllTips.map(blocksTable.apply).maxBy(_.height).block.hash
  }

  override def getAllTips: Seq[Keccak256] = {
    blocksTable.values.filter(_.isLeaf).map(_.block.hash).toSeq
  }

  override def getAllBlocks: Iterable[Block] = blocksTable.values.map(_.block)

  override def isBefore(hash1: Keccak256, hash2: Keccak256): Boolean = {
    assert(blocksTable.contains(hash1) && blocksTable.contains(hash2))
    val node1 = blocksTable(hash1)
    val node2 = blocksTable(hash2)
    isBefore(node1, node2)
  }

  private def getPredecessor(node: ForksTree.TreeNode, height: Int): ForksTree.TreeNode = {
    @tailrec
    def iter(current: ForksTree.TreeNode): ForksTree.TreeNode = {
      assert(current.height >= height && height >= root.height)
      current match {
        case n: ForksTree.Node =>
          if (n.height == height) {
            current
          } else {
            iter(n.parent)
          }
        case _: ForksTree.Root =>
          assert(height == root.height)
          current
      }
    }

    iter(node)
  }

  private def isBefore(node1: ForksTree.TreeNode, node2: ForksTree.TreeNode): Boolean = {
    val height1 = node1.height
    val height2 = node2.height
    if (height1 < height2) {
      val node1Infer = getPredecessor(node2, node1.height)
      node1Infer.eq(node1)
    } else if (height1 == height2) {
      node1.eq(node2)
    } else false
  }

  override def getTransaction(hash: Keccak256): Transaction = transactionsTable(hash)
}

object ForksTree {

  sealed trait TreeNode {
    val block: Block
    val successors: ArrayBuffer[Node]
    val height: Int
    val weight: Int

    def isRoot: Boolean
    def isLeaf: Boolean = successors.isEmpty
  }

  case class Root(
      block: Block,
      successors: ArrayBuffer[Node],
      height: Int,
      weight: Int
  ) extends TreeNode {
    override def isRoot: Boolean = true
  }

  object Root {
    def apply(block: Block, height: Int, weight: Int): Root =
      Root(block, ArrayBuffer.empty, height, weight)
  }

  case class Node(
      block: Block,
      parent: TreeNode,
      successors: ArrayBuffer[Node],
      height: Int,
      weight: Int
  ) extends TreeNode {
    def isRoot: Boolean = false
  }

  object Node {
    def apply(block: Block, parent: TreeNode, height: Int, weight: Int): Node = {
      new Node(block, parent, ArrayBuffer.empty, height, weight)
    }
  }

  def apply(genesis: Block): ForksTree = apply(genesis, 0, 0)

  def apply(genesis: Block, initialHeight: Int, initialWeight: Int): ForksTree = {
    val root = Root(genesis, initialHeight, initialWeight)
    new ForksTree(root)
  }
}
