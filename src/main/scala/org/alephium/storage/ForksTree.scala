package org.alephium.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.ChainSlice
import org.alephium.protocol.model.{Block, Transaction}

import scala.annotation.tailrec
import scala.collection.mutable.{ArrayBuffer, HashMap}

class ForksTree extends BlockPool {
  private var root: ForksTree.Root = _

  private val blocksTable: HashMap[Keccak256, ForksTree.TreeNode] = HashMap.empty
  private val transactionsTable: HashMap[Keccak256, Transaction]  = HashMap.empty
//  private val orphanBlocksTable: HashMap[Keccak256, Block]        = HashMap.empty

  override def numBlocks: Int = blocksTable.size

  override def numTransactions: Int = transactionsTable.size

  private def postOrderTraverse(f: ForksTree.TreeNode => Unit): Unit = {
    def iter(node: ForksTree.TreeNode): Unit = {
      if (!node.isLeaf) node.successors.foreach(iter)
      f(node)
    }
    iter(root)
  }

  def this(_root: ForksTree.Root) {
    this()
    root = _root
    postOrderTraverse(updateTable)
  }

  def this(slice: ChainSlice) {
    this()
    val blocks = slice.blocks
    root = ForksTree.Root(blocks.head)
    blocksTable += (root.block.hash -> root)
    blocks.tail.foreach(add)
  }

  def weight: Int = root.weight

  private def updateTable(node: ForksTree.TreeNode): Unit = {
    blocksTable += node.block.hash -> node
    node.block.transactions.foreach { transaction =>
      transactionsTable += transaction.hash -> transaction
    }
  }

  @tailrec
  private def updateWeightFrom(node: ForksTree.Node): Unit = {
    val parent       = node.parent
    val parentWeight = parent.weight
    val newWeight    = parent.successors.view.map(_.weight).max + 1
    if (newWeight > parentWeight) {
      parent.weight = newWeight
      parent match {
        case p: ForksTree.Node => updateWeightFrom(p)
        case _: ForksTree.Root => ()
      }
    }
  }

  private def update(node: ForksTree.Node): Unit = {
    updateTable(node)
    updateWeightFrom(node)
  }

  def contains(block: Block): Boolean = blocksTable.contains(block.hash)

  override def add(block: Block): Boolean = {
    blocksTable.get(block.hash) match {
      case Some(_) => false
      case None =>
        blocksTable.get(block.prevBlockHash) match {
          case Some(parent) =>
            val newNode = ForksTree.Node(block, parent)
            parent.successors += newNode
            update(newNode)
            true
          case None =>
            false
        }
    }
  }

  @tailrec
  private def addAfter(parent: ForksTree.TreeNode, blocks: Seq[Block]): Unit = {
    require(blocks.nonEmpty && blocks.head.prevBlockHash == parent.block.hash)
    if (blocks.nonEmpty) {
      val currentBlock = blocks.head
      val restBlocks   = blocks.tail
      val newNode      = ForksTree.Node(currentBlock, parent)
      parent.successors += newNode
      update(newNode)
      addAfter(newNode, restBlocks)
    }
  }

  def add(slice: ChainSlice): Boolean = {
    val uncommittedBlocks = slice.blocks.dropWhile(contains)
    if (uncommittedBlocks.nonEmpty) {
      val firstBlock = uncommittedBlocks.head
      blocksTable.get(firstBlock.prevBlockHash) match {
        case Some(parent) =>
          addAfter(parent, uncommittedBlocks)
          true
        case None =>
          false
      }
    } else false
  }

  private def getNodeHeight(node: ForksTree.TreeNode): Int = {
    @tailrec
    def iter(acc: Int, node: ForksTree.TreeNode): Int = {
      node match {
        case _: ForksTree.Root => acc + 1
        case n: ForksTree.Node => iter(acc + 1, n.parent)
      }
    }
    iter(0, node)
  }

  override def getHeightFor(block: Block): Int = {
    blocksTable.get(block.hash) match {
      case Some(node) =>
        getNodeHeight(node)
      case None =>
        0
    }
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

  override def getChain(block: Block): Seq[Block] = {
    blocksTable.get(block.hash) match {
      case Some(node) =>
        getChain(node).map(_.block)
      case None =>
        Seq.empty
    }
  }

  override def isHeader(block: Block): Boolean = {
    blocksTable.get(block.hash) match {
      case Some(node) =>
        node.isLeaf
      case None =>
        false
    }
  }

  override def getBestHeader: Block = {
    getAllHeaders.map(blocksTable.apply).maxBy(getNodeHeight).block
  }

  override def getAllHeaders: Seq[Keccak256] = {
    blocksTable.values.filter(_.isLeaf).map(_.block.hash).toSeq
  }

  override def getTransaction(hash: Keccak256): Transaction = transactionsTable(hash)

//  def prune(): Unit

//  def extract(): ChainSlice
}

object ForksTree {
  sealed trait TreeNode {
    val block: Block
    val successors: ArrayBuffer[Node]
    var weight: Int

    def isRoot: Boolean
    def isLeaf: Boolean = successors.isEmpty
  }
  case class Root(
      block: Block,
      successors: ArrayBuffer[Node] = ArrayBuffer.empty,
      var weight: Int               = 1
  ) extends TreeNode {
    override def isRoot: Boolean = true
  }
  case class Node(
      block: Block,
      parent: TreeNode,
      successors: ArrayBuffer[Node] = ArrayBuffer.empty,
      var weight: Int               = 1
  ) extends TreeNode {
    def isRoot: Boolean = false
  }

  def apply(genesis: Block): ForksTree = {
    val root = Root(genesis)
    new ForksTree(root)
  }
}
