package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{Block, Transaction}

import scala.collection.mutable.HashMap

trait BlockChain extends BlockPool with BlockHeaderChain with BlockHashChain {

  def diskIO: DiskIO

  protected val transactionsTable: HashMap[Keccak256, Transaction] = HashMap.empty

  def numTransactions: Int = transactionsTable.size

  def getTransaction(hash: Keccak256): Transaction = transactionsTable(hash)

  def getBlock(hash: Keccak256): IOResult[Block] = {
    diskIO.getBlock(hash)
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
    diskIO.putBlock(block).right.map(_ => ())
    // TODO: handle transactions later
//    block.transactions.foreach { transaction =>
//      transactionsTable += transaction.hash -> transaction
//    }
  }
}

object BlockChain {

  def fromGenesis(genesis: Block)(implicit config: PlatformConfig): BlockChain =
    apply(genesis, 0, 0)

  def apply(rootBlock: Block, initialHeight: Int, initialWeight: Int)(
      implicit _config: PlatformConfig): BlockChain = {

    val rootNode = BlockHashChain.Root(rootBlock.hash, initialHeight, initialWeight)

    new BlockChain {
      override val diskIO                              = _config.diskIO
      override val headerDB                            = _config.headerDB
      override implicit val config: PlatformConfig     = _config
      override protected def root: BlockHashChain.Root = rootNode

      this.addNode(rootNode)
      this.addHeader(rootBlock.header)
      this.persistBlock(rootBlock)
    }
  }
}
