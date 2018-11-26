package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockDeps
import org.alephium.protocol.model.{Block, BlockHeader, ChainIndex, GroupIndex}
import org.alephium.util.AVector

import scala.reflect.ClassTag
import scala.util.Try

class BlockFlow(val diskIO: DiskIO)(implicit val config: PlatformConfig) extends MultiChain {
  import config.genesisBlocks

  private def mainGroup: GroupIndex = config.mainGroup

  override val groups = config.groups

  private val inBlockChains: AVector[BlockChain] = AVector.tabulate(groups - 1) { k =>
    BlockChain.fromGenesis(genesisBlocks(if (k < mainGroup.value) k else k + 1)(mainGroup.value))
  }
  private val outBlockChains: AVector[BlockChain] = AVector.tabulate(groups) { to =>
    BlockChain.fromGenesis(genesisBlocks(mainGroup.value)(to))
  }
  private val blockHeaderChains: AVector[AVector[BlockHeaderPool with BlockHashChain]] =
    AVector.tabulate(groups, groups) {
      case (from, to) =>
        if (from == mainGroup.value) outBlockChains(to)
        else if (to == mainGroup.value) {
          inBlockChains(if (from < mainGroup.value) from else from - 1)
        } else BlockHeaderChain.fromGenesis(genesisBlocks(from)(to))
    }

  override protected def aggregate[T: ClassTag](f: BlockHashPool => T)(op: (T, T) => T): T = {
    blockHeaderChains.reduceBy { chains =>
      chains.reduceBy(f)(op)
    }(op)
  }

  override def numTransactions: Int = {
    inBlockChains.sumBy(_.numTransactions) + outBlockChains.sumBy(_.numTransactions)
  }

  override protected def getBlockChain(from: GroupIndex, to: GroupIndex): BlockChain = {
    assert(from == mainGroup || to == mainGroup)
    if (from == mainGroup) outBlockChains(to.value)
    else inBlockChains(if (from.value < mainGroup.value) from.value else from.value - 1)
  }

  override protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderPool = {
    blockHeaderChains(from.value)(to.value)
  }

  override protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain = {
    blockHeaderChains(from.value)(to.value)
  }

  def add(block: Block): AddBlockResult = {
    if (contains(block.hash)) {
      AddBlockResult.AlreadyExisted
    } else {
      val index = block.chainIndex
      val result = for {
        _ <- checkCompleteness(block)
        _ <- validate(block, index)
      } yield add(block, index)
      result.fold(identity, _ => AddBlockResult.Success)
    }
  }

  def checkCompleteness(block: Block): Either[AddBlockResult.Incomplete, Unit] = {
    checkCompleteness(block.header).left.map(AddBlockResult.HeaderIncomplete)
  }

  def validate(block: Block, index: ChainIndex): Either[AddBlockResult.Error, Unit] = {
    if (index.from != mainGroup && index.to != mainGroup) {
      Left(AddBlockResult.HeaderError(AddBlockHeaderResult.InvalidGroup))
    } else if (!index.validateDiff(block)) {
      Left(AddBlockResult.HeaderError(AddBlockHeaderResult.InvalidDifficulty))
    } else {
      Right(())
    }
  }

  protected def add(block: Block, index: ChainIndex): Either[AddBlockResult, Unit] = {
    val chain  = getBlockChain(index)
    val parent = block.uncleHash(index.to)
    val weight = calWeightUnsafe(block)
    chain.add(block, parent, weight).left.map(AddBlockResult.IOError)
  }

  def add(block: Block, weight: Int): IOResult[Unit] = {
    ???
  }

  def add(block: Block, parentHash: Keccak256, weight: Int): IOResult[Unit] = {
    ???
  }

  def add(header: BlockHeader): AddBlockHeaderResult = {
    if (contains(header.hash)) {
      AddBlockHeaderResult.AlreadyExisted
    } else {
      val index = header.chainIndex
      val result = for {
        _ <- checkCompleteness(header)
        _ <- validate(header, index)
      } yield add(header, index)
      result.fold(identity, _ => AddBlockHeaderResult.Success)
    }
  }

  def validate(header: BlockHeader,
               index: ChainIndex): Either[AddBlockHeaderResult.VerificationError, Unit] = {
    if (index.from == mainGroup || index.to == mainGroup) {
      Left(AddBlockHeaderResult.InvalidGroup)
    } else if (!index.validateDiff(header)) {
      Left(AddBlockHeaderResult.InvalidDifficulty)
    } else Right(())
  }

  def checkCompleteness(header: BlockHeader): Either[AddBlockHeaderResult.Incomplete, Unit] = {
    val deps        = header.blockDeps
    val missingDeps = deps.filterNot(contains)
    if (missingDeps.isEmpty) Right(())
    else Left(AddBlockHeaderResult.MissingDeps(missingDeps))
  }

  protected def add(header: BlockHeader, index: ChainIndex): IOResult[Unit] = {
    val chain  = getHeaderChain(index)
    val parent = header.uncleHash(index.to)
    try {
      val weight = calWeightUnsafe(header)
      chain.add(header, parent, weight)
    } catch {
      case e: Exception =>
        Left(IOError.from(e))
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
        if (k != from.value) rdeps(k) = genesisBlocks(k).head.hash
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

  private def updateRtips(rtips: Array[Keccak256], tip: Keccak256, from: GroupIndex): Unit = {
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
      genesisBlocks(from.value).map(_.hash)
    } else {
      deps.takeRight(groups)
    }
  }

  def getBestDepsUnsafe(chainIndex: ChainIndex): BlockDeps = {
    val bestTip   = getBestTip
    val bestIndex = ChainIndex.from(bestTip)
    val rtips     = getRtipsUnsafe(bestTip, bestIndex.from)
    val deps1 = (0 until groups)
      .filter(_ != chainIndex.from.value)
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
              updateRtips(rtips, bestTry, k)
              deps :+ bestTry
            }
          }
      }
    val groupTip  = rtips(chainIndex.from.value)
    val groupDeps = getGroupDepsUnsafe(groupTip, chainIndex.from)
    val deps2 = (0 until groups)
      .foldLeft(deps1) {
        case (deps, _l) =>
          val l          = GroupIndex(_l)
          val chain      = getHashChain(chainIndex.from, l)
          val toTries    = chain.getAllTips
          val validTries = toTries.filter(tip => chain.isBefore(groupDeps(l.value), tip))
          if (validTries.isEmpty) deps :+ groupDeps(l.value)
          else {
            val bestTry = validTries.maxBy(getWeight) // TODO: improve
            deps :+ bestTry
          }
      }
    BlockDeps(chainIndex, deps2)
  }

  def getBestDeps(chainIndex: ChainIndex): Either[Throwable, BlockDeps] =
    Try {
      getBestDepsUnsafe(chainIndex)
    }.toEither
}

object BlockFlow {
  def apply()(implicit config: PlatformConfig): BlockFlow = {
    new BlockFlow(config.diskIO)
  }

  case class BlockInfo(timestamp: Long, chainIndex: ChainIndex)
}
