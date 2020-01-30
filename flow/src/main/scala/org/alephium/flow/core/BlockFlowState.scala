package org.alephium.flow.core

import scala.reflect.ClassTag

import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.flow.io.IOResult
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.platform.PlatformProfile
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.model._
import org.alephium.protocol.script.PubScript
import org.alephium.serde.serialize
import org.alephium.util.{AVector, ConcurrentHashMap, ConcurrentQueue, EitherF}

trait BlockFlowState {
  import BlockFlowState._

  implicit def config: PlatformProfile

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
    AVector.tabulate(config.groupNumPerBroker) { groupShift =>
      val group = brokerInfo.groupFrom + groupShift
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
        intraGroupChains(fromShift)
      } else {
        BlockChain.fromGenesisUnsafe(config.genesisBlocks(mainGroup)(to))
      }
    }
  private val blockHeaderChains: AVector[AVector[BlockHeaderChain]] =
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
  private val groupCaches = AVector.fill(config.groupNumPerBroker)(GroupCache.empty)

  def getGroupCache(groupIndex: GroupIndex): GroupCache = {
    assert(brokerInfo.contains(groupIndex))
    groupCaches(groupIndex.value - brokerInfo.groupFrom)
  }

  def cacheBlock(block: Block): Unit = {
    val index = block.chainIndex
    (brokerInfo.groupFrom until brokerInfo.groupUntil).foreach { group =>
      val groupIndex = GroupIndex(group)
      val groupCache = getGroupCache(groupIndex)
      if (index.relateTo(groupIndex)) {
        convertBlock(block, groupIndex) match {
          case c: InBlockCache    => groupCache.inblockcaches.add(block.hash, c)
          case c: OutBlockCache   => groupCache.outblockcaches.add(block.hash, c)
          case c: InOutBlockCache => groupCache.inoutblockcaches.add(block.hash, c)
        }
        groupCache.cachedHashes.enqueue(block.hash)
        pruneCaches(groupCache)
      }
    }
  }

  protected def pruneCaches(groupCache: GroupCache): Unit = {
    import groupCache._
    if (cachedHashes.length > config.blockCacheSize) {
      val toRemove = cachedHashes.dequeue
      inblockcaches.removeIfExist(toRemove)
      outblockcaches.removeIfExist(toRemove)
      inoutblockcaches.removeIfExist(toRemove)
      assert(cachedHashes.length <= config.blockCacheSize)
    }
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
    intraGroupChains(group.value - brokerInfo.groupFrom)
  }

  protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderChain = {
    blockHeaderChains(from.value)(to.value)
  }

  protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain = {
    blockHeaderChains(from.value)(to.value)
  }

  private def getTrie(deps: AVector[Keccak256], groupIndex: GroupIndex): MerklePatriciaTrie = {
    assert(deps.length == 2 * config.groups - 1)
    val hash = deps(config.groups - 1 + groupIndex.value)
    getBlockChainWithState(groupIndex).getTrie(hash)
  }

  def getTrie(block: Block): MerklePatriciaTrie = {
    val header = block.header
    getTrie(header.blockDeps, header.chainIndex.from)
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps = {
    val groupShift = groupIndex.value - brokerInfo.groupFrom
    bestDeps(groupShift)
  }

  def getBestTrie(groupIndex: GroupIndex): MerklePatriciaTrie = {
    assert(config.brokerInfo.contains(groupIndex))
    val deps = getBestDeps(groupIndex)
    getTrie(deps.deps, groupIndex)
  }

  def updateBestDeps(mainGroup: Int, deps: BlockDeps): Unit = {
    assert(brokerInfo.containsRaw(mainGroup))
    val groupShift = mainGroup - brokerInfo.groupFrom
    bestDeps(groupShift) = deps
  }

  def getBlockHeader(hash: Keccak256): IOResult[BlockHeader]

  def getOutTips(header: BlockHeader, inclusive: Boolean): AVector[Keccak256] = {
    val index = header.chainIndex
    if (header.isGenesis) {
      config.genesisBlocks(index.from.value).map(_.hash)
    } else {
      if (inclusive) {
        header.outDeps.replace(index.to.value, header.hash)
      } else {
        header.outDeps
      }
    }
  }

  def getInTip(dep: Keccak256, currentGroup: GroupIndex): IOResult[Keccak256] = {
    getBlockHeader(dep).map { header =>
      val from = header.chainIndex.from
      if (header.isGenesis) config.genesisBlocks(from.value)(currentGroup.value).hash
      else header.uncleHash(currentGroup)
    }
  }

  // if inclusive is true, the current header would be included
  def getInOutTips(header: BlockHeader,
                   currentGroup: GroupIndex,
                   inclusive: Boolean): IOResult[AVector[Keccak256]] = {
    if (header.isGenesis) {
      val inTips = AVector.tabulate(groups - 1) { i =>
        if (i < currentGroup.value) config.genesisBlocks(i)(currentGroup.value).hash
        else config.genesisBlocks(i + 1)(currentGroup.value).hash
      }
      val outTips = config.genesisBlocks(currentGroup.value).map(_.hash)
      Right(inTips ++ outTips)
    } else {
      val outTips = getOutTips(header, inclusive)
      header.inDeps.mapE(getInTip(_, currentGroup)).map(_ ++ outTips)
    }
  }

  def getInOutTips(hash: Keccak256,
                   currentGroup: GroupIndex,
                   inclusive: Boolean): IOResult[AVector[Keccak256]] = {
    getBlockHeader(hash).flatMap(getInOutTips(_, currentGroup, inclusive))
  }

  def getTipsDiff(newTip: Keccak256, oldTip: Keccak256): AVector[Keccak256] = {
    getBlockChain(oldTip).getBlockHashesBetween(newTip, oldTip)
  }

  protected def getTipsDiff(newTips: AVector[Keccak256],
                            oldTips: AVector[Keccak256]): AVector[Keccak256] = {
    assert(newTips.length == oldTips.length)
    newTips.indices.foldLeft(AVector.empty[Keccak256]) { (acc, i) =>
      acc ++ getTipsDiff(newTips(i), oldTips(i))
    }
  }

  protected def getBlocksForUpdates(block: Block): IOResult[AVector[BlockCache]] = {
    val chainIndex = block.chainIndex
    assert(chainIndex.isIntraGroup)
    val groupOffset = chainIndex.from.value - brokerInfo.groupFrom
    val groupCache  = groupCaches(groupOffset)
    for {
      newTips <- getInOutTips(block.header, chainIndex.from, inclusive     = false)
      oldTips <- getInOutTips(block.parentHash, chainIndex.from, inclusive = true)
    } yield {
      val newHashes = getTipsDiff(newTips, oldTips) :+ block.hash
      newHashes.map(groupCache.getBlockCache)
    }
  }

  // Note: update state only for intra group blocks
  def updateState(trie: MerklePatriciaTrie, block: Block): IOResult[MerklePatriciaTrie] = {
    if (block.header.isGenesis) {
      val chainIndex = block.chainIndex
      assert(chainIndex.isIntraGroup)
      val cache = BlockFlowState.convertBlock(block, chainIndex.from)
      BlockFlowState.updateState(trie, cache)
    } else {
      getBlocksForUpdates(block).flatMap { blockcaches =>
        blockcaches.foldE(trie)(BlockFlowState.updateState)
      }
    }
  }

  def getP2pkhBalances(address: ED25519PublicKey): IOResult[AVector[(TxOutputPoint, TxOutput)]] = {
    val pubScript  = PubScript.p2pkh(address)
    val groupIndex = GroupIndex.from(pubScript)
    assert(config.brokerInfo.contains(groupIndex))
    val prefix = serialize(pubScript.shortKey)
    getBestTrie(groupIndex).getAll[TxOutputPoint, TxOutput](prefix).map { data =>
      data.filter(_._2.pubScript == pubScript)
    }
  }
}

