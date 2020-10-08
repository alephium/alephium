// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.core

import scala.collection.mutable
import scala.reflect.ClassTag

import org.alephium.flow.model.BlockDeps
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.{Hash, PrivateKey}
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.util._

// scalastyle:off number.of.methods
trait BlockFlowState extends FlowTipsUtil {
  import BlockFlowState._

  implicit def brokerConfig: BrokerConfig
  def consensusConfig: ConsensusSetting
  def groups: Int = brokerConfig.groups
  def genesisBlocks: AVector[AVector[Block]]

  private val bestDeps = Array.tabulate(brokerConfig.groupNumPerBroker) { fromShift =>
    val mainGroup = brokerConfig.groupFrom + fromShift
    val deps1 = AVector.tabulate(groups - 1) { i =>
      if (i < mainGroup) genesisBlocks(i)(i).hash
      else genesisBlocks(i + 1)(i + 1).hash
    }
    val deps2 = genesisBlocks(mainGroup).map(_.hash)
    BlockDeps(deps1 ++ deps2)
  }

  def blockchainWithStateBuilder: (Block, BlockFlow.TrieUpdater) => BlockChainWithState
  def blockchainBuilder: Block                                   => BlockChain
  def blockheaderChainBuilder: BlockHeader                       => BlockHeaderChain

  private val intraGroupChains: AVector[BlockChainWithState] = {
    AVector.tabulate(brokerConfig.groupNumPerBroker) { groupShift =>
      val group        = brokerConfig.groupFrom + groupShift
      val genesisBlock = genesisBlocks(group)(group)
      blockchainWithStateBuilder(genesisBlock, updateState)
    }
  }

  private val inBlockChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(brokerConfig.groupNumPerBroker, groups - brokerConfig.groupNumPerBroker) {
      (toShift, k) =>
        val mainGroup    = brokerConfig.groupFrom + toShift
        val fromIndex    = if (k < brokerConfig.groupFrom) k else k + brokerConfig.groupNumPerBroker
        val genesisBlock = genesisBlocks(fromIndex)(mainGroup)
        blockchainBuilder(genesisBlock)
    }
  private val outBlockChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(brokerConfig.groupNumPerBroker, groups) { (fromShift, to) =>
      val mainGroup = brokerConfig.groupFrom + fromShift
      if (mainGroup == to) {
        intraGroupChains(fromShift)
      } else {
        val genesisBlock = genesisBlocks(mainGroup)(to)
        blockchainBuilder(genesisBlock)
      }
    }
  private val blockHeaderChains: AVector[AVector[BlockHeaderChain]] =
    AVector.tabulate(groups, groups) {
      case (from, to) =>
        if (brokerConfig.containsRaw(from)) {
          val fromShift = from - brokerConfig.groupFrom
          outBlockChains(fromShift)(to)
        } else if (brokerConfig.containsRaw(to)) {
          val toShift = to - brokerConfig.groupFrom
          val fromIndex =
            if (from < brokerConfig.groupFrom) from else from - brokerConfig.groupNumPerBroker
          inBlockChains(toShift)(fromIndex)
        } else {
          val genesisHeader = genesisBlocks(from)(to).header
          blockheaderChainBuilder(genesisHeader)
        }
    }

  // Cache latest blocks for assisting merkle trie
  private val groupCaches = AVector.fill(brokerConfig.groupNumPerBroker) {
    LruCache[Hash, BlockCache, IOError](
      consensusConfig.blockCacheCapacityPerChain * brokerConfig.depsNum)
  }

  def getGroupCache(groupIndex: GroupIndex): LruCache[Hash, BlockCache, IOError] = {
    assume(brokerConfig.contains(groupIndex))
    groupCaches(groupIndex.value - brokerConfig.groupFrom)
  }

  def cacheBlock(block: Block): Unit = {
    val index = block.chainIndex
    (brokerConfig.groupFrom until brokerConfig.groupUntil).foreach { group =>
      val groupIndex = GroupIndex.unsafe(group)
      val groupCache = getGroupCache(groupIndex)
      if (index.relateTo(groupIndex)) {
        groupCache.putInCache(block.hash, convertBlock(block, groupIndex))
      }
    }
  }

