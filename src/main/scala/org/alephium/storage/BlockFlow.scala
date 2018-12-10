package org.alephium.storage

import java.util.concurrent.ConcurrentHashMap

import org.alephium.constant.Network
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.util.Hex

import scala.collection.JavaConverters._

// scalastyle:off number.of.methods
class BlockFlow() extends BlockPool {
  import Network.groups

  val initialBlocks: Seq[Seq[Block]] = Network.blocksForFlow

  val blockPools: Seq[Seq[BlockPool]] =
    Seq.tabulate(groups, groups) {
      case (from, to) => ForksTree.apply(initialBlocks(from)(to))
    }

  val headerDeps: Seq[Seq[collection.concurrent.Map[Keccak256, Seq[Keccak256]]]] =
    Seq.tabulate(groups, groups) { (from, to) =>
      val header = initialBlocks(from)(to).hash
      val deps = Seq.tabulate(Network.chainNum) { index =>
        val i = index / groups
        val j = index % groups
        initialBlocks(i)(j).hash
      }
      val map = new ConcurrentHashMap[Keccak256, Seq[Keccak256]]().asScala
      map += (header -> deps)
    }

  private def aggregate[T](f: BlockPool => T)(reduce: Seq[T] => T): T = {
    reduce(blockPools.flatMap(_.map(f)))
  }

  override def numBlocks: Int = aggregate(_.numBlocks)(_.sum)

  override def numTransactions: Int = aggregate(_.numTransactions)(_.sum)

  val numGroups: Int = groups

  private def getPool(i: Int, j: Int): BlockPool = {
    require(i >= 0 && i < groups && j >= 0 && j < groups)
    blockPools(i)(j)
  }

  def getPool(chainIndex: ChainIndex): BlockPool = {
    getPool(chainIndex.from, chainIndex.to)
  }

  def getIndex(block: Block): ChainIndex = {
    getIndex(block.hash)
  }

  private def getIndex(hash: Keccak256): ChainIndex = {
    ChainIndex.fromHash(hash)
  }

  private def getPool(block: Block): BlockPool = getPool(getIndex(block))

  private def getPool(hash: Keccak256): BlockPool = getPool(getIndex(hash))

  override def contains(block: Block): Boolean = {
    val pool = getPool(block)
    pool.contains(block)
  }

  def contains(hash: Keccak256): Boolean = {
    val pool = getPool(hash)
    pool.contains(hash)
  }

  def addDeps(block: Block, deps: Seq[Keccak256]): Unit = {
    val chainIndex = getIndex(block)
    val pool       = blockPools(chainIndex.from)(chainIndex.to)
    val caches     = headerDeps(chainIndex.from)(chainIndex.to)
    caches.retain { (header, _) =>
      !pool.isBefore(header, block.hash) || {
        val headerHeight = pool.getHeightFor(header)
        val blockHeight  = pool.getHeightFor(block)
        headerHeight >= blockHeight - 5
      }
    }
    caches += (block.hash -> deps)
  }

  override def add(block: Block): Boolean = {
    addBlock(block) match {
      case AddBlockResult.Success => true
      case _                      => false
    }
  }

