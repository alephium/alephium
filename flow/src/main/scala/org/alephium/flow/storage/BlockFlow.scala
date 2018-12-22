package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.{IOError, IOResult}
import org.alephium.flow.model.{BlockDeps, ValidationError}
import org.alephium.protocol.model.{Block, BlockHeader, ChainIndex, GroupIndex}
import org.alephium.util.AVector

class BlockFlow()(implicit val config: PlatformConfig)
    extends MultiChain
    with BlockFlowState
    with FlowUtils {

  def add(block: Block): IOResult[Unit] = {
    val index  = block.chainIndex
    val chain  = getBlockChain(index)
    val parent = block.uncleHash(index.to)
    val weight = calWeightUnsafe(block)
    chain.add(block, parent, weight).map { _ =>
      updateStateFor(block)
    }
  }

  private def updateStateFor(block: Block): Unit = {
    val bestDeps = calBestDepsUnsafe()
    updateBestDeps(bestDeps)
    updateUTXOs(block)
  }

  def validate(block: Block): Either[ValidationError, Unit] = {
    validate(block.header, fromBlock = true)
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
    try {
      val weight = calWeightUnsafe(header)
      chain.add(header, parent, weight).map { _ =>
        updateStateForNewHeader()
      }
    } catch {
      case e: Exception =>
        Left(IOError.from(e))
    }
  }

  private def updateStateForNewHeader(): Unit = {
    val bestDeps = calBestDepsUnsafe()
    updateBestDeps(bestDeps)
  }

  def validate(header: BlockHeader): Either[ValidationError, Unit] = {
    validate(header, fromBlock = false)
  }

  def validate(header: BlockHeader, fromBlock: Boolean): Either[ValidationError, Unit] = {
    val index = header.chainIndex
    if (fromBlock ^ index.relateTo(mainGroup)) {
      // fromBlock = true, relate = false; fromBlock = false, relate = true
      Left(ValidationError.InvalidGroup)
    } else if (!index.validateDiff(header)) {
      Left(ValidationError.InvalidDifficulty)
    } else {
      val deps        = header.blockDeps
      val missingDeps = deps.filterNot(contains)
      if (missingDeps.isEmpty) Right(())
      else Left(ValidationError.MissingDeps(missingDeps))
    }
  }

  def add(header: BlockHeader, weight: Int): IOResult[Unit] = {
    ???
  }

  def add(header: BlockHeader, parentHash: Keccak256, weight: Int): IOResult[Unit] = {
    ???
  }

  private def calWeightUnsafe(block: Block): Int = {
    calWeightUnsafe(block.header)
  }

  private def calWeightUnsafe(header: BlockHeader): Int = {
    val deps = header.blockDeps
    if (deps.isEmpty) 0
    else {
      val weight1 = deps.dropRight(groups).sumBy(calGroupWeightUnsafe)
      val weight2 = deps.takeRight(groups).sumBy(getHeight)
      weight1 + weight2 + 1
    }
  }

  private def calGroupWeightUnsafe(hash: Keccak256): Int = {
    val deps = getBlockHeaderUnsafe(hash).blockDeps
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

  private def getRtipsUnsafe(tip: Keccak256, from: GroupIndex): Array[Keccak256] = {
    val rdeps = new Array[Keccak256](groups)
    rdeps(from.value) = tip

    val header = getBlockHeaderUnsafe(tip)
    val deps   = header.blockDeps
    if (deps.isEmpty) {
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
    assert(rtips.size == newRtips.length)
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
    val deps = getBlockHeaderUnsafe(tip).blockDeps
    if (deps.isEmpty) {
      config.genesisBlocks(from.value).map(_.hash)
    } else {
      deps.takeRight(groups)
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

  def calBestDepsUnsafe(): BlockDeps = calBestDepsUnsafe(config.mainGroup)

  def calBestDeps(): IOResult[BlockDeps] =
    try {
      Right(calBestDepsUnsafe())
    } catch {
      case e: Exception => Left(IOError.from(e))
    }
}

object BlockFlow {
  def createUnsafe()(implicit config: PlatformConfig): BlockFlow = new BlockFlow()

  case class BlockInfo(timestamp: Long, chainIndex: ChainIndex)
}
