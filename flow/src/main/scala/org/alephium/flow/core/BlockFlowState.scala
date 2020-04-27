package org.alephium.flow.core

import scala.reflect.ClassTag

import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.flow.io.{IOError, IOResult}
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.platform.PlatformConfig
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.script.{PayTo, PubScript}
import org.alephium.util._

// scalastyle:off number.of.methods
trait BlockFlowState {
  import BlockFlowState._

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

  def blockchainWithStateBuilder: (ChainIndex, BlockFlow.TrieUpdater) => BlockChainWithState
  def blockchainBuilder: ChainIndex                                   => BlockChain
  def blockheaderChainBuilder: ChainIndex                             => BlockHeaderChain

  private val intraGroupChains: AVector[BlockChainWithState] = {
    AVector.tabulate(config.groupNumPerBroker) { groupShift =>
      val group = brokerInfo.groupFrom + groupShift
      blockchainWithStateBuilder(ChainIndex.unsafe(group, group), updateState)
    }
  }

  private val inBlockChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(config.groupNumPerBroker, groups - config.groupNumPerBroker) { (toShift, k) =>
      val mainGroup = brokerInfo.groupFrom + toShift
      val fromIndex = if (k < brokerInfo.groupFrom) k else k + config.groupNumPerBroker
      blockchainBuilder(ChainIndex.unsafe(fromIndex, mainGroup))
    }
  private val outBlockChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(config.groupNumPerBroker, groups) { (fromShift, to) =>
      val mainGroup = brokerInfo.groupFrom + fromShift
      if (mainGroup == to) {
        intraGroupChains(fromShift)
      } else {
        blockchainBuilder(ChainIndex.unsafe(mainGroup, to))
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
        } else blockheaderChainBuilder(ChainIndex.unsafe(from, to))
    }

  // Cache latest blocks for assisting merkle trie
  private val groupCaches = AVector.fill(config.groupNumPerBroker) {
    LruCache[Hash, BlockCache, IOError](config.blockCacheCapacity)
  }

  def getGroupCache(groupIndex: GroupIndex): LruCache[Hash, BlockCache, IOError] = {
    assume(brokerInfo.contains(groupIndex))
    groupCaches(groupIndex.value - brokerInfo.groupFrom)
  }

  def cacheBlock(block: Block): Unit = {
    val index = block.chainIndex
    (brokerInfo.groupFrom until brokerInfo.groupUntil).foreach { group =>
      val groupIndex = GroupIndex.unsafe(group)
      val groupCache = getGroupCache(groupIndex)
      if (index.relateTo(groupIndex)) {
        groupCache.putInCache(block.hash, convertBlock(block, groupIndex))
      }
    }
  }

  def getBlockCache(groupIndex: GroupIndex, hash: Hash): IOResult[BlockCache] = {
    assert(ChainIndex.from(hash).relateTo(groupIndex))
    getGroupCache(groupIndex).get(hash) {
      getBlockChain(hash).getBlock(hash).map(convertBlock(_, groupIndex))
    }
  }

  protected def aggregateHash[T: ClassTag](f: BlockHashPool => T)(op: (T, T) => T): T = {
    blockHeaderChains.reduceBy { chains =>
      chains.reduceBy(f)(op)
    }(op)
  }

  protected def aggregateHashE[T: ClassTag](f: BlockHashPool => IOResult[T])(
      op: (T, T)                                             => T): IOResult[T] = {
    blockHeaderChains.reduceByE { chains =>
      chains.reduceByE(f)(op)
    }(op)
  }

  protected def aggregateHeaderE[T: ClassTag](f: BlockHeaderPool => IOResult[T])(
      op: (T, T)                                                 => T): IOResult[T] = {
    blockHeaderChains.reduceByE { chains =>
      chains.reduceByE(f)(op)
    }(op)
  }

  def getBlockChain(hash: Hash): BlockChain

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

  private def getTrie(deps: AVector[Hash], groupIndex: GroupIndex): IOResult[MerklePatriciaTrie] = {
    assert(deps.length == config.depsNum)
    val hash = deps(config.groups - 1 + groupIndex.value)
    getBlockChainWithState(groupIndex).getTrie(hash)
  }

