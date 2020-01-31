package org.alephium.flow.core

import org.alephium.crypto.Keccak256
import org.alephium.flow.io.{IOResult, IOUtils}
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model._
import org.alephium.util.AVector

class BlockFlow()(implicit val config: PlatformProfile)
    extends MultiChain
    with BlockFlowState
    with FlowUtils {
  def add(block: Block): IOResult[Unit] = {
    val index = block.chainIndex
    assert(index.relateTo(config.brokerInfo))
    val chain  = getBlockChain(index)
    val parent = block.uncleHash(index.to) // equal to parentHash

    cacheBlock(block)
    for {
      weight <- calWeight(block)
      _      <- chain.add(block, parent, weight)
      _      <- calBestDeps()
    } yield ()
  }

  def add(block: Block, weight: Int): IOResult[Unit] = {
    ???
  }

  def add(block: Block, parentHash: Keccak256, weight: Int): IOResult[Unit] = {
    ???
  }

  def add(header: BlockHeader): IOResult[Unit] = {
    val index  = header.chainIndex
    val chain  = getHeaderChain(index)
    val parent = header.uncleHash(index.to)
    for {
      weight <- calWeight(header)
      _      <- chain.add(header, parent, weight)
      _      <- calBestDeps()
    } yield ()
  }

  def add(header: BlockHeader, weight: Int): IOResult[Unit] = {
    ???
  }

  def add(header: BlockHeader, parentHash: Keccak256, weight: Int): IOResult[Unit] = {
    ???
  }

  private def calWeight(block: Block): IOResult[Int] = {
    calWeight(block.header)
  }

  private def calWeight(header: BlockHeader): IOResult[Int] = {
    IOUtils.tryExecute(calWeightUnsafe(header))
  }

  private def calWeightUnsafe(header: BlockHeader): Int = {
    if (header.isGenesis) 0
    else {
      val weight1 = header.inDeps.sumBy(calGroupWeightUnsafe)
      val weight2 = header.outDeps.sumBy(getHeight)
      weight1 + weight2 + 1
    }
  }

  private def calGroupWeightUnsafe(hash: Keccak256): Int = {
    val header = getBlockHeaderUnsafe(hash)
    if (header.isGenesis) 0
    else {
      header.outDeps.sumBy(getHeight) + 1
    }
  }

  override def getBestTip: Keccak256 = {
    val ordering = Ordering.Int.on[Keccak256](getWeight)
    aggregate(_.getBestTip)(ordering.max)
  }

  override def getAllTips: AVector[Keccak256] = {
    aggregate(_.getAllTips)(_ ++ _)
  }

  def getOutBlockTips(brokerInfo: BrokerInfo): AVector[Keccak256] = {
    val (low, high) = brokerInfo.calIntersection(config.brokerInfo)
    assert(low < high)

    var tips = AVector.empty[Keccak256]
    for {
      from <- low until high
      to   <- 0 until groups
    } tips = tips ++ getHashChain(GroupIndex(from), GroupIndex(to)).getAllTips
    tips
  }

  // Rtips means tip representatives for all groups
  private def getRtipsUnsafe(tip: Keccak256, from: GroupIndex): Array[Keccak256] = {
    val rdeps = new Array[Keccak256](groups)
    rdeps(from.value) = tip

    val header = getBlockHeaderUnsafe(tip)
    val deps   = header.blockDeps
    if (header.isGenesis) {
      0 until groups foreach { k =>
        if (k != from.value) rdeps(k) = config.genesisBlocks(k).head.hash
      }
    } else {
      0 until groups foreach { k =>
        if (k < from.value) rdeps(k) = deps(k)
        else if (k > from.value) rdeps(k) = deps(k - 1)
      }
    }
    rdeps
  }

  private def isExtending(current: Keccak256, previous: Keccak256): Boolean = {
    val index1 = ChainIndex.from(current)
    val index2 = ChainIndex.from(previous)
    assert(index1.from == index2.from)

    val chain = getHashChain(index2)
    if (index1.to == index2.to) chain.isBefore(previous, current)
    else {
      val groupDeps = getGroupDepsUnsafe(current, index1.from)
      chain.isBefore(previous, groupDeps(index2.to.value))
    }
  }

  private def isCompatibleUnsafe(rtips: IndexedSeq[Keccak256],
                                 tip: Keccak256,
                                 from: GroupIndex): Boolean = {
    val newRtips = getRtipsUnsafe(tip, from)
    assert(rtips.length == newRtips.length)
    rtips.indices forall { k =>
      val t1 = rtips(k)
      val t2 = newRtips(k)
      isExtending(t1, t2) || isExtending(t2, t1)
    }
  }

  private def updateRtipsUnsafe(rtips: Array[Keccak256], tip: Keccak256, from: GroupIndex): Unit = {
    val newRtips = getRtipsUnsafe(tip, from)
    assert(rtips.length == newRtips.length)
    rtips.indices foreach { k =>
      val t1 = rtips(k)
      val t2 = newRtips(k)
      if (isExtending(t2, t1)) {
        rtips(k) = t2
      }
    }
  }

  private def getGroupDepsUnsafe(tip: Keccak256, from: GroupIndex): AVector[Keccak256] = {
    val header = getBlockHeaderUnsafe(tip)
    if (header.isGenesis) {
      config.genesisBlocks(from.value).map(_.hash)
    } else {
      header.outDeps
    }
  }

  def calBestDepsUnsafe(group: GroupIndex): BlockDeps = {
    val bestTip   = getBestTip
    val bestIndex = ChainIndex.from(bestTip)
    val rtips     = getRtipsUnsafe(bestTip, bestIndex.from)
    val deps1 = (0 until groups)
      .filter(_ != group.value)
      .foldLeft(AVector.empty[Keccak256]) {
        case (deps, _k) =>
          val k = GroupIndex(_k)
          if (k == bestIndex.from) deps :+ bestTip
          else {
            val toTries = (0 until groups).foldLeft(AVector.empty[Keccak256]) { (acc, _l) =>
              val l = GroupIndex(_l)
              acc ++ getHashChain(k, l).getAllTips
            }
            val validTries = toTries.filter(tip => isCompatibleUnsafe(rtips, tip, k))
            if (validTries.isEmpty) deps :+ rtips(k.value)
            else {
              val bestTry = validTries.maxBy(getWeight) // TODO: improve
              updateRtipsUnsafe(rtips, bestTry, k)
              deps :+ bestTry
            }
          }
      }
    val groupTip  = rtips(group.value)
    val groupDeps = getGroupDepsUnsafe(groupTip, group)
    val deps2 = (0 until groups)
      .foldLeft(deps1) {
        case (deps, _l) =>
          val l          = GroupIndex(_l)
          val chain      = getHashChain(group, l)
          val toTries    = chain.getAllTips
          val validTries = toTries.filter(tip => chain.isBefore(groupDeps(l.value), tip))
          if (validTries.isEmpty) deps :+ groupDeps(l.value)
          else {
            val bestTry = validTries.maxBy(getWeight) // TODO: improve
            deps :+ bestTry
          }
      }
    BlockDeps(deps2)
  }

  def calBestDepsUnsafe(): Unit =
    brokerInfo.groupFrom until brokerInfo.groupUntil foreach { mainGroup =>
      val deps = calBestDepsUnsafe(GroupIndex(mainGroup))
      updateBestDeps(mainGroup, deps)
    }

  def calBestDeps(): IOResult[Unit] = {
    IOUtils.tryExecute(calBestDepsUnsafe())
  }
}

object BlockFlow {
  def createUnsafe()(implicit config: PlatformProfile): BlockFlow = new BlockFlow()

  case class BlockInfo(timestamp: Long, chainIndex: ChainIndex)
}
