package org.alephium.storage

import org.alephium.constant.Network
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.util.Hex

import scala.util.{Failure, Random, Success, Try}

class BlockFlow(groups: Int, initialBlocks: Seq[Seq[Block]]) extends BlockPool {
  val random = Random

  val blockPools: Seq[Seq[BlockPool]] =
    Seq.tabulate(groups, groups) {
      case (from, to) => ForksTree.apply(initialBlocks(from)(to))
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

  def checkDeps(block: Block): Unit = {
    Try {
      val deps = block.blockHeader.blockDeps
      val ok   = deps.forall(contains)
      if (!ok) println("Deps checking failed")
    } match {
      case Failure(exception) =>
        println(s"Deps checking exception: $exception")
      case Success(_) =>
    }
  }

  override def add(block: Block): Boolean = {
    checkDeps(block)
    val pool = getPool(block)
    pool.add(block)
  }

  def addBlock(block: Block): AddBlockResult = {
    val deps        = block.blockHeader.blockDeps
    val missingDeps = deps.filterNot(contains)
    if (missingDeps.isEmpty) {
      val pool = getPool(block)
      val ok   = pool.add(block)
      if (ok) AddBlockResult.Success else AddBlockResult.AlreadyExisted
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

  private def getHeight(hash: Keccak256): Int = {
    val pool  = getPool(hash)
    val block = pool.getBlock(hash)
    pool.getHeightFor(block)
  }

  private def getGroupHeight(hash: Keccak256): Int = {
    val pool      = getPool(hash)
    val block     = pool.getBlock(hash)
    val blockDeps = block.blockHeader.blockDeps
    if (blockDeps.isEmpty) 1
    else {
      require(blockDeps.size == (2 * groups - 1))
      val groupDeps = blockDeps.takeRight(groups)
      groupDeps.map(getHeight).sum + 1
    }
  }

  def getBlockFlowHeight(block: Block): Int = {
    val blockDeps = block.blockHeader.blockDeps
    if (blockDeps.isEmpty) 1
    else {
      require(blockDeps.size == (2 * groups - 1))
      val groupDeps = block.blockHeader.blockDeps.takeRight(groups)
      val otherDeps = block.blockHeader.blockDeps.dropRight(groups)
      otherDeps.map(getGroupHeight).sum + groupDeps.map(getHeight).sum + 1
    }
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

  def getGroupHeaders(hash: Keccak256): Seq[Keccak256] = {
    val block = getBlock(hash)
    getGroupHeaders(block)
  }

  def getGroupHeaders(block: Block): Seq[Keccak256] = {
    val blockDeps = block.blockHeader.blockDeps
    if (blockDeps.isEmpty) Seq.empty
    else {
      val groupDeps = blockDeps.takeRight(groups)
      groupDeps.updated(groupDeps.size - 1, block.hash)
    }
  }

  def getOtherHeaders(hash: Keccak256): Seq[Keccak256] = {
    val block = getBlock(hash)
    getOtherHeaders(block)
  }

  def getOtherHeaders(block: Block): Seq[Keccak256] = {
    val blockDeps = block.blockHeader.blockDeps
    if (blockDeps.isEmpty) Seq.empty
    else {
      blockDeps.dropRight(groups).flatMap(getGroupHeaders)
    }
  }

  def getHeaders(hash: Keccak256): Seq[Keccak256] = {
    val block = getBlock(hash)
    getHeaders(block)
  }

  def getHeaders(block: Block): Seq[Keccak256] = {
    val depsNum = groups * groups
    val deps    = Array.fill[Keccak256](depsNum)(Keccak256.zero)
    getGroupHeaders(block).foreach(h => deps(getIndex(h).toOneDim) = h)
    getOtherHeaders(block).foreach(h => deps(getIndex(h).toOneDim) = h)
    deps
  }

  def merge(hash1: Keccak256, hash2: Keccak256): Option[Keccak256] = {
    if (hash1 == Keccak256.zero) Some(hash2)
    else if (hash2 == Keccak256.zero) Some(hash1)
    else {
      // require(getIndex(hash1) == getIndex(hash2))
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

  def merge(headers: Seq[Keccak256], hash: Keccak256): Option[(Seq[Keccak256], Keccak256)] = {
    val newHeaders = getHeaders(hash)
    merge(headers, newHeaders).map((_, hash))
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

  def updateOutDeps(headers: Seq[Keccak256],
                    deps: Seq[Keccak256],
                    toTry: Seq[Keccak256],
                    from: Int): (Seq[Keccak256], Seq[Keccak256]) = {
    val validNewHeaders = toTry.flatMap(merge(headers, _))
    if (validNewHeaders.nonEmpty) {
      val (newHeaders, newDep) = validNewHeaders.maxBy(p => getHeadersHeight(p._1))
      (newHeaders, deps :+ newDep)
    } else (headers, deps :+ headers(ChainIndex(from, 0).toOneDim))
  }

  def updateGroupDeps(headers: Seq[Keccak256],
                      deps: Seq[Keccak256],
                      toTry: Seq[Keccak256],
                      chainIndex: ChainIndex): (Seq[Keccak256], Seq[Keccak256]) = {
    val pool   = getPool(chainIndex)
    val headOI = headers(chainIndex.toOneDim)
    val validNewHeaders =
      if (headOI == Keccak256.zero) toTry else toTry.filter(pool.isBefore(headOI, _))
    if (validNewHeaders.nonEmpty) {
      val newHeader = validNewHeaders.maxBy(header => pool.getHeightFor(pool.getBlock(header)))
      (headers.updated(chainIndex.toOneDim, newHeader), deps :+ newHeader)
    } else (headers, deps :+ headers(chainIndex.toOneDim))
  }

  def getBestDeps(chainIndex: ChainIndex): (Seq[Keccak256], Long) = {
    val bestHeader  = getBestHeader
    val bestHeaders = getHeaders(bestHeader)
    val bestIndex   = getIndex(bestHeader)
    val initialDeps = if (bestIndex.from == chainIndex.from) Seq.empty else Seq(bestHeader.hash)
    val (newHeaders1, newDeps1) = (0 until groups)
      .filter(k => k != chainIndex.from && k != bestIndex.from)
      .foldLeft((bestHeaders, initialDeps)) {
        case ((headers, deps), k) =>
          val toTry = (0 until groups).flatMap { l =>
            getPool(k, l).getAllHeaders
          }
          updateOutDeps(headers, deps, toTry, k)
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

object BlockFlow {
  def apply(): BlockFlow = new BlockFlow(Network.groups, Network.blocksForFlow)

  def apply(groups: Int, blocks: Seq[Seq[Block]]): BlockFlow = new BlockFlow(groups, blocks)

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
