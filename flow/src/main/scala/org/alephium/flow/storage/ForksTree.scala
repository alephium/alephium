package org.alephium.flow.storage

import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.Block

class ForksTree(_root: BlockHashChain.Root, _rootBlock: Block)(implicit val config: PlatformConfig)
    extends BlockChain {

  override def root: BlockHashChain.Root = _root
  def rootBlock: Block                   = _rootBlock

  // Initialization
  {
    assert(root.blockHash == rootBlock.hash)
    val hash = root.blockHash
    blocksTable += hash -> rootBlock
    postOrderTraverse(addNode)
  }

  private def postOrderTraverse(f: BlockHashChain.TreeNode => Unit): Unit = {
    def iter(node: BlockHashChain.TreeNode): Unit = {
      if (!node.isLeaf) node.successors.foreach(iter)
      f(node)
    }
    iter(root)
  }
}

object ForksTree {

  def apply(genesis: Block)(implicit config: PlatformConfig): ForksTree = apply(genesis, 0, 0)

  def apply(genesis: Block, initialHeight: Int, initialWeight: Int)(
      implicit config: PlatformConfig): ForksTree = {
    val root = BlockHashChain.Root(genesis.hash, initialHeight, initialWeight)
    new ForksTree(root, genesis)
  }
}
