package org.alephium.flow.core

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

import org.alephium.crypto.Keccak256
import org.alephium.flow.core.BlockHashChain.{ChainDiff, TreeNode}
import org.alephium.flow.platform.PlatformProfile
import org.alephium.util.{AVector, ConcurrentHashMap, ConcurrentHashSet, TimeStamp}

// scalastyle:off number.of.methods
trait BlockHashChain extends BlockHashPool with ChainDifficultyAdjustment {
  implicit def config: PlatformProfile

  protected def root: BlockHashChain.Root

  protected override val blockHashesTable =
    ConcurrentHashMap.empty[Keccak256, BlockHashChain.TreeNode]

  protected val tips            = ConcurrentHashSet.empty[Keccak256]
  protected val confirmedHashes = ArrayBuffer.empty[BlockHashChain.TreeNode]

  protected def getNode(hash: Keccak256): BlockHashChain.TreeNode = blockHashesTable(hash)

  protected def addNode(node: BlockHashChain.TreeNode): Unit = {
    assert(node.isLeaf && !contains(node.blockHash))

    val hash = node.blockHash
    blockHashesTable.put(hash, node)
    tips.add(hash)
    node match {
      case _: BlockHashChain.Root =>
        ()
      case n: BlockHashChain.Node =>
        tips.removeIfExist(n.parent.blockHash)
        ()
    }

    pruneDueto(node)
    confirmHashes()
    ()
  }

  protected def addHash(hash: Keccak256,
                        parent: BlockHashChain.TreeNode,
                        weight: Int,
                        timestamp: TimeStamp): Unit = {
    val newNode = BlockHashChain.Node(hash, parent, parent.height + 1, weight, timestamp)
    parent.successors += newNode
    addNode(newNode)
  }

  protected def removeNode(node: BlockHashChain.TreeNode): Unit = {
    val hash = node.blockHash
    if (tips.contains(hash)) tips.remove(hash)
    blockHashesTable.remove(hash)
    ()
  }

  private def pruneDueto(newNode: BlockHashChain.TreeNode): Boolean = {
    val toCut = tips.iterator.filter { key =>
      val tipNode = blockHashesTable(key)
      newNode.height >= tipNode.height + config.blockConfirmNum
    }

    toCut.foreach { key =>
      val node = blockHashesTable(key)
      pruneBranchFrom(node)
    }
    toCut.nonEmpty
  }

