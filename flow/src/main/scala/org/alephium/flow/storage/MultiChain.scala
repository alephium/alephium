package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.IOResult
import org.alephium.protocol.model._
import org.alephium.util.AVector

import scala.reflect.ClassTag

// scalastyle:off number.of.methods
trait MultiChain extends BlockPool with BlockHeaderPool {
  implicit def config: PlatformConfig

  def groups: Int

  protected def aggregate[T: ClassTag](f: BlockHashPool => T)(op: (T, T) => T): T

  def numHashes: Int = aggregate(_.numHashes)(_ + _)

  def maxWeight: Int = aggregate(_.maxWeight)(math.max)

  def maxHeight: Int = aggregate(_.maxHeight)(math.max)

  /* BlockHash apis */

  def contains(hash: Keccak256): Boolean = {
    val index = ChainIndex.from(hash)
    val chain = getHashChain(index)
    chain.contains(hash)
  }

  def getIndex(hash: Keccak256): ChainIndex = {
    ChainIndex.from(hash)
  }

  protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain

  def getHashChain(chainIndex: ChainIndex): BlockHashChain = {
    getHashChain(chainIndex.from, chainIndex.to)
  }

  def getHashChain(hash: Keccak256): BlockHashChain = {
    val index = ChainIndex.from(hash)
    getHashChain(index.from, index.to)
  }

  def isTip(hash: Keccak256): Boolean = {
    getHashChain(hash).isTip(hash)
  }

  def getHashesAfter(locator: Keccak256): AVector[Keccak256] =
    getHashChain(locator).getHashesAfter(locator)

  def getHeight(hash: Keccak256): Int = {
    getHashChain(hash).getHeight(hash)
  }

  def getWeight(hash: Keccak256): Int = {
    getHashChain(hash).getWeight(hash)
  }

  def getAllBlockHashes: Iterator[Keccak256] = aggregate(_.getAllBlockHashes)(_ ++ _)

  def getBlockHashSlice(hash: Keccak256): AVector[Keccak256] =
    getHashChain(hash).getBlockHashSlice(hash)

  /* BlockHeader apis */

  protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderPool

  def getHeaderChain(chainIndex: ChainIndex): BlockHeaderPool = {
    getHeaderChain(chainIndex.from, chainIndex.to)
  }

  def getHeaderChain(header: BlockHeader): BlockHeaderPool = {
    getHeaderChain(header.chainIndex)
  }

  def getHeaderChain(hash: Keccak256): BlockHeaderPool = {
    getHeaderChain(ChainIndex.from(hash))
  }

  def getBlockHeader(hash: Keccak256): IOResult[BlockHeader] =
    getHeaderChain(hash).getBlockHeader(hash)

  def getBlockHeaderUnsafe(hash: Keccak256): BlockHeader =
    getHeaderChain(hash).getBlockHeaderUnsafe(hash)

  def add(header: BlockHeader): IOResult[Unit]

  /* BlockChain apis */

  protected def getBlockChain(from: GroupIndex, to: GroupIndex): BlockChain

  def getBlockChain(chainIndex: ChainIndex): BlockChain = {
    getBlockChain(chainIndex.from, chainIndex.to)
  }

  def getBlockChain(block: Block): BlockChain = getBlockChain(block.chainIndex)

  def getBlockChain(hash: Keccak256): BlockChain = {
    getBlockChain(ChainIndex.from(hash))
  }

  def getBlock(hash: Keccak256): IOResult[Block] = {
    getBlockChain(hash).getBlock(hash)
  }

  def add(block: Block): IOResult[Unit]

  def getTransaction(hash: Keccak256): Transaction = ???

  def getHeadersUnsafe(predicate: BlockHeader => Boolean): Seq[BlockHeader] = {
    for {
      i    <- 0 until groups
      j    <- 0 until groups
      hash <- getHashChain(GroupIndex(i), GroupIndex(j)).getAllBlockHashes
      header = getBlockHeaderUnsafe(hash)
      if predicate(header)
    } yield {
      header
    }
  }
}
