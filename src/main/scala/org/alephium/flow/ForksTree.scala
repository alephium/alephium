package org.alephium.flow

import org.alephium.crypto.Keccak256
import org.alephium.flow.ForksTree.{Node, Root}
import org.alephium.protocol.model.Block

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class ForksTree {
  var root: ForksTree.Root = _

  val blocksTable: mutable.HashMap[Keccak256, ForksTree.TreeNode] = mutable.HashMap.empty

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
    postOrderTraverse(node => blocksTable += (node.block.hash -> node))
  }

  def this(slice: ChainSlice) {
    this()
    val blocks = slice.blocks
    root = ForksTree.Root(blocks.head)
    blocksTable += (root.block.hash -> root)
    blocks.tail.foreach(add)
  }

  def weight: Int = root.weight

  @tailrec
  private def updateWeightFrom(node: ForksTree.Node): Unit = {
    val parent       = node.parent
    val parentWeight = parent.weight
    val newWeight    = parent.successors.view.map(_.weight).max + 1
    if (newWeight > parentWeight) {
      parent.weight = newWeight
      parent match {
        case p: Node => updateWeightFrom(p)
        case _: Root => ()
      }
    }
  }

  private def update(node: ForksTree.Node): Unit = {
    blocksTable += node.block.hash -> node
    updateWeightFrom(node)
  }

  def contains(block: Block): Boolean = blocksTable.contains(block.hash)

  def add(block: Block): Boolean = {
    blocksTable.get(block.hash) match {
      case Some(_) => true
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

}
