package org.alephium.storage

import java.util.concurrent.ConcurrentHashMap

import org.alephium.constant.Network
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.util.Hex

import scala.collection.JavaConverters._

// scalastyle:off number.of.methods
class BlockFlow() extends MultiChain {
  import Network.groups

  val initialBlocks: Seq[Seq[Block]] = Network.blocksForFlow

  val SingleChains: Seq[Seq[SingleChain]] =
    Seq.tabulate(groups, groups) {
      case (from, to) => ForksTree(initialBlocks(from)(to))
    }

  val depsInTips: Seq[Seq[collection.concurrent.Map[Keccak256, Seq[Keccak256]]]] =
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

  private def aggregate[T](f: SingleChain => T)(reduce: Seq[T] => T): T = {
    reduce(SingleChains.flatMap(_.map(f)))
  }

  override def numBlocks: Int = aggregate(_.numBlocks)(_.sum)

  override def numTransactions: Int = aggregate(_.numTransactions)(_.sum)

  override def maxWeight: Int = aggregate(_.maxWeight)(_.max)

  private def getChain(i: Int, j: Int): SingleChain = {
    assert(i >= 0 && i < groups && j >= 0 && j < groups)
    SingleChains(i)(j)
  }

  def getChain(chainIndex: ChainIndex): SingleChain = {
    getChain(chainIndex.from, chainIndex.to)
  }

  def getIndex(block: Block): ChainIndex = {
    getIndex(block.hash)
  }

  private def getIndex(hash: Keccak256): ChainIndex = {
    ChainIndex.fromHash(hash)
  }

  private def getChain(block: Block): SingleChain = getChain(getIndex(block))

  private def getChain(hash: Keccak256): SingleChain = getChain(getIndex(hash))

  override def contains(block: Block): Boolean = {
    val chain = getChain(block)
    chain.contains(block)
  }

  def contains(hash: Keccak256): Boolean = {
    val chain = getChain(hash)
    chain.contains(hash)
  }

  def addDeps(block: Block, deps: Seq[Keccak256]): Unit = {
    val chainIndex = getIndex(block)
    val chain      = SingleChains(chainIndex.from)(chainIndex.to)
    val caches     = depsInTips(chainIndex.from)(chainIndex.to)
    caches.retain { (tip, _) =>
      !chain.isBefore(tip, block.hash) || {
        val tipHeight   = chain.getHeight(tip)
        val blockHeight = chain.getHeight(block)
        tipHeight >= blockHeight - 5
      }
    }
    caches += (block.hash -> deps)
  }

  override def add(block: Block): AddBlockResult = {
    // TODO: check dependencies
    val deps        = block.blockHeader.blockDeps
    val missingDeps = deps.filterNot(contains)
    if (missingDeps.isEmpty) {
      val tips       = getDepsInTips(block)
      val chainIndex = getIndex(block)
      val chain      = getChain(chainIndex)
      val weight     = getTipsWeight(tips) + 1
      val ok         = chain.add(block, weight)
      if (ok) {
        addDeps(block, tips.updated(chainIndex.toOneDim, block.hash))
        AddBlockResult.Success
      } else AddBlockResult.AlreadyExisted
    } else {
      AddBlockResult.MissingDeps(missingDeps :+ block.hash)
    }
  }

  override def getBlock(hash: Keccak256): Block = {
    getChain(hash).getBlock(hash)
  }

  override def getBlocks(locator: Keccak256): Seq[Block] = {
    getChain(locator).getBlocks(locator)
  }

  override def isTip(hash: Keccak256): Boolean = {
    getChain(hash).isTip(hash)
  }

  override def getHeight(hash: Keccak256): Int = {
    getChain(hash).getHeight(hash)
  }

  override def getWeight(hash: Keccak256): Int = {
    getChain(hash).getWeight(hash)
  }

  override def getBestTip: Block = {
    aggregate(_.getBestTip)(_.maxBy(block => getWeight(block)))
  }

  override def getAllTips: Seq[Keccak256] =
    aggregate(_.getAllTips)(_.foldLeft(Seq.empty[Keccak256])(_ ++ _))

  def getCachedHeaders(hash: Keccak256): Seq[Keccak256] = {
    val chainIndex = getIndex(hash)
    depsInTips(chainIndex.from)(chainIndex.to)(hash)
  }

  def getCachedHeaders(block: Block): Seq[Keccak256] = {
    getCachedHeaders(block.hash)
  }

  def getTips(hash: Keccak256): Seq[Keccak256] = {
    val block = getBlock(hash)
    getTips(block)
  }