object BlockFlowState {
  sealed trait BlockCache
  case class InBlockCache(outputs: Map[TxOutputPoint, TxOutput]) extends BlockCache
  case class OutBlockCache(inputs: Set[TxOutputPoint])           extends BlockCache
  case class InOutBlockCache(outputs: Map[TxOutputPoint, TxOutput], inputs: Set[TxOutputPoint])
      extends BlockCache // For blocks on intra-group chain

  private def convertInputs(block: Block): Set[TxOutputPoint] = {
    block.transactions.flatMap(_.raw.inputs).toIterable.toSet
  }

  private def convertOutputs(block: Block): Map[TxOutputPoint, TxOutput] = {
    val outputs = block.transactions.flatMap { transaction =>
      transaction.raw.outputs.mapWithIndex { (output, i) =>
        val outputPoint = TxOutputPoint(output.shortKey, transaction.hash, i)
        (outputPoint, output)
      }
    }
    outputs.toIterable.toMap
  }

  def convertBlock(block: Block, groupIndex: GroupIndex)(
      implicit config: PlatformProfile): BlockCache = {
    val index = block.chainIndex
    assert(index.relateTo(groupIndex))
    if (index.isIntraGroup) {
      InOutBlockCache(convertOutputs(block), convertInputs(block))
    } else if (index.from == groupIndex) {
      OutBlockCache(convertInputs(block))
    } else {
      InBlockCache(convertOutputs(block))
    }
  }