  def getBlockCache(groupIndex: GroupIndex, hash: Hash): IOResult[BlockCache] = {
    assume(ChainIndex.from(hash).relateTo(groupIndex))
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
    assume(brokerConfig.contains(from) || brokerConfig.contains(to))
    if (brokerConfig.contains(from)) {
      outBlockChains(from.value - brokerConfig.groupFrom)(to.value)
    } else {
      val fromIndex =
        if (from.value < brokerConfig.groupFrom) from.value
        else from.value - brokerConfig.groupNumPerBroker
      val toShift = to.value - brokerConfig.groupFrom
      inBlockChains(toShift)(fromIndex)
    }
  }

  protected def getBlockChainWithState(group: GroupIndex): BlockChainWithState = {
    assume(brokerConfig.contains(group))
    intraGroupChains(group.value - brokerConfig.groupFrom)
  }

  def getHeaderChain(chainIndex: ChainIndex): BlockHeaderChain

  protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderChain = {
    blockHeaderChains(from.value)(to.value)
  }

  protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain = {
    blockHeaderChains(from.value)(to.value)
  }

  private def getPersistedTrie(deps: AVector[Hash],
                               groupIndex: GroupIndex): IOResult[WorldState.Persisted] = {
    assume(deps.length == brokerConfig.depsNum)
    val hash = deps(brokerConfig.groups - 1 + groupIndex.value)
    getBlockChainWithState(groupIndex).getPersistedWorldState(hash)
  }

  private def getCachedTrie(deps: AVector[Hash],
                            groupIndex: GroupIndex): IOResult[WorldState.Cached] = {
    assume(deps.length == brokerConfig.depsNum)
    val hash = deps(brokerConfig.groups - 1 + groupIndex.value)
    getBlockChainWithState(groupIndex).getCachedWorldState(hash)
  }

  def getPersistedTrie(block: Block): IOResult[WorldState] = {
    val header = block.header
    getPersistedTrie(header.blockDeps, header.chainIndex.from)
  }

  def getCachedTrie(block: Block): IOResult[WorldState] = {
    val header = block.header
    getCachedTrie(header.blockDeps, header.chainIndex.from)
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps = {
    val groupShift = groupIndex.value - brokerConfig.groupFrom
    bestDeps(groupShift)
  }

  def getBestHeight(chainIndex: ChainIndex): IOResult[Int] = {
    val bestParent = getBestDeps(chainIndex.from).getOutDep(chainIndex.to)
    getHashChain(chainIndex.from, chainIndex.to).getHeight(bestParent)
  }

  def getBestPersistedTrie(groupIndex: GroupIndex): IOResult[WorldState.Persisted] = {
    assume(brokerConfig.contains(groupIndex))
    val deps = getBestDeps(groupIndex)
    getPersistedTrie(deps.deps, groupIndex)
  }

  def getBestCachedTrie(groupIndex: GroupIndex): IOResult[WorldState.Cached] = {
    assume(brokerConfig.contains(groupIndex))
    val deps = getBestDeps(groupIndex)
    getCachedTrie(deps.deps, groupIndex)
  }

  def updateBestDeps(mainGroup: Int, deps: BlockDeps): Unit = {
    assume(brokerConfig.containsRaw(mainGroup))
    val groupShift = mainGroup - brokerConfig.groupFrom
    bestDeps(groupShift) = deps
  }

  protected def getBlocksForUpdates(block: Block): IOResult[AVector[Block]] = {
    val chainIndex = block.chainIndex
    assume(chainIndex.isIntraGroup)
    for {
      newTips <- getInOutTips(block.header, chainIndex.from, inclusive = false)
      oldTips <- getInOutTips(block.parentHash, chainIndex.from, inclusive = true)
      diff    <- getTipsDiff(newTips, oldTips)
      blocks  <- (diff :+ block.hash).mapE(hash => getBlockChain(hash).getBlock(hash))
    } yield blocks
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
  def updateState(worldState: WorldState, block: Block): IOResult[WorldState] = {
    val chainIndex = block.chainIndex
    assume(chainIndex.isIntraGroup)
    if (block.header.isGenesis) {
      BlockFlowState.updateState(worldState, block, chainIndex.from)
    } else {
      getBlocksForUpdates(block).flatMap { blocks =>
        blocks.foldE(worldState)(BlockFlowState.updateState(_, _, chainIndex.from))
      }
    }
  }

  private def lockedBy(output: TxOutput, lockupScript: LockupScript): Boolean =
    output.lockupScript == lockupScript

  def getUtxos(lockupScript: LockupScript): IOResult[AVector[(AssetOutputRef, AssetOutput)]] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))

