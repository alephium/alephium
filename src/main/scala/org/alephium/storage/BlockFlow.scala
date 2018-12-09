package org.alephium.storage

import org.alephium.constant.Network
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.storage.BlockFlow.ChainIndex

import scala.util.Random

class BlockFlow(groups: Int, initialBlocks: Seq[Seq[Block]]) extends BlockPool {
  val random = Random

  private val blockPools: Seq[Seq[BlockPool]] =
    Seq.tabulate(groups, groups) {
      case (from, to) => ForksTree.apply(initialBlocks(from)(to))
    }

  private def aggregate[T](f: BlockPool => T)(reduce: Seq[T] => T): T = {
    reduce(blockPools.flatMap(_.map(f)))
  }

  override def numBlocks: Int = aggregate(_.numBlocks)(_.sum)

  override def numTransactions: Int = aggregate(_.numTransactions)(_.sum)

  private def getPool(i: Int, j: Int): BlockPool = {
    require(i >= 0 && i < groups && j >= 0 && j < groups)
    blockPools(i)(j)
  }

  private def getPool(chainIndex: ChainIndex): BlockPool = {
    getPool(chainIndex.from, chainIndex.to)
  }

  def getIndex(block: Block): ChainIndex = {
    getIndex(block.hash)
  }

  private def getIndex(hash: Keccak256): ChainIndex = {
    val miningHash = Block.toMiningHash(hash)
    ChainIndex(miningHash.bytes(0).toInt, miningHash.bytes(1).toInt)
  }

  private def getPool(block: Block): BlockPool = getPool(getIndex(block))

  private def getPool(hash: Keccak256): BlockPool = getPool(getIndex(hash))

  override def add(block: Block): Boolean = {
    val pool = getPool(block)
    pool.add(block)
  }

  override def getBlock(hash: Keccak256): Block = {
    getPool(hash).getBlock(hash)
  }

  override def getBlocks(locator: Keccak256): Seq[Block] = {
    getPool(locator).getBlocks(locator)
  }

  override def isHeader(block: Block): Boolean = {
    getPool(block).isHeader(block)
  }

  private def getHeight(hash: Keccak256): Int = {
    val pool  = getPool(hash)
    val block = pool.getBlock(hash)
    pool.getHeightFor(block)
  }

  private def getGroupHeight(hash: Keccak256): Int = {
    val pool      = getPool(hash)
    val block     = pool.getBlock(hash)
    val groupDeps = block.blockHeader.blockDeps.takeRight(groups)
    groupDeps.map(getHeight).sum + 1
  }

  def getBlockFlowHeight(block: Block): Int = {
    val groupDeps = block.blockHeader.blockDeps.takeRight(groups)
    val otherDeps = block.blockHeader.blockDeps.dropRight(groups)
    otherDeps.map(getGroupHeight).sum + groupDeps.map(getHeight).sum + 1
  }

  def getBlockFlowHeight(blockHash: Keccak256): Int = {
    val block = getBlock(blockHash)
    getBlockFlowHeight(block)
  }

  override def getBestHeader: Block = {
    val blockHash = getAllHeaders.maxBy(getBlockFlowHeight)
    getBlock(blockHash)
  }

  def getBestLength: Int = {
    getAllHeaders.map(getBlockFlowHeight).max
  }

  override def getAllHeaders: Seq[Keccak256] =
    aggregate(_.getAllHeaders)(_.foldLeft(Seq.empty[Keccak256])(_ ++ _))

  def getBestDeps(chainIndex: ChainIndex): Seq[Keccak256] = {
    require(
      0 <= chainIndex.from && chainIndex.from < groups &&
        0 <= chainIndex.to && chainIndex.to < groups
    )
    val outDepIndice = (0 until groups).filter(_ != chainIndex.from).map { from =>
      val to = (0 until groups).maxBy(to => getPool(from, to).getHeight)
      ChainIndex(from, to)
    }
    val inDepIndice =
      (0 until groups).filter(_ != chainIndex.to).map(ChainIndex(chainIndex.from, _))
    val depIndice = outDepIndice ++ inDepIndice :+ chainIndex
    depIndice.map(chainIndex => getPool(chainIndex).getBestHeader.hash)
  }

  override def getChain(block: Block): Seq[Block] = getPool(block).getChain(block)

  override def getTransaction(hash: Keccak256): Transaction = ???

  def getInfo: String = {
    val infos = for {
      i <- 0 until groups
      j <- 0 until groups
    } yield s"($i, $j): ${getPool(i, j).getHeight}"
    infos.mkString("; ")
  }
}

object BlockFlow {
  def apply(): BlockFlow = new BlockFlow(Network.groups, Network.blocksForFlow)

  def apply(groups: Int, blocks: Seq[Seq[Block]]): BlockFlow = new BlockFlow(groups, blocks)

  case class ChainIndex(from: Int, to: Int) {
    def accept(hash: Keccak256): Boolean = {
      hash.bytes(0) == from && hash.bytes(1) == to
    }
  }
}