  def getTrie(block: Block): IOResult[MerklePatriciaTrie] = {
    val header = block.header
    getTrie(header.blockDeps, header.chainIndex.from)
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps = {
    val groupShift = groupIndex.value - brokerInfo.groupFrom
    bestDeps(groupShift)
  }

  def getBestTrie(chainIndex: ChainIndex): IOResult[MerklePatriciaTrie] = {
    getBestTrie(chainIndex.from)
  }

  def getBestTrie(groupIndex: GroupIndex): IOResult[MerklePatriciaTrie] = {
    assert(config.brokerInfo.contains(groupIndex))
    val deps = getBestDeps(groupIndex)
    getTrie(deps.deps, groupIndex)
  }

  def updateBestDeps(mainGroup: Int, deps: BlockDeps): Unit = {
    assert(brokerInfo.containsRaw(mainGroup))
    val groupShift = mainGroup - brokerInfo.groupFrom
    bestDeps(groupShift) = deps
  }

  def getBlockHeader(hash: Hash): IOResult[BlockHeader]

  def getOutTips(header: BlockHeader, inclusive: Boolean): AVector[Hash] = {
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

  def getInTip(dep: Hash, currentGroup: GroupIndex): IOResult[Hash] = {
    getBlockHeader(dep).map { header =>
      val from = header.chainIndex.from
      if (header.isGenesis) config.genesisBlocks(from.value)(currentGroup.value).hash
      else {
        if (currentGroup == ChainIndex.from(dep).to) dep else header.uncleHash(currentGroup)
      }
    }
  }

  def getOutBlockTips(brokerInfo: BrokerInfo): AVector[Hash]

  // if inclusive is true, the current header would be included
  def getInOutTips(header: BlockHeader,
                   currentGroup: GroupIndex,
                   inclusive: Boolean): IOResult[AVector[Hash]] = {
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

  def getInOutTips(hash: Hash,
                   currentGroup: GroupIndex,
                   inclusive: Boolean): IOResult[AVector[Hash]] = {
    getBlockHeader(hash).flatMap(getInOutTips(_, currentGroup, inclusive))
  }

  def getTipsDiff(newTip: Hash, oldTip: Hash): IOResult[AVector[Hash]] = {
    getBlockChain(oldTip).getBlockHashesBetween(newTip, oldTip)
  }

  protected def getTipsDiff(newTips: AVector[Hash],
                            oldTips: AVector[Hash]): IOResult[AVector[Hash]] = {
    assert(newTips.length == oldTips.length)
    EitherF.foldTry(newTips.indices, AVector.empty[Hash]) { (acc, i) =>
      getTipsDiff(newTips(i), oldTips(i)).map(acc ++ _)
    }
  }

  protected def getBlocksForUpdates(block: Block): IOResult[AVector[BlockCache]] = {
    val chainIndex = block.chainIndex
    assert(chainIndex.isIntraGroup)
    val groupIndex = chainIndex.from
    for {
      newTips     <- getInOutTips(block.header, chainIndex.from, inclusive = false)
      oldTips     <- getInOutTips(block.parentHash, chainIndex.from, inclusive = true)
      diff        <- getTipsDiff(newTips, oldTips)
      blockCaches <- (diff :+ block.hash).mapE(getBlockCache(groupIndex, _))
    } yield blockCaches
  }

  def getHashesForUpdates(groupIndex: GroupIndex): IOResult[AVector[Hash]] = {
    val bestDeps     = getBestDeps(groupIndex)
    val bestOutDeps  = bestDeps.outDeps
    val bestIntraDep = bestOutDeps(groupIndex.value)
    for {
      newTips <- bestDeps.inDeps.mapE(getInTip(_, groupIndex)).map(_ ++ bestOutDeps)
      oldTips <- getInOutTips(bestIntraDep, groupIndex, inclusive = true)
      diff    <- getTipsDiff(newTips, oldTips)
    } yield diff
  }

  def getBlocksForUpdates(groupIndex: GroupIndex): IOResult[AVector[BlockCache]] = {
    for {
      diff        <- getHashesForUpdates(groupIndex)
      blockCaches <- diff.mapE(getBlockCache(groupIndex, _))
    } yield blockCaches
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

  def getAllInputs(chainIndex: ChainIndex, tx: Transaction): IOResult[AVector[TxOutput]] = {
    for {
      trie   <- getBestTrie(chainIndex)
      inputs <- tx.unsigned.inputs.mapE(input => trie.get[TxOutputPoint, TxOutput](input))
    } yield inputs
  }

  def getUtxos(payTo: PayTo,
               address: ED25519PublicKey): IOResult[AVector[(TxOutputPoint, TxOutput)]] = {
    val pubScript = PubScript.build(payTo, address)
    getUtxos(pubScript)
  }

  def getUtxos(pubScript: PubScript): IOResult[AVector[(TxOutputPoint, TxOutput)]] = {
    val groupIndex = pubScript.groupIndex
    assert(config.brokerInfo.contains(groupIndex))

    for {
      bestTrie <- getBestTrie(groupIndex)
      persistedUtxos <- bestTrie
        .getAll[TxOutputPoint, TxOutput](pubScript.shortKeyBytes)
        .map(_.filter(_._2.pubScript == pubScript))
      pair <- getUtxosInCache(pubScript, groupIndex, persistedUtxos)
    } yield {
      val (usedUtxos, newUtxos) = pair
      persistedUtxos.filter(p => !usedUtxos.contains(p._1)) ++ newUtxos
    }
  }

  def getBalance(payTo: PayTo, address: ED25519PublicKey): IOResult[(BigInt, Int)] = {
    getUtxos(payTo, address).map { utxos =>
      (utxos.sumBy(_._2.value), utxos.length)
    }
  }

  def getUtxosInCache(pubScript: PubScript,
                      groupIndex: GroupIndex,
                      persistedUtxos: AVector[(TxOutputPoint, TxOutput)])
    : IOResult[(AVector[TxOutputPoint], AVector[(TxOutputPoint, TxOutput)])] = {
    getBlocksForUpdates(groupIndex).map { blockCaches =>
      val usedUtxos = blockCaches.flatMap[TxOutputPoint] { blockCache =>
        AVector.from(blockCache.inputs.view.filter(input => persistedUtxos.exists(_._1 == input)))
      }
      val newUtxos = blockCaches.flatMap[(TxOutputPoint, TxOutput)] { blockCache =>
        AVector.from(blockCache.relatedOutputs.view.filter(_._2.pubScript == pubScript))
      }
      (usedUtxos, newUtxos)
    }
  }

  def prepareUnsignedTx(from: ED25519PublicKey,
                        fromPayTo: PayTo,
                        to: ED25519PublicKey,
                        toPayTo: PayTo,
                        value: BigInt): IOResult[Option[UnsignedTransaction]] = {
    getUtxos(fromPayTo, from).map { utxos =>
      val balance = utxos.sumBy(_._2.value)
      if (balance >= value) {
        Some(
          UnsignedTransaction
            .simpleTransfer(utxos.map(_._1), balance, from, fromPayTo, to, toPayTo, value))
      } else {
        None
      }
    }
  }

  def prepareTx(from: ED25519PublicKey,
                fromPayTo: PayTo,
                to: ED25519PublicKey,
                toPayTo: PayTo,
                value: BigInt,
                fromPrivateKey: ED25519PrivateKey): IOResult[Option[Transaction]] =
    prepareUnsignedTx(from, fromPayTo, to, toPayTo, value).map(_.map { unsigned =>
      Transaction.from(unsigned, fromPayTo, from, fromPrivateKey)
    })
}
// scalastyle:on number.of.methods

object BlockFlowState {
  sealed trait BlockCache {
    def inputs: Set[TxOutputPoint]
    def relatedOutputs: Map[TxOutputPoint, TxOutput]
  }

  final case class InBlockCache(outputs: Map[TxOutputPoint, TxOutput]) extends BlockCache {
    def inputs: Set[TxOutputPoint]                   = Set.empty
    def relatedOutputs: Map[TxOutputPoint, TxOutput] = outputs
  }
  final case class OutBlockCache(inputs: Set[TxOutputPoint],
                                 relatedOutputs: Map[TxOutputPoint, TxOutput])
      extends BlockCache
  final case class InOutBlockCache(outputs: Map[TxOutputPoint, TxOutput],
                                   inputs: Set[TxOutputPoint])
      extends BlockCache { // For blocks on intra-group chain
    def relatedOutputs: Map[TxOutputPoint, TxOutput] = outputs
  }

  private def convertInputs(block: Block): Set[TxOutputPoint] = {
    block.transactions.flatMap(_.unsigned.inputs).toIterable.toSet
  }

  private def convertOutputs(block: Block): Map[TxOutputPoint, TxOutput] = {
    val outputs = block.transactions.flatMap { transaction =>
      transaction.unsigned.outputs.mapWithIndex { (output, i) =>
        val outputPoint = TxOutputPoint.unsafe(transaction, i)
        (outputPoint, output)
      }
    }
    outputs.toIterable.toMap
  }

  private def convertRelatedOutputs(block: Block, groupIndex: GroupIndex)(
      implicit config: GroupConfig): Map[TxOutputPoint, TxOutput] = {
    convertOutputs(block).filter(_._1.fromGroup == groupIndex)
  }

  def convertBlock(block: Block, groupIndex: GroupIndex)(
      implicit config: PlatformConfig): BlockCache = {
    val index = block.chainIndex
    assert(index.relateTo(groupIndex))
    if (index.isIntraGroup) {
      InOutBlockCache(convertOutputs(block), convertInputs(block))
    } else if (index.from == groupIndex) {
      OutBlockCache(convertInputs(block), convertRelatedOutputs(block, groupIndex))
    } else {
      InBlockCache(convertOutputs(block))
    }
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
      case OutBlockCache(inputs, relatedOutputs) =>
        for {
          trie0 <- updateStateForInputs(trie, inputs)
          trie1 <- updateStateForOutputs(trie0, relatedOutputs)
        } yield trie1
      case InOutBlockCache(outputs, inputs) =>
        for {
          trie0 <- updateStateForOutputs(trie, outputs)
          trie1 <- updateStateForInputs(trie0, inputs)
        } yield trie1
    }
  }
}
