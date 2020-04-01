package org.alephium.flow.core

import scala.reflect.ClassTag

import org.alephium.flow.io.IOResult
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model._
import org.alephium.util.AVector

// scalastyle:off number.of.methods
trait MultiChain extends BlockPool with BlockHeaderPool {
  implicit def config: PlatformConfig

  def groups: Int

  protected def aggregate[T: ClassTag](f: BlockHashPool => T)(op: (T, T) => T): T

  def numHashes: Int = aggregate(_.numHashes)(_ + _)

  def maxWeight: Int = aggregate(_.maxWeight)(math.max)

  def maxHeight: Int = aggregate(_.maxHeight)(math.max)

  /* BlockHash apis */

  def contains(hash: Hash): Boolean = {
    val index = ChainIndex.from(hash)
    val chain = getHashChain(index)
    chain.contains(hash)
  }

  def getIndex(hash: Hash): ChainIndex = {
    ChainIndex.from(hash)
  }

  protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain

  def getHashChain(chainIndex: ChainIndex): BlockHashChain = {
    getHashChain(chainIndex.from, chainIndex.to)
  }

  def getHashChain(hash: Hash): BlockHashChain = {
    val index = ChainIndex.from(hash)
    getHashChain(index.from, index.to)
  }

  def isTip(hash: Hash): Boolean = {
    getHashChain(hash).isTip(hash)
  }

  def getHashesAfter(locator: Hash): AVector[Hash] =
    getHashChain(locator).getHashesAfter(locator)

  def getPredecessor(hash: Hash, height: Int): Hash =
    getHashChain(hash).getPredecessor(hash, height)

  def getHeight(hash: Hash): Int = {
    getHashChain(hash).getHeight(hash)
  }

  def getWeight(hash: Hash): Int = {
    getHashChain(hash).getWeight(hash)
  }

  def getAllBlockHashes: Iterator[Hash] = aggregate(_.getAllBlockHashes)(_ ++ _)

  def getBlockHashSlice(hash: Hash): AVector[Hash] =
    getHashChain(hash).getBlockHashSlice(hash)

  /* BlockHeader apis */

  protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderChain

  def getHeaderChain(chainIndex: ChainIndex): BlockHeaderChain = {
    getHeaderChain(chainIndex.from, chainIndex.to)
  }

  def getHeaderChain(header: BlockHeader): BlockHeaderChain = {
    getHeaderChain(header.chainIndex)
  }

  def getHeaderChain(hash: Hash): BlockHeaderChain = {
    getHeaderChain(ChainIndex.from(hash))
  }

  def getBlockHeader(hash: Hash): IOResult[BlockHeader] =
    getHeaderChain(hash).getBlockHeader(hash)

  def getBlockHeaderUnsafe(hash: Hash): BlockHeader =
    getHeaderChain(hash).getBlockHeaderUnsafe(hash)

  def add(header: BlockHeader): IOResult[Unit]

  /* BlockChain apis */

  protected def getBlockChain(from: GroupIndex, to: GroupIndex): BlockChain

  def getBlockChain(chainIndex: ChainIndex): BlockChain = {
    getBlockChain(chainIndex.from, chainIndex.to)
  }

  def getBlockChain(block: Block): BlockChain = getBlockChain(block.chainIndex)

  def getBlockChain(hash: Hash): BlockChain = {
    getBlockChain(ChainIndex.from(hash))
  }

  def getBlock(hash: Hash): IOResult[Block] = {
    getBlockChain(hash).getBlock(hash)
  }

  def add(block: Block): IOResult[Unit]

  def getHeadersUnsafe(predicate: BlockHeader => Boolean): Seq[BlockHeader] = {
    for {
      i    <- 0 until groups
      j    <- 0 until groups
      hash <- getHashChain(GroupIndex.unsafe(i), GroupIndex.unsafe(j)).getAllBlockHashes
      header = getBlockHeaderUnsafe(hash)
      if predicate(header)
    } yield {
      header
    }
  }
}