  def addBlock(block: Block): AddBlockResult = {
    // TODO: check dependencies
    val deps        = block.blockHeader.blockDeps
    val missingDeps = deps.filterNot(contains)
    if (missingDeps.isEmpty) {
      val pool = getPool(block)
      val ok   = pool.add(block)
      if (ok) {
        val deps = getHeaders(block)
        addDeps(block, deps)
        AddBlockResult.Success
      } else AddBlockResult.AlreadyExisted
    } else {
      AddBlockResult.MissingDeps(missingDeps :+ block.hash)
    }
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

  def getBlockFlowHeight(block: Block): Int = {
    val chainIndex = getIndex(block)
    val deps       = headerDeps(chainIndex.from)(chainIndex.to)(block.hash)
    getHeadersHeight(deps)
  }

  def getBlockFlowHeight(blockHash: Keccak256): Int = {
    val block = getBlock(blockHash)
    getBlockFlowHeight(block)
  }

  override def getBestHeader: Block = {
    val heights = for {
      seq            <- headerDeps
      cache          <- seq
      (header, deps) <- cache
    } yield (header, getHeadersHeight(deps))
    val blockHash = heights.maxBy(_._2)._1
    getBlock(blockHash)
  }

  def getBestLength: Int = {
    getAllHeaders.map(getBlockFlowHeight).max
  }

  override def getAllHeaders: Seq[Keccak256] =
    aggregate(_.getAllHeaders)(_.foldLeft(Seq.empty[Keccak256])(_ ++ _))

  def getCachedHeaders(hash: Keccak256): Seq[Keccak256] = {
    val block = getBlock(hash)
    getCachedHeaders(block)
  }

  def getCachedHeaders(block: Block): Seq[Keccak256] = {
    val chainIndex = getIndex(block)
    headerDeps(chainIndex.from)(chainIndex.to)(block.hash)
  }

  def getHeaders(hash: Keccak256): Seq[Keccak256] = {
    val block = getBlock(hash)
    getHeaders(block)
  }

  def getHeaders(block: Block): Seq[Keccak256] = {
    val deps       = block.blockHeader.blockDeps.map(getCachedHeaders).reduce(merge(_, _).get)
    val chainIndex = getIndex(block)
    deps.updated(chainIndex.toOneDim, block.hash)
  }

  def merge(hash1: Keccak256, hash2: Keccak256): Option[Keccak256] = {
    if (hash1 == Keccak256.zero) Some(hash2)
    else if (hash2 == Keccak256.zero) Some(hash1)
    else {
      require(getIndex(hash1) == getIndex(hash2))
      val pool = getPool(hash1)
      if (pool.isBefore(hash1, hash2)) Some(hash2)
      else if (pool.isBefore(hash2, hash1)) Some(hash1)
      else None
    }
  }

  def merge(headers1: Seq[Keccak256], headers2: Seq[Keccak256]): Option[Seq[Keccak256]] = {
    // require(headers1.size == headers2.size)
    val merged = headers1.zip(headers2).map {
      case (h1, h2) => merge(h1, h2)
    }

    if (merged.forall(_.nonEmpty)) {
      Some(merged.map(_.get))
    } else None
  }

  def merge(headers: Seq[Keccak256], hash: Keccak256): Option[(Seq[Keccak256], Keccak256, Int)] = {
    val newHeaders = getCachedHeaders(hash)
    merge(headers, newHeaders).map { mergedHeaders =>
      val heightBefore = getHeadersHeight(headers)
      val heightAfter  = getHeadersHeight(mergedHeaders)
      (mergedHeaders, hash, heightAfter - heightBefore)
    }
  }

  def getHeadersHeight(headers: Seq[Keccak256]): Int = {
    headers.map { header =>
      if (header == Keccak256.zero) 0
      else {
        val pool  = getPool(header)
        val block = pool.getBlock(header)
        pool.getHeightFor(block)
      }
    }.sum
  }

  def updateGroupDeps(headers: Seq[Keccak256],
                      deps: Seq[Keccak256],
                      toTry: Seq[Keccak256],
                      chainIndex: ChainIndex): (Seq[Keccak256], Seq[Keccak256]) = {
    val validNewHeaders = toTry.flatMap(merge(headers, _))
    if (validNewHeaders.nonEmpty) {
      val (newHeaders, newDep, _) = validNewHeaders.maxBy(_._3)
      (newHeaders, deps :+ newDep)
    } else (headers, deps :+ headers(chainIndex.toOneDim))
  }

  def getBestDeps(chainIndex: ChainIndex): (Seq[Keccak256], Long) = {
    val bestHeader  = getBestHeader
    val bestHeaders = getCachedHeaders(bestHeader)
    val bestIndex   = getIndex(bestHeader)
    val initialDeps = if (bestIndex.from == chainIndex.from) Seq.empty else Seq(bestHeader.hash)
    val (newHeaders1, newDeps1) = (0 until groups)
      .filter(k => k != chainIndex.from && k != bestIndex.from)
      .foldLeft((bestHeaders, initialDeps)) {
        case ((headers, deps), k) =>
          val toTry = (0 until groups).flatMap { l =>
            getPool(k, l).getAllHeaders
          }
          updateGroupDeps(headers, deps, toTry, ChainIndex(k, 0))
      }
    val (newHeaders2, newDeps2) = (0 until groups)
      .filter(_ != chainIndex.to)
      .foldLeft((newHeaders1, newDeps1)) {
        case ((headers, deps), l) =>
          val toTryIndex = ChainIndex(chainIndex.from, l)
          val toTry      = getPool(chainIndex.from, l).getAllHeaders
          updateGroupDeps(headers, deps, toTry, toTryIndex)
      }
    val toTry         = getPool(chainIndex).getAllHeaders
    val (_, newDeps3) = updateGroupDeps(newHeaders2, newDeps2, toTry, chainIndex)
    (newDeps3, getBlock(newDeps3.last).blockHeader.timestamp)
  }

  override def getChain(block: Block): Seq[Block] = getPool(block).getChain(block)

  override def getAllBlocks: Iterable[Block] =
    for {
      i     <- 0 until groups
      j     <- 0 until groups
      block <- getPool(i, j).getAllBlocks
    } yield block

  override def isBefore(hash1: Keccak256, hash2: Keccak256): Boolean = ???

  override def getTransaction(hash: Keccak256): Transaction = ???

  def getInfo: String = {
    val infos = for {
      i <- 0 until groups
      j <- 0 until groups
    } yield s"($i, $j): ${getPool(i, j).getHeight}"
    infos.mkString("; ")
  }

  def getBlockInfo: String = {
    val blocks = for {
      i     <- 0 until groups
      j     <- 0 until groups
      block <- getPool(i, j).getAllBlocks
    } yield toJson(i, j, block)
    val blocksJson = blocks.sorted.mkString("[", ",", "]")
    val heights = for {
      i <- 0 until groups
      j <- 0 until groups
    } yield s"""{"chainFrom":$i,"chainTo":$j,"height":${getPool(i, j).getHeight}}"""
    val heightsJson = heights.mkString("[", ",", "]")
    s"""{"blocks":$blocksJson,"heights":$heightsJson}"""
  }

  def toJson(from: Int, to: Int, block: Block): String = {
    val timestamp = block.blockHeader.timestamp
    val height    = getBlockFlowHeight(block)
    val hash      = Hex.toHexString(block.hash.bytes).take(16)
    val deps = block.blockHeader.blockDeps
      .map(h => "\"" + Hex.toHexString(h.bytes).take(16) + "\"")
      .mkString("[", ",", "]")
    s"""{"timestamp":$timestamp,"chainFrom":$from,"chainTo":$to,"height":"$height","hash":"$hash","deps":$deps}"""
  }
}
// scalastyle:on number.of.methods

object BlockFlow {
  def apply(): BlockFlow = new BlockFlow()

  private def hash2Index(hash: Keccak256): Int = {
    val bytes    = hash.bytes
    val BigIndex = bytes(1).toInt * 256 + bytes(0).toInt
    Math.floorMod(BigIndex, Network.chainNum)
  }

  case class ChainIndex(from: Int, to: Int) {
    def accept(hash: Keccak256): Boolean = {
      val target     = from * Network.groups + to
      val miningHash = Block.toMiningHash(hash)
      val actual     = hash2Index(miningHash)
      actual == target
    }

    def toOneDim: Int = from * Network.groups + to
  }

  object ChainIndex {
    def fromHash(hash: Keccak256): ChainIndex = {
      val miningHash = Block.toMiningHash(hash)
      val target     = hash2Index(miningHash)
      val from       = target / Network.groups
      val to         = target % Network.groups
      ChainIndex(from, to)
    }
  }

  case class BlockInfo(timestamp: Long, chainIndex: ChainIndex)

}

sealed trait AddBlockResult

object AddBlockResult {
  case object Success                          extends AddBlockResult
  case object AlreadyExisted                   extends AddBlockResult
  case class MissingDeps(deps: Seq[Keccak256]) extends AddBlockResult
}
