package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.{Disk, IOResult}
import org.alephium.protocol.model.Block

trait BlockChain extends BlockPool with BlockHeaderChain with BlockHashChain {

  def disk: Disk

  def getBlock(hash: Keccak256): IOResult[Block] = {
    disk.getBlock(hash)
  }

  def add(block: Block, weight: Int): IOResult[Unit] = {
    add(block, block.parentHash, weight)
  }

  def add(block: Block, parentHash: Keccak256, weight: Int): IOResult[Unit] = {
    assert(!contains(block.hash) && contains(parentHash))
    for {
      _ <- persistBlock(block)
      _ <- add(block.header, parentHash, weight)
    } yield ()
  }

  protected def persistBlock(block: Block): IOResult[Unit] = {
    disk.putBlock(block).right.map(_ => ())
    // TODO: handle transactions later
  }

  protected def persistBlockUnsafe(block: Block): Unit = {
    disk.putBlockUnsafe(block)
    ()
  }
}

object BlockChain {

  def fromGenesisUnsafe(genesis: Block)(implicit config: PlatformConfig): BlockChain =
    createUnsafe(genesis, 0, 0)

  private def createUnsafe(rootBlock: Block, initialHeight: Int, initialWeight: Int)(
      implicit _config: PlatformConfig): BlockChain = {
    val timestamp = rootBlock.header.timestamp
    val rootNode  = BlockHashChain.Root(rootBlock.hash, initialHeight, initialWeight, timestamp)

    new BlockChain {
      override val disk                                = _config.disk
      override val headerDB                            = _config.headerDB
      override implicit val config: PlatformConfig     = _config
      override protected def root: BlockHashChain.Root = rootNode

      this.persistBlockUnsafe(rootBlock)
      this.addHeaderUnsafe(rootBlock.header)
      this.addNode(rootNode)
    }
  }
}