    for {
      bestTrie <- getBestPersistedTrie(groupIndex)
      persistedUtxos <- bestTrie
        .getAssetOutputs(lockupScript.assetHintBytes)
        .map(_.filter(p => lockedBy(p._2, lockupScript)))
    } yield persistedUtxos
  }

  def getBalance(lockupScript: LockupScript): IOResult[(U64, Int)] = {
    getUtxos(lockupScript).map { utxos =>
      val balance = utxos.fold(U64.Zero)(_ addUnsafe _._2.amount)
      (balance, utxos.length)
    }
  }

  def getUtxosInCache(lockupScript: LockupScript,
                      groupIndex: GroupIndex,
                      persistedUtxos: AVector[(AssetOutputRef, AssetOutput)])
    : IOResult[(AVector[TxOutputRef], AVector[(AssetOutputRef, AssetOutput)])] = {
    getBlocksForUpdates(groupIndex).map { blockCaches =>
      val usedUtxos = blockCaches.flatMap[TxOutputRef] { blockCache =>
        AVector.from(blockCache.inputs.view.filter(input => persistedUtxos.exists(_._1 == input)))
      }
      val newUtxos = blockCaches.flatMap { blockCache =>
        AVector
          .from(blockCache.relatedOutputs.view.filter(p =>
            lockedBy(p._2, lockupScript) && p._1.isAssetType && p._2.isAsset))
          .asUnsafe[(AssetOutputRef, AssetOutput)]
      }
      (usedUtxos, newUtxos)
    }
  }

  def prepareUnsignedTx(fromLockupScript: LockupScript,
                        fromUnlockScript: UnlockScript,
                        toLockupScript: LockupScript,
                        value: U64): IOResult[Option[UnsignedTransaction]] = {
    for {
      utxos  <- getUtxos(fromLockupScript)
      height <- getBestHeight(ChainIndex(fromLockupScript.groupIndex, toLockupScript.groupIndex))
    } yield {
      val balance = utxos.fold(U64.Zero)(_ addUnsafe _._2.amount)
      if (balance >= value) {
        Some(
          UnsignedTransaction
            .transferAlf(utxos.map(_._1),
                         balance,
                         fromLockupScript,
                         fromUnlockScript,
                         toLockupScript,
                         value,
                         height))
      } else {
        None
      }
    }
  }

  def prepareTx(fromLockupScript: LockupScript,
                fromUnlockScript: UnlockScript,
                toLockupScript: LockupScript,
                value: U64,
                fromPrivateKey: PrivateKey): IOResult[Option[Transaction]] =
    prepareUnsignedTx(fromLockupScript, fromUnlockScript, toLockupScript, value).map(_.map {
      unsigned =>
        Transaction.from(unsigned, fromPrivateKey)
    })
}
// scalastyle:on number.of.methods

object BlockFlowState {
  sealed trait BlockCache {
    def inputs: Set[TxOutputRef]
    def relatedOutputs: Map[TxOutputRef, TxOutput]
  }

  final case class InBlockCache(outputs: Map[TxOutputRef, TxOutput]) extends BlockCache {
    def inputs: Set[TxOutputRef]                   = Set.empty
    def relatedOutputs: Map[TxOutputRef, TxOutput] = outputs
  }
  final case class OutBlockCache(inputs: Set[TxOutputRef],
                                 relatedOutputs: Map[TxOutputRef, TxOutput])
      extends BlockCache
  final case class InOutBlockCache(outputs: Map[TxOutputRef, TxOutput], inputs: Set[TxOutputRef])
      extends BlockCache { // For blocks on intra-group chain
    def relatedOutputs: Map[TxOutputRef, TxOutput] = outputs
  }

  private def convertInputs(block: Block): Set[TxOutputRef] = {
    block.transactions.flatMap { tx =>
      tx.unsigned.inputs.map[TxOutputRef](_.outputRef) ++
        tx.contractInputs.map(_.asInstanceOf[TxOutputRef])
    }.toSet
  }

  private def convertOutputs(block: Block): Map[TxOutputRef, TxOutput] = {
    val outputs = mutable.Map.empty[TxOutputRef, TxOutput]
    block.transactions.foreach { transaction =>
      (0 until transaction.outputsLength).foreach { index =>
        val output    = transaction.getOutput(index)
        val outputRef = TxOutputRef.unsafe(transaction, index)
        outputs.update(outputRef, output)
      }
      AVector.tabulate(transaction.outputsLength) { index =>
        val output    = transaction.getOutput(index)
        val outputRef = TxOutputRef.unsafe(transaction, index)
        (outputRef, output)
      }
    }
    outputs.toMap
  }

