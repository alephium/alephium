package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{Block, ChainIndex, Transaction}
import org.alephium.util.{AVector, Hex}

import scala.reflect.ClassTag

trait MultiChain extends BlockPool {
  implicit def config: PlatformConfig

  def groups: Int

  def blockChains: AVector[AVector[BlockChain]]

  protected def aggregate[T: ClassTag](f: BlockChain => T)(op: (T, T) => T): T = {
    blockChains.reduceBy { chains =>
      chains.reduceBy(f)(op)
    }(op)
  }

  override def numHashes: Int = aggregate(_.numHashes)(_ + _)

  override def numTransactions: Int = aggregate(_.numTransactions)(_ + _)

  override def maxWeight: Int = aggregate(_.maxWeight)(math.max)

  override def maxHeight: Int = aggregate(_.maxHeight)(math.max)

  def getChain(i: Int, j: Int): BlockChain

  def getChain(chainIndex: ChainIndex): BlockChain = {
    getChain(chainIndex.from, chainIndex.to)
  }

  def getChain(block: Block): BlockChain = getChain(getIndex(block))

  def getChain(hash: Keccak256): BlockChain = getChain(getIndex(hash))

  def getIndex(block: Block): ChainIndex = {
    getIndex(block.hash)
  }

  def contains(hash: Keccak256): Boolean = {
    val chain = getChain(hash)
    chain.contains(hash)
  }

  def getIndex(hash: Keccak256): ChainIndex = {
    ChainIndex.fromHash(hash)
  }

  def add(block: Block): AddBlockResult

  override def add(block: Block, weight: Int): AddBlockResult = {
    add(block, block.parentHash, weight)
  }

  override def add(block: Block, parentHash: Keccak256, weight: Int): AddBlockResult = {
    val chain = getChain(block)
    chain.add(block, parentHash, weight)
  }

  def getBlock(hash: Keccak256): Block = {
    getChain(hash).getBlock(hash)
  }

  def getBlocks(locator: Keccak256): AVector[Block] = {
    getChain(locator).getBlocks(locator)
  }

  def isTip(hash: Keccak256): Boolean = {
    getChain(hash).isTip(hash)
  }

  def getHeight(hash: Keccak256): Int = {
    getChain(hash).getHeight(hash)
  }

  def getWeight(hash: Keccak256): Int = {
    getChain(hash).getWeight(hash)
  }

  override def getBlockSlice(hash: Keccak256): AVector[Block] = getChain(hash).getBlockSlice(hash)

  override def getAllBlockHashes: Iterable[Keccak256] = aggregate(_.getAllBlockHashes)(_ ++ _)

  override def getBlockHashSlice(hash: Keccak256): AVector[Keccak256] =
    getChain(hash).getBlockHashSlice(hash)

  def getTransaction(hash: Keccak256): Transaction = ???

  def getInfo: String = {
    val infos = for {
      i <- 0 until groups
      j <- 0 until groups
    } yield s"($i, $j): ${getChain(i, j).maxHeight}/${getChain(i, j).numHashes - 1}"
    infos.mkString("; ")
  }

  def getBlockInfo: String = {
    val blocks = for {
      i     <- 0 until groups
      j     <- 0 until groups
      block <- getChain(i, j).getAllBlocks
    } yield toJson(i, j, block)
    val blocksJson = blocks.sorted.mkString("[", ",", "]")
    val heights = for {
      i <- 0 until groups
      j <- 0 until groups
    } yield s"""{"chainFrom":$i,"chainTo":$j,"height":${getChain(i, j).maxHeight}}"""
    val heightsJson = heights.mkString("[", ",", "]")
    s"""{"blocks":$blocksJson,"heights":$heightsJson}"""
  }

  def toJson(from: Int, to: Int, block: Block): String = {
    val timestamp = block.blockHeader.timestamp
    val height    = getWeight(block)
    val hash      = Hex.toHexString(block.hash.bytes).take(16)
    val deps = block.blockHeader.blockDeps
      .map(h => "\"" + Hex.toHexString(h.bytes).take(16) + "\"")
      .mkString("[", ",", "]")
    s"""{"timestamp":$timestamp,"chainFrom":$from,"chainTo":$to,"height":"$height","hash":"$hash","deps":$deps}"""
  }
}
