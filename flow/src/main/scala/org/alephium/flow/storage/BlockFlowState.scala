package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.IOResult
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.storage.BlockFlowState.{BlockCache, InBlockCache, InOutBlockCache, OutBlockCache}
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.model._
import org.alephium.util.{AVector, EitherF}

import scala.reflect.ClassTag

trait BlockFlowState {
  implicit def config: PlatformConfig

  val brokerInfo: BrokerInfo = config.brokerInfo

  val groups: Int = config.groups

  private val bestDeps = Array.tabulate(config.groupNumPerBroker) { fromShift =>
    val mainGroup = brokerInfo.groupFrom + fromShift
    val deps1 = AVector.tabulate(groups - 1) { i =>
      if (i < mainGroup) config.genesisBlocks(i).head.hash
      else config.genesisBlocks(i + 1).head.hash
    }
    val deps2 = config.genesisBlocks(mainGroup).map(_.hash)
    BlockDeps(deps1 ++ deps2)
  }

  private val intraGroupChains: AVector[BlockChainWithState] = {
    AVector.tabulate(config.groupNumPerBroker) { group =>
      BlockChainWithState.fromGenesisUnsafe(config.genesisBlocks(group)(group), updateState)
    }
  }

  private val inBlockChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(config.groupNumPerBroker, groups - config.groupNumPerBroker) { (toShift, k) =>
      val mainGroup = brokerInfo.groupFrom + toShift
      val fromIndex = if (k < brokerInfo.groupFrom) k else k + config.groupNumPerBroker
      BlockChain.fromGenesisUnsafe(config.genesisBlocks(fromIndex)(mainGroup))
    }
  private val outBlockChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(config.groupNumPerBroker, groups) { (fromShift, to) =>
      val mainGroup = brokerInfo.groupFrom + fromShift
      if (mainGroup == to) {
        intraGroupChains(mainGroup)
      } else {
        BlockChain.fromGenesisUnsafe(config.genesisBlocks(mainGroup)(to))
      }
    }
  private val blockHeaderChains: AVector[AVector[BlockHeaderPool with BlockHashChain]] =
    AVector.tabulate(groups, groups) {
      case (from, to) =>
        if (brokerInfo.containsRaw(from)) {
          val fromShift = from - brokerInfo.groupFrom
          outBlockChains(fromShift)(to)
        } else if (brokerInfo.containsRaw(to)) {
          val toShift   = to - brokerInfo.groupFrom
          val fromIndex = if (from < brokerInfo.groupFrom) from else from - config.groupNumPerBroker
          inBlockChains(toShift)(fromIndex)
        } else BlockHeaderChain.fromGenesisUnsafe(config.genesisBlocks(from)(to))
    }

  // Cache latest blocks for assisting merkle trie
  private val inblockCashes    = collection.mutable.HashMap.empty[Keccak256, InBlockCache]
  private val outblockCashes   = collection.mutable.HashMap.empty[Keccak256, OutBlockCache]
  private val inoutblockCashes = collection.mutable.HashMap.empty[Keccak256, InOutBlockCache]

  def getBlockCache(hash: Keccak256): BlockCache = {
    assert(
      inblockCashes.contains(hash) || outblockCashes.contains(hash) || inoutblockCashes.contains(
        hash))
    if (inblockCashes.contains(hash)) {
      inblockCashes(hash)
    } else if (outblockCashes.contains(hash)) {
      outblockCashes(hash)
    } else {
      inoutblockCashes(hash)
    }
  }

  def isUtxoAvailableInCashe(utxo: TxOutputPoint): Boolean = {
    inblockCashes.values.exists(_.outputs.contains(utxo))
  }

  def isUtxoSpentInCashe(utxo: TxOutputPoint): Boolean = {
    outblockCashes.values.exists(_.inputs.contains(utxo))
  }

  protected def aggregate[T: ClassTag](f: BlockHashPool => T)(op: (T, T) => T): T = {
    blockHeaderChains.reduceBy { chains =>
      chains.reduceBy(f)(op)
    }(op)
  }

  def getBlockChain(hash: Keccak256): BlockChain

  protected def getBlockChain(from: GroupIndex, to: GroupIndex): BlockChain = {
    assert(brokerInfo.contains(from) || brokerInfo.contains(to))
    if (brokerInfo.contains(from)) {
      outBlockChains(from.value - brokerInfo.groupFrom)(to.value)
    } else {
      val fromIndex =
        if (from.value < brokerInfo.groupFrom) from.value
        else from.value - config.groupNumPerBroker
      val toShift = to.value - brokerInfo.groupFrom
      inBlockChains(toShift)(fromIndex)
    }
  }

  protected def getBlockChainWithState(group: GroupIndex): BlockChainWithState = {
    assert(brokerInfo.contains(group))
    intraGroupChains(group.value)
  }

  protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderPool = {
    blockHeaderChains(from.value)(to.value)
  }

  protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain = {
    blockHeaderChains(from.value)(to.value)
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps = {
    val groupShift = groupIndex.value - brokerInfo.groupFrom
    bestDeps(groupShift)
  }

  def getBestTrie(groupIndex: GroupIndex): MerklePatriciaTrie = {
    assert(config.brokerInfo.contains(groupIndex))
    val deps = getBestDeps(groupIndex)
    val hash = deps.deps(groupIndex.value)
    getBlockChainWithState(groupIndex).getTrie(hash)
  }

  def updateBestDeps(mainGroup: Int, deps: BlockDeps): Unit = {
    assert(brokerInfo.containsRaw(mainGroup))
    val groupShift = mainGroup - brokerInfo.groupFrom
    bestDeps(groupShift) = deps
  }

  def getBlockHeader(hash: Keccak256): IOResult[BlockHeader]

  def getIntraGroupDepHash(block: Block, groupIndex: GroupIndex): IOResult[Keccak256] = {
    val index = block.chainIndex
    if (index.isIntraGroup) {
      Right(block.header.uncleHash(groupIndex))
    } else {
      val deps = block.header.blockDeps
      val depOfTheGroup = if (index.from.value > groupIndex.value) {
        deps(groupIndex.value)
      } else {
        deps(groupIndex.value - 1)
      }
      getBlockHeader(depOfTheGroup).map(_.uncleHash(groupIndex))
    }
  }

  def getInOutTips(header: BlockHeader, currentGroup: GroupIndex): IOResult[AVector[Keccak256]] = {
    val inDeps  = header.inDeps
    val outDeps = header.outDeps
    inDeps.traverse(getBlockHeader).map(_.map(_.uncleHash(currentGroup))).map(_ ++ outDeps)
  }

  def getInOutTips(hash: Keccak256, currentGroup: GroupIndex): IOResult[AVector[Keccak256]] = {
    getBlockHeader(hash).flatMap(getInOutTips(_, currentGroup))
  }

  def getTipsDiff(newTip: Keccak256, oldTip: Keccak256): AVector[Keccak256] = {
    getBlockChain(newTip).getBlockHashesBetween(newTip, oldTip)
  }

  protected def getTipsDiff(newTips: AVector[Keccak256],
                            oldTips: AVector[Keccak256]): AVector[Keccak256] = {
    assert(newTips.length == oldTips.length)
    (0 to newTips.length).foldLeft(AVector.empty[Keccak256]) { (acc, i) =>
      acc ++ getTipsDiff(newTips(i), oldTips(i))
    }
  }

  protected def getBlocksForUpdates(block: Block): IOResult[AVector[BlockCache]] = {
    val chainIndex = block.chainIndex
    assert(chainIndex.isIntraGroup)
    for {
      newTips <- getInOutTips(block.header, chainIndex.from)
      oldTips <- getInOutTips(block.parentHash, chainIndex.from)
    } yield {
      val newHashes = getTipsDiff(newTips, oldTips)
      newHashes.map(getBlockCache)
    }
  }

  def updateState(trie: MerklePatriciaTrie, block: Block): IOResult[MerklePatriciaTrie] = {
    val index = block.chainIndex
    assert(index.isIntraGroup)
    getIntraGroupDepHash(block, index.from).flatMap { intraGroupDepHash =>
      if (index.isIntraGroup) {
        getBlocksForUpdates(block).flatMap { blocks =>
          blocks.foldF(trie)(updateState1)
        }
      } else {
        Right(trie)
      }
    }
  }

  private def updateState1(trie: MerklePatriciaTrie,
                           outputs: Map[TxOutputPoint, TxOutput]): IOResult[MerklePatriciaTrie] = {
    EitherF.fold(outputs, trie) {
      case (trie0, (outputPoint, output)) =>
        trie0.put(outputPoint, output)
    }
  }

  private def updateState1(trie: MerklePatriciaTrie,
                           inputs: Set[TxOutputPoint]): IOResult[MerklePatriciaTrie] = {
    EitherF.fold(inputs, trie)(_.remove(_))
  }

  private def updateState1(trie: MerklePatriciaTrie,
                           blockCache: BlockCache): IOResult[MerklePatriciaTrie] = {
    blockCache match {
      case InBlockCache(outputs) =>
        updateState1(trie, outputs)
      case OutBlockCache(inputs) =>
        updateState1(trie, inputs)
      case InOutBlockCache(outputs, inputs) =>
        for {
          trie0 <- updateState1(trie, outputs)
          trie1 <- updateState1(trie0, inputs)
        } yield trie1
    }
  }
}

object BlockFlowState {
  sealed trait BlockCache
  case class InBlockCache(outputs: Map[TxOutputPoint, TxOutput]) extends BlockCache
  case class OutBlockCache(inputs: Set[TxOutputPoint])           extends BlockCache
  case class InOutBlockCache(outputs: Map[TxOutputPoint, TxOutput], inputs: Set[TxOutputPoint])
      extends BlockCache // For blocks on intra-group chain
}