  class GroupCache(
      val inblockcaches: ConcurrentHashMap[Keccak256, InBlockCache],
      val outblockcaches: ConcurrentHashMap[Keccak256, OutBlockCache],
      val inoutblockcaches: ConcurrentHashMap[Keccak256, InOutBlockCache],
      val cachedHashes: ConcurrentQueue[Keccak256]
  ) {
    def getBlockCache(hash: Keccak256): BlockCache = {
      assert(
        inblockcaches.contains(hash) ||
          outblockcaches.contains(hash) ||
          inoutblockcaches.contains(hash))

      if (inblockcaches.contains(hash)) {
        inblockcaches(hash)
      } else if (outblockcaches.contains(hash)) {
        outblockcaches(hash)
      } else {
        inoutblockcaches(hash)
      }
    }

    def isUtxoAvailableIncache(utxo: TxOutputPoint): Boolean = {
      inblockcaches.values.exists(_.outputs.contains(utxo)) ||
      inoutblockcaches.values.exists(_.outputs.contains(utxo))
    }

    def isUtxoSpentIncache(utxo: TxOutputPoint): Boolean = {
      outblockcaches.values.exists(_.inputs.contains(utxo)) ||
      inoutblockcaches.values.exists(_.inputs.contains(utxo))
    }
  }

  object GroupCache {
    def empty: GroupCache = new GroupCache(
      ConcurrentHashMap.empty,
      ConcurrentHashMap.empty,
      ConcurrentHashMap.empty,
      ConcurrentQueue.empty
    )
  }

  def updateStateForOutputs(
      trie: MerklePatriciaTrie,
      outputs: Iterable[(TxOutputPoint, TxOutput)]): IOResult[MerklePatriciaTrie] = {
    EitherF.foldTry(outputs, trie) {
      case (trie0, (outputPoint, output)) =>
        trie0.put(outputPoint, output)
    }
  }

  def updateStateForInputs(trie: MerklePatriciaTrie,
                           inputs: Iterable[TxOutputPoint]): IOResult[MerklePatriciaTrie] = {
    EitherF.foldTry(inputs, trie)(_.remove(_))
  }

  def updateState(trie: MerklePatriciaTrie,
                  blockCache: BlockCache): IOResult[MerklePatriciaTrie] = {
    blockCache match {
      case InBlockCache(outputs) =>
        updateStateForOutputs(trie, outputs)
      case OutBlockCache(inputs) =>
        updateStateForInputs(trie, inputs)
      case InOutBlockCache(outputs, inputs) =>
        for {
          trie0 <- updateStateForOutputs(trie, outputs)
          trie1 <- updateStateForInputs(trie0, inputs)
        } yield trie1
    }
  }
}
