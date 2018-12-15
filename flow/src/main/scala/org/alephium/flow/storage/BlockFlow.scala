package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockDeps
import org.alephium.protocol.model.{Block, ChainIndex, Transaction}
import org.alephium.util.{AVector, Hex}

import scala.reflect.ClassTag

class BlockFlow()(implicit val config: PlatformConfig) extends MultiChain {
  import config.{blocksForFlow, groups}

  val initialBlocks: AVector[AVector[Block]] = blocksForFlow

  val singleChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(groups, groups) {
      case (from, to) => ForksTree(initialBlocks(from)(to))
    }

  private def aggregate[T: ClassTag](f: BlockChain => T)(op: (T, T) => T): T = {
    singleChains.reduceBy { chains =>
      chains.reduceBy(f)(op)
    }(op)
  }

  override def numBlocks: Int = aggregate(_.numBlocks)(_ + _)

  override def numTransactions: Int = aggregate(_.numTransactions)(_ + _)

  override def maxWeight: Int = aggregate(_.maxWeight)(math.max)

  override def maxHeight: Int = aggregate(_.maxHeight)(math.max)

  def getChain(i: Int, j: Int): BlockChain = {
    assert(i >= 0 && i < groups && j >= 0 && j < groups)
    singleChains(i)(j)
  }

  def getChain(chainIndex: ChainIndex): BlockChain = {
    getChain(chainIndex.from, chainIndex.to)
  }

  override def add(block: Block): AddBlockResult = {
    // TODO: check dependencies
    val deps        = block.blockHeader.blockDeps
    val missingDeps = deps.filterNot(contains)
    if (missingDeps.isEmpty) {
      val chainIndex = getIndex(block)
      val chain      = getChain(chainIndex)
      val parent     = block.uncleHash(chainIndex.to)
      val weight     = calWeight(block)
      chain.add(block, parent, weight)
    } else {
      AddBlockResult.MissingDeps(missingDeps :+ block.hash)
    }
  }

  private def calWeight(block: Block): Int = {
    val deps = block.blockHeader.blockDeps
    if (deps.isEmpty) 0
    else {
      val weight1 = deps.dropRight(groups).sumBy(calGroupWeight)
      val weight2 = deps.takeRight(groups).sumBy(getHeight)
      weight1 + weight2 + 1
    }
  }

  private def calGroupWeight(hash: Keccak256): Int = {
    val block = getBlock(hash)
    val deps  = block.blockHeader.blockDeps
    if (deps.isEmpty) 0
    else {
      deps.takeRight(groups).sumBy(getHeight) + 1
    }
  }

  override def getBestTip: Keccak256 = {
    val ordering = Ordering.Int.on[Keccak256](getWeight)
    aggregate(_.getBestTip)(ordering.max)
  }

  override def getAllTips: AVector[Keccak256] = {
    aggregate(_.getAllTips)(_ ++ _)
  }

  def getRtips(tip: Keccak256, from: Int): Array[Keccak256] = {
    val rdeps = new Array[Keccak256](groups)
    rdeps(from) = tip

    val block = getBlock(tip)
    val deps  = block.blockHeader.blockDeps
    if (deps.isEmpty) {
      0 until groups foreach { k =>
        if (k != from) rdeps(k) = initialBlocks(k).head.hash
      }
    } else {
      0 until groups foreach { k =>
        if (k < from) rdeps(k) = deps(k)
        else if (k > from) rdeps(k) = deps(k - 1)
      }
    }
    rdeps
  }

  def isExtending(current: Keccak256, previous: Keccak256): Boolean = {
    val index1 = getIndex(current)
    val index2 = getIndex(previous)
    assert(index1.from == index2.from)

    val chain = getChain(index2)
    if (index1.to == index2.to) chain.isBefore(previous, current)
    else {
      val groupDeps = getGroupDeps(current, index1.from)
      chain.isBefore(previous, groupDeps(index2.to))
    }
  }

  def isCompatible(rtips: IndexedSeq[Keccak256], tip: Keccak256, from: Int): Boolean = {
    val newRtips = getRtips(tip, from)
    assert(rtips.size == newRtips.length)
    rtips.indices forall { k =>
      val t1 = rtips(k)
      val t2 = newRtips(k)
      isExtending(t1, t2) || isExtending(t2, t1)
    }
  }

  def updateRtips(rtips: Array[Keccak256], tip: Keccak256, from: Int): Unit = {
    val newRtips = getRtips(tip, from)
    assert(rtips.length == newRtips.length)
    rtips.indices foreach { k =>
      val t1 = rtips(k)
      val t2 = newRtips(k)
      if (isExtending(t2, t1)) {
        rtips(k) = t2
      }
    }
  }

  def getGroupDeps(tip: Keccak256, from: Int): AVector[Keccak256] = {
    val deps = getBlock(tip).blockHeader.blockDeps
    if (deps.isEmpty) {
      initialBlocks(from).map(_.hash)
    } else {
      deps.takeRight(groups)
    }
  }

  def getBestDeps(chainIndex: ChainIndex): BlockDeps = {
    val bestTip   = getBestTip
    val bestIndex = getIndex(bestTip)
    val rtips     = getRtips(bestTip, bestIndex.from)
    val deps1 = (0 until groups)
      .filter(_ != chainIndex.from)
      .foldLeft(AVector.empty[Keccak256]) {
        case (deps, k) =>
          if (k == bestIndex.from) deps :+ bestTip
          else {
            val toTries = (0 until groups).foldLeft(AVector.empty[Keccak256]) { (acc, l) =>
              acc ++ getChain(k, l).getAllTips
            }
            val validTries = toTries.filter(tip => isCompatible(rtips, tip, k))
            if (validTries.isEmpty) deps :+ rtips(k)
            else {
              val bestTry = validTries.maxBy(getWeight) // TODO: improve
              updateRtips(rtips, bestTry, k)
              deps :+ bestTry
            }
          }
      }
    val groupTip  = rtips(chainIndex.from)
    val groupDeps = getGroupDeps(groupTip, chainIndex.from)
    val deps2 = (0 until groups)
      .foldLeft(deps1) {
        case (deps, l) =>
          val chain      = getChain(chainIndex.from, l)
          val toTries    = chain.getAllTips
          val validTries = toTries.filter(tip => chain.isBefore(groupDeps(l), tip))
          if (validTries.isEmpty) deps :+ groupDeps(l)
          else {
            val bestTry = validTries.maxBy(getWeight) // TODO: improve
            deps :+ bestTry
          }
      }
    BlockDeps(chainIndex, deps2)
  }

  override def getAllBlocks: Iterable[Block] =
    for {
      i     <- 0 until groups
      j     <- 0 until groups
      block <- getChain(i, j).getAllBlocks
    } yield block

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

object BlockFlow {
  def apply()(implicit config: PlatformConfig): BlockFlow = new BlockFlow()

  case class BlockInfo(timestamp: Long, chainIndex: ChainIndex)
}