  def getDepsInTips(block: Block): Seq[Keccak256] = {
    block.blockHeader.blockDeps.map(getCachedHeaders).reduce(merge(_, _).get)
  }

  def getTips(block: Block): Seq[Keccak256] = {
    val deps       = getDepsInTips(block)
    val chainIndex = getIndex(block)
    deps.updated(chainIndex.toOneDim, block.hash)
  }

  def merge(hash1: Keccak256, hash2: Keccak256): Option[Keccak256] = {
    if (hash1 == Keccak256.zero) Some(hash2)
    else if (hash2 == Keccak256.zero) Some(hash1)
    else {
      assert(getIndex(hash1) == getIndex(hash2))
      val chain = getChain(hash1)
      if (chain.isBefore(hash1, hash2)) Some(hash2)
      else if (chain.isBefore(hash2, hash1)) Some(hash1)
      else None
    }
  }

  def merge(tips1: Seq[Keccak256], tips2: Seq[Keccak256]): Option[Seq[Keccak256]] = {
    assert(tips1.size == tips2.size)
    val merged = tips1.zip(tips2).map {
      case (hash1, hash2) => merge(hash1, hash2)
    }

    if (merged.forall(_.nonEmpty)) {
      Some(merged.map(_.get))
    } else None
  }

  def merge(tips: Seq[Keccak256], hash: Keccak256): Option[(Seq[Keccak256], Keccak256, Int)] = {
    val newTips = getCachedHeaders(hash)
    merge(tips, newTips).map { mergedTips =>
      val heightBefore = getTipsWeight(tips)
      val heightAfter  = getTipsWeight(mergedTips)
      (mergedTips, hash, heightAfter - heightBefore)
    }
  }

  def getTipsWeight(tips: Seq[Keccak256]): Int = {
    tips.map(getHeight).sum
  }

  def updateGroupDeps(tips: Seq[Keccak256],
                      deps: Seq[Keccak256],
                      toTry: Seq[Keccak256],
                      chainIndex: ChainIndex): (Seq[Keccak256], Seq[Keccak256]) = {
    val validNewTips = toTry.flatMap(merge(tips, _))
    if (validNewTips.nonEmpty) {
      val (newTips, newDep, _) = validNewTips.maxBy(_._3)
      (newTips, deps :+ newDep)
    } else (tips, deps :+ tips(chainIndex.toOneDim))
  }

  def getBestDeps(chainIndex: ChainIndex): (Seq[Keccak256], Long) = {
    val bestTip       = getBestTip
    val tipsOfBestTip = getCachedHeaders(bestTip)
    val bestIndex     = getIndex(bestTip)
    val initialDeps   = if (bestIndex.from == chainIndex.from) Seq.empty else Seq(bestTip.hash)
    val (newTips1, newDeps1) = (0 until groups)
      .filter(k => k != chainIndex.from && k != bestIndex.from)
      .foldLeft((tipsOfBestTip, initialDeps)) {
        case ((tips, deps), k) =>
          val toTry = (0 until groups).flatMap { l =>
            getChain(k, l).getAllTips
          }
          updateGroupDeps(tips, deps, toTry, ChainIndex(k, 0))
      }
    val (newTips2, newDeps2) = (0 until groups)
      .filter(_ != chainIndex.to)
      .foldLeft((newTips1, newDeps1)) {
        case ((tips, deps), l) =>
          val toTryIndex = ChainIndex(chainIndex.from, l)
          val toTry      = getChain(chainIndex.from, l).getAllTips
          updateGroupDeps(tips, deps, toTry, toTryIndex)
      }
    val toTry         = getChain(chainIndex).getAllTips
    val (_, newDeps3) = updateGroupDeps(newTips2, newDeps2, toTry, chainIndex)
    (newDeps3, getBlock(newDeps3.last).blockHeader.timestamp)
  }

  override def getBlockSlice(block: Block): Seq[Block] = getChain(block).getBlockSlice(block)

  override def getAllBlocks: Iterable[Block] =
    for {
      i     <- 0 until groups
      j     <- 0 until groups
      block <- getChain(i, j).getAllBlocks
    } yield block

  override def isBefore(hash1: Keccak256, hash2: Keccak256): Boolean = ???

  override def getTransaction(hash: Keccak256): Transaction = ???

  def getInfo: String = {
    val infos = for {
      i <- 0 until groups
      j <- 0 until groups
    } yield s"($i, $j): ${getChain(i, j).maxHeight}/${getChain(i, j).numBlocks - 1}"
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