  @tailrec
  private def pruneBranchFrom(node: BlockHashChain.TreeNode): Unit = {
    removeNode(node)

    node match {
      case n: BlockHashChain.Node =>
        val parent = n.parent
        if (parent.successors.size == 1) {
          pruneBranchFrom(parent)
        }
      case _: BlockHashChain.Root => ()
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  private def confirmHashes(): Unit = {
    val oldestTip = tips.iterator.map(blockHashesTable.apply).minBy(_.height)

    @tailrec
    def iter(): Unit = {
      if (confirmedHashes.isEmpty && root.successors.size == 1) {
        confirmedHashes.append(root)
        iter()
      } else if (confirmedHashes.nonEmpty) {
        val lastConfirmed = confirmedHashes.last
        if (lastConfirmed.successors.size == 1 && (oldestTip.height >= lastConfirmed.height + config.blockConfirmNum)) {
          confirmedHashes.append(lastConfirmed.successors.head)
          iter()
        }
      }
    }

    iter()
  }

  def numHashes: Int = blockHashesTable.size

  def maxWeight: Int = blockHashesTable.reduceValuesBy(_.weight)(math.max)

  def maxHeight: Int = blockHashesTable.reduceValuesBy(_.height)(math.max)

  def contains(hash: Keccak256): Boolean = blockHashesTable.contains(hash)

  def getHeight(hash: Keccak256): Int = {
    assert(contains(hash))
    blockHashesTable(hash).height
  }

  def getWeight(hash: Keccak256): Int = {
    assert(contains(hash))
    blockHashesTable(hash).weight
  }

  def isTip(hash: Keccak256): Boolean = {
    tips.contains(hash)
  }

  def getHashesAfter(locator: Keccak256): AVector[Keccak256] = {
    blockHashesTable.get(locator) match {
      case Some(node) => getHashesSince(AVector.from(node.successors))
      case None       => AVector.empty
    }
  }

  // Note: this is BFS search instead of DFS search
  private def getHashesSince(nodes: AVector[BlockHashChain.Node]): AVector[Keccak256] = {
    @tailrec
    def iter(acc: AVector[Keccak256],
             currents: AVector[BlockHashChain.Node]): AVector[Keccak256] = {
      if (currents.nonEmpty) {
        val nexts = currents.flatMap(node => AVector.from(node.successors))
        iter(acc ++ currents.map(_.blockHash), nexts)
      } else acc
    }

    iter(AVector.empty, nodes)
  }

  def getBestTip: Keccak256 = {
    getAllTips.map(blockHashesTable.apply).maxBy(_.height).blockHash
  }

  def getAllTips: AVector[Keccak256] = {
    AVector.fromIterator(tips.iterator)
  }

  // If oldHash is an ancestor of newHash, it returns all the new hashes after oldHash to newHash (inclusive)
  // Otherwise, it returns the hash path until newHash
  // TODO: make this safer
  def getBlockHashesBetween(newHash: Keccak256, oldHash: Keccak256): AVector[Keccak256] = {
    @tailrec
    def iter(acc: AVector[Keccak256], current: BlockHashChain.TreeNode): AVector[Keccak256] = {
      if (current.blockHash == oldHash) acc
      else {
        current match {
          case n: BlockHashChain.Root => acc :+ n.blockHash
          case n: BlockHashChain.Node => iter(acc :+ n.blockHash, n.parent)
        }
      }
    }

    blockHashesTable.get(newHash) match {
      case Some(node) => iter(AVector.empty, node).reverse
      case None       => AVector.empty
    }
  }

  def getBlockHashSlice(hash: Keccak256): AVector[Keccak256] = {
    blockHashesTable.get(hash) match {
      case Some(node) =>
        getChain(node).map(_.blockHash)
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

  def getAllBlockHashes: Iterator[Keccak256] = {
    blockHashesTable.values.map(_.blockHash)
  }

  def isBefore(hash1: Keccak256, hash2: Keccak256): Boolean = {
    assert(blockHashesTable.contains(hash1) && blockHashesTable.contains(hash2))
    val node1 = blockHashesTable(hash1)
    val node2 = blockHashesTable(hash2)
    isBefore(node1, node2)
  }

  def getPredecessor(hash: Keccak256, height: Int): Keccak256 = {
    val node = getNode(hash)
    getPredecessor(node, height).blockHash
  }

  private def getPredecessor(node: BlockHashChain.TreeNode,
                             height: Int): BlockHashChain.TreeNode = {
    @tailrec
    def iter(current: BlockHashChain.TreeNode): BlockHashChain.TreeNode = {
      assert(current.height >= height && height >= root.height)
      current match {
        case n: BlockHashChain.Node =>
          if (n.height == height) {
            current
          } else {
            iter(n.parent)
          }
        case _: BlockHashChain.Root =>
          assert(height == root.height)
          current
      }
    }

    if (height < confirmedHashes.size) {
      val targetHash = confirmedHashes(height).blockHash
      getNode(targetHash)
    } else {
      iter(node)
    }
  }

  private def isBefore(node1: BlockHashChain.TreeNode, node2: BlockHashChain.TreeNode): Boolean = {
    val height1 = node1.height
    val height2 = node2.height
    if (height1 < height2) {
      val node1Infer = getPredecessor(node2, node1.height)
      node1Infer.eq(node1)
    } else if (height1 == height2) {
      node1.eq(node2)
    } else false
  }

  def calHashDiff(newHash: Keccak256, oldHash: Keccak256): ChainDiff = {
    val toRemove = ArrayBuffer.empty[Keccak256]
    val toAdd    = ArrayBuffer.empty[Keccak256]
    calDiff(toRemove, toAdd, newHash, oldHash)
    ChainDiff(AVector.from(toRemove), AVector.fromIterator(toAdd.reverseIterator))
  }

  private def calDiff(toRemove: ArrayBuffer[Keccak256],
                      toAdd: ArrayBuffer[Keccak256],
                      newHash: Keccak256,
                      oldHash: Keccak256): Unit = {
    val newNode = getNode(newHash)
    val oldNode = getNode(oldHash)
    if (newNode.height > oldNode.height) {
      val newNode1 = accDiff(toAdd, newNode, newNode.height, oldNode.height)
      calDiff(toRemove, toAdd, newNode1, oldNode)
    } else if (oldNode.height > newNode.height) {
      val oldNode1 = accDiff(toRemove, oldNode, oldNode.height, newNode.height)
      calDiff(toRemove, toAdd, newNode, oldNode1)
    } else {
      calDiff(toRemove, toAdd, newNode, oldNode)
    }
  }

  @tailrec
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def accDiff(todos: ArrayBuffer[Keccak256],
                      node: TreeNode,
                      currentHeight: Int,
                      targetHeight: Int): TreeNode = {
    if (currentHeight > targetHeight) {
      todos.append(node.blockHash)
      accDiff(todos, node.parentOpt.get, currentHeight - 1, targetHeight)
    } else {
      node
    }
  }

  @tailrec
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def calDiff(toRemove: ArrayBuffer[Keccak256],
                      toAdd: ArrayBuffer[Keccak256],
                      newNode: TreeNode,
                      oldNode: TreeNode): Unit = {
    if (newNode.blockHash != oldNode.blockHash) {
      toRemove.append(oldNode.blockHash)
      toAdd.append(newNode.blockHash)
      calDiff(toRemove, toAdd, newNode.parentOpt.get, oldNode.parentOpt.get)
    }
  }

  def getConfirmedHash(height: Int): Option[Keccak256] = {
    assert(height >= 0)
    if (height < confirmedHashes.size) {
      Some(confirmedHashes(height).blockHash)
    } else None
  }
}
// scalastyle:on number.of.methods

object BlockHashChain {

  sealed trait TreeNode {
    val blockHash: Keccak256
    val successors: ArrayBuffer[Node]
    val height: Int
    val weight: Int
    val timestamp: TimeStamp

    def isRoot: Boolean
    def isLeaf: Boolean = successors.isEmpty

    def parentOpt: Option[TreeNode]
  }

  final case class Root(
      blockHash: Keccak256,
      successors: ArrayBuffer[Node],
      height: Int,
      weight: Int,
      timestamp: TimeStamp
  ) extends TreeNode {
    override def isRoot: Boolean = true

    override def parentOpt: Option[TreeNode] = None
  }

  object Root {
    def apply(blockHash: Keccak256, height: Int, weight: Int, timestamp: TimeStamp): Root =
      Root(blockHash, ArrayBuffer.empty, height, weight, timestamp)
  }

  final case class Node(
      blockHash: Keccak256,
      parent: TreeNode,
      successors: ArrayBuffer[Node],
      height: Int,
      weight: Int,
      timestamp: TimeStamp
  ) extends TreeNode {
    override def isRoot: Boolean = false

    override def parentOpt: Option[TreeNode] = Some(parent)
  }

  object Node {
    def apply(blockHash: Keccak256,
              parent: TreeNode,
              height: Int,
              weight: Int,
              timestamp: TimeStamp): Node = {
      new Node(blockHash, parent, ArrayBuffer.empty, height, weight, timestamp)
    }
  }

  final case class ChainDiff(toRemove: AVector[Keccak256], toAdd: AVector[Keccak256])
}