  // This is only used for out blocks for a specific group
  private def convertRelatedOutputs(block: Block, groupIndex: GroupIndex)(
      implicit brokerConfig: GroupConfig): Map[TxOutputRef, TxOutput] = {
    convertOutputs(block).filter {
      case (outputRef: AssetOutputRef, _) if outputRef.fromGroup == groupIndex => true
      case _                                                                   => false
    }
  }

  def convertBlock(block: Block, groupIndex: GroupIndex)(
      implicit brokerConfig: BrokerConfig): BlockCache = {
    val index = block.chainIndex
    assume(index.relateTo(groupIndex))
    if (index.isIntraGroup) {
      val outputs = convertOutputs(block)
      InOutBlockCache(outputs, convertInputs(block))
    } else if (index.from == groupIndex) {
      OutBlockCache(convertInputs(block), convertRelatedOutputs(block, groupIndex))
    } else {
      InBlockCache(convertOutputs(block))
    }
  }

  def updateState(worldState: WorldState, block: Block, targetGroup: GroupIndex)(
      implicit brokerConfig: GroupConfig): IOResult[WorldState] = {
    val chainIndex = block.chainIndex
    assume(chainIndex.relateTo(targetGroup))
    if (chainIndex.isIntraGroup) {
      block.transactions.foldE(worldState) {
        case (state, tx) => updateStateForInOutBlock(state, tx, targetGroup)
      }
    } else if (chainIndex.from == targetGroup) {
      block.transactions.foldE(worldState) {
        case (state, tx) => updateStateForOutBlock(state, tx, targetGroup)
      }
    } else if (chainIndex.to == targetGroup) {
      block.transactions.foldE(worldState) {
        case (state, tx) => updateStateForInBlock(state, tx, targetGroup)
      }
    } else {
      // dead branch though
      Right(worldState)
    }
  }

  def updateStateForInOutBlock(worldState: WorldState, tx: Transaction, targetGroup: GroupIndex)(
      implicit brokerConfig: GroupConfig): IOResult[WorldState] = {
    for {
      state0 <- updateStateForTxScript(worldState, tx)
      state1 <- updateStateForInputs(state0, tx)
      state2 <- updateStateForOutputs(state1, tx, targetGroup)
    } yield state2
  }

  def updateStateForOutBlock(worldState: WorldState, tx: Transaction, targetGroup: GroupIndex)(
      implicit brokerConfig: GroupConfig): IOResult[WorldState] = {
    for {
      state0 <- updateStateForInputs(worldState, tx)
      state1 <- updateStateForOutputs(state0, tx, targetGroup)
    } yield state1
  }

  def updateStateForInBlock(worldState: WorldState, tx: Transaction, targetGroup: GroupIndex)(
      implicit brokerConfig: GroupConfig): IOResult[WorldState] = {
    updateStateForOutputs(worldState, tx, targetGroup)
  }

  def updateStateForTxScript(worldState: WorldState, tx: Transaction): IOResult[WorldState] = {
    tx.unsigned.scriptOpt match {
      case Some(script) =>
        StatefulVM.runTxScript(worldState, tx, script) match {
          case Right(exeResult)                => Right(exeResult.worldState)
          case Left(IOErrorUpdateState(error)) => Left(error)
          case Left(error) =>
            throw new RuntimeException(s"Updating world state for invalid tx: $error")
        }
      case None => Right(worldState)
    }
  }

  // Note: contract inputs are updated during the execution of tx script
  def updateStateForInputs(worldState: WorldState, tx: Transaction): IOResult[WorldState] = {
    tx.unsigned.inputs.foldE(worldState) {
      case (state, txInput) => state.removeAsset(txInput.outputRef)
    }
  }

  private def updateStateForOutputs(
      worldState: WorldState,
      tx: Transaction,
      targetGroup: GroupIndex)(implicit brokerConfig: GroupConfig): IOResult[WorldState] = {
    tx.allOutputs.foldWithIndexE(worldState) {
      case (state, output: AssetOutput, index) if output.toGroup == targetGroup =>
        val outputRef = TxOutputRef.from(output, TxOutputRef.key(tx.hash, index))
        state.addAsset(outputRef, output)
      case (state, _, _) => Right(state) // contract outputs are updated in VM
    }
  }
}
