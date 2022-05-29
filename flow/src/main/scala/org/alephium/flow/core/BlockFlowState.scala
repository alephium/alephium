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

import org.alephium.flow.core.BlockChain.TxIndex
import org.alephium.flow.mempool.MemPool
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.{IOResult, KeyValueStorage}
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.config.{BrokerConfig, GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.util._

// scalastyle:off number.of.methods
trait BlockFlowState extends FlowTipsUtil {
  import BlockFlowState._

  implicit def brokerConfig: BrokerConfig
  implicit def networkConfig: NetworkConfig
  def consensusConfig: ConsensusSetting
  def groups: Int = brokerConfig.groups
  def genesisBlocks: AVector[AVector[Block]]

  lazy val genesisHashes: AVector[AVector[BlockHash]] = genesisBlocks.map(_.map(_.hash))
  lazy val initialGenesisHashes: AVector[BlockHash]   = genesisHashes.mapWithIndex(_.apply(_))

  protected[core] val bestDeps = Array.tabulate(brokerConfig.groupNumPerBroker) { fromShift =>
    val mainGroup = brokerConfig.groupRange(fromShift)
    val deps1 = AVector.tabulate(groups - 1) { i =>
      if (i < mainGroup) {
        genesisHashes(i)(i)
      } else {
        genesisHashes(i + 1)(i + 1)
      }
    }
    val deps2 = genesisBlocks(mainGroup).map(_.hash)
    BlockDeps.build(deps1 ++ deps2)
  }

  def blockchainWithStateBuilder: (Block, BlockFlow.WorldStateUpdater) => BlockChainWithState
  def blockchainBuilder: Block => BlockChain
  def blockheaderChainBuilder: BlockHeader => BlockHeaderChain

  protected[core] val intraGroupBlockChains: AVector[BlockChainWithState] = {
    AVector.tabulate(brokerConfig.groupNumPerBroker) { groupShift =>
      val group        = brokerConfig.groupRange(groupShift)
      val genesisBlock = genesisBlocks(group)(group)
      blockchainWithStateBuilder(genesisBlock, updateState)
    }
  }

  val logStorage: KeyValueStorage[LogStatesId, LogStates] = {
    assume(intraGroupBlockChains.nonEmpty, "No intraGroupBlockChains")
    intraGroupBlockChains.head.worldStateStorage.logStorage
  }

  protected[core] val outBlockChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(brokerConfig.groupNumPerBroker, groups) { (fromShift, to) =>
      val mainGroup = brokerConfig.groupRange(fromShift)
      if (mainGroup == to) {
        intraGroupBlockChains(fromShift)
      } else {
        val genesisBlock = genesisBlocks(mainGroup)(to)
        blockchainBuilder(genesisBlock)
      }
    }
  protected[core] val inBlockChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(groups, brokerConfig.groupNumPerBroker) { (from, toShift) =>
      val mainGroup = brokerConfig.groupRange(toShift)
      if (brokerConfig.groupRange.contains(from)) {
        val fromShift = brokerConfig.groupIndexOfBrokerUnsafe(from)
        outBlockChains(fromShift)(mainGroup)
      } else {
        val genesisBlock = genesisBlocks(from)(mainGroup)
        blockchainBuilder(genesisBlock)
      }
    }

  protected[core] val blockHeaderChains: AVector[AVector[BlockHeaderChain]] =
    AVector.tabulate(groups, groups) { case (from, to) =>
      if (brokerConfig.containsRaw(from)) {
        val fromShift = brokerConfig.groupIndexOfBrokerUnsafe(from)
        outBlockChains(fromShift)(to)
      } else if (brokerConfig.containsRaw(to)) {
        val toShift = brokerConfig.groupIndexOfBrokerUnsafe(to)
        inBlockChains(from)(toShift)
      } else {
        val genesisHeader = genesisBlocks(from)(to).header
        blockheaderChainBuilder(genesisHeader)
      }
    }

  protected[core] val intraGroupHeaderChains: AVector[BlockHeaderChain] =
    AVector.tabulate(groups)(group => blockHeaderChains(group)(group))

  def sanityCheckUnsafe(): Unit = {
    inBlockChains.foreach(_.foreach { chain =>
      chain.getAllTips.foreach { tip =>
        require(chain.blockStorage.existsUnsafe(tip), "Tip should be stored in block storage")
        require(chain.blockStateStorage.existsUnsafe(tip), "Tip should be stored in state storage")
      }
    })
    outBlockChains.foreach(_.foreach { chain =>
      chain.getAllTips.foreach { tip =>
        require(chain.blockStorage.existsUnsafe(tip), "Tip should be stored in block storage")
        require(chain.blockStateStorage.existsUnsafe(tip), "Tip should be stored in state storage")
      }
    })
    intraGroupBlockChains.foreach { chain =>
      chain.getAllTips.foreach { tip =>
        require(chain.worldStateStorage.existsUnsafe(tip), "Tip should have its state trie stored")
      }
    }
    blockHeaderChains.foreach(_.foreach { chain =>
      chain.getAllTips.map { tip =>
        require(chain.headerStorage.existsUnsafe(tip), "Tip should be stored in header storage")
        require(chain.blockStateStorage.existsUnsafe(tip), "Tip should be stored in state storage")
      }
    })
  }

  // Cache latest blocks for trie update, UTXO indexing
  lazy val groupCaches = AVector.fill(brokerConfig.groupNumPerBroker) {
    FlowCache.blocks(consensusConfig.blockCacheCapacityPerChain)
  }

  def getGroupCache(groupIndex: GroupIndex): FlowCache[BlockHash, BlockCache] = {
    assume(brokerConfig.contains(groupIndex))
    groupCaches(brokerConfig.groupIndexOfBroker(groupIndex))
  }

  def cacheBlock(block: Block): Unit = {
    val index = block.chainIndex
    brokerConfig.groupRange.foreach { group =>
      val groupIndex = GroupIndex.unsafe(group)
      val groupCache = getGroupCache(groupIndex)
      if (index.relateTo(groupIndex)) {
        groupCache.put(block.hash, convertBlock(block, groupIndex))
      }
    }
  }

  def getBlockCache(groupIndex: GroupIndex, hash: BlockHash): IOResult[BlockCache] = {
    assume(ChainIndex.from(hash).relateTo(groupIndex))
    getGroupCache(groupIndex).getE(hash) {
      getBlockChain(hash).getBlock(hash).map(convertBlock(_, groupIndex))
    }
  }

  protected def aggregateHash[T](f: BlockHashPool => T)(op: (T, T) => T): T = {
    blockHeaderChains.reduceBy { chains => chains.reduceBy(f)(op) }(op)
  }

  protected def aggregateHashE[T](f: BlockHashPool => IOResult[T])(op: (T, T) => T): IOResult[T] = {
    blockHeaderChains.reduceByE { chains => chains.reduceByE(f)(op) }(op)
  }

  protected def concatOutBlockChainsE[T: ClassTag](
      f: BlockChain => IOResult[T]
  ): IOResult[AVector[T]] = {
    outBlockChains.flatMapE { chains => chains.mapE(f) }
  }

  protected def concatIntraBlockChainsE[T: ClassTag](
      f: BlockChain => IOResult[T]
  ): IOResult[AVector[T]] = {
    intraGroupBlockChains.mapE(f)
  }

  def getBlockChain(hash: BlockHash): BlockChain

  def getBlockChain(chainIndex: ChainIndex): BlockChain

  protected def getBlockChain(from: GroupIndex, to: GroupIndex): BlockChain = {
    assume(brokerConfig.contains(from) || brokerConfig.contains(to))
    if (brokerConfig.contains(from)) {
      outBlockChains(brokerConfig.groupIndexOfBroker(from))(to.value)
    } else {
      inBlockChains(from.value)(brokerConfig.groupIndexOfBroker(to))
    }
  }

  protected def getBlockChainWithState(group: GroupIndex): BlockChainWithState = {
    assume(brokerConfig.contains(group))
    intraGroupBlockChains(brokerConfig.groupIndexOfBroker(group))
  }

  def getHeaderChain(chainIndex: ChainIndex): BlockHeaderChain

  protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderChain = {
    blockHeaderChains(from.value)(to.value)
  }

  protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain = {
    blockHeaderChains(from.value)(to.value)
  }

  private[flow] def getPersistedWorldState(
      deps: BlockDeps,
      groupIndex: GroupIndex
  ): IOResult[WorldState.Persisted] = {
    assume(deps.length == brokerConfig.depsNum)
    val hash = deps.uncleHash(groupIndex)
    getBlockChainWithState(groupIndex).getPersistedWorldState(hash)
  }

  private[flow] def getDepStateHash(header: BlockHeader): IOResult[Hash] = {
    val groupIndex = header.chainIndex.from
    getDepStateHash(header.blockDeps, groupIndex)
  }

  private[flow] def getDepStateHash(deps: BlockDeps, groupIndex: GroupIndex): IOResult[Hash] = {
    val intraGroupDep = deps.getOutDep(groupIndex)
    getBlockChainWithState(groupIndex).getWorldStateHash(intraGroupDep)
  }

  def getCachedWorldState(
      deps: BlockDeps,
      groupIndex: GroupIndex
  ): IOResult[WorldState.Cached] = {
    assume(deps.length == brokerConfig.depsNum)
    val hash = deps.uncleHash(groupIndex)
    getBlockChainWithState(groupIndex).getCachedWorldState(hash)
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps = {
    val groupShift = brokerConfig.groupIndexOfBroker(groupIndex)
    bestDeps(groupShift)
  }

  def getBestPersistedWorldState(groupIndex: GroupIndex): IOResult[WorldState.Persisted] = {
    assume(brokerConfig.contains(groupIndex))
    val deps = getBestDeps(groupIndex)
    getPersistedWorldState(deps, groupIndex)
  }

  def getBestCachedWorldState(groupIndex: GroupIndex): IOResult[WorldState.Cached] = {
    assume(brokerConfig.contains(groupIndex))
    val deps = getBestDeps(groupIndex)
    getCachedWorldState(deps, groupIndex)
  }

  def getImmutableGroupView(
      mainGroup: GroupIndex
  ): IOResult[BlockFlowGroupView[WorldState.Persisted]] = {
    val blockDeps = getBestDeps(mainGroup)
    for {
      worldState  <- getPersistedWorldState(blockDeps, mainGroup)
      blockCaches <- getBlockCachesForUpdates(mainGroup, blockDeps)
    } yield BlockFlowGroupView.onlyBlocks(worldState, blockCaches)
  }

  def getMutableGroupView(
      mainGroup: GroupIndex
  ): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    getMutableGroupView(mainGroup, getBestDeps(mainGroup))
  }

  def getMemPool(mainGroup: GroupIndex): MemPool

  def getImmutableGroupViewIncludePool(
      mainGroup: GroupIndex
  ): IOResult[BlockFlowGroupView[WorldState.Persisted]] = {
    val blockDeps = getBestDeps(mainGroup)
    for {
      worldState  <- getPersistedWorldState(blockDeps, mainGroup)
      blockCaches <- getBlockCachesForUpdates(mainGroup, blockDeps)
    } yield BlockFlowGroupView.includePool(worldState, blockCaches, getMemPool(mainGroup))
  }

  def getMutableGroupViewIncludePool(
      mainGroup: GroupIndex
  ): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    val blockDeps = getBestDeps(mainGroup)
    for {
      worldState  <- getCachedWorldState(blockDeps, mainGroup)
      blockCaches <- getBlockCachesForUpdates(mainGroup, blockDeps)
    } yield BlockFlowGroupView.includePool(worldState, blockCaches, getMemPool(mainGroup))
  }

  def getMutableGroupView(block: Block): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    getMutableGroupView(block.chainIndex.from, block.blockDeps)
  }

  def getMutableGroupView(
      mainGroup: GroupIndex,
      blockDeps: BlockDeps
  ): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    for {
      worldState  <- getCachedWorldState(blockDeps, mainGroup)
      blockCaches <- getBlockCachesForUpdates(mainGroup, blockDeps)
    } yield BlockFlowGroupView.onlyBlocks(worldState, blockCaches)
  }

  def getMutableGroupView(
      mainGroup: GroupIndex,
      blockDeps: BlockDeps,
      worldState: WorldState.Cached
  ): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    getBlockCachesForUpdates(mainGroup, blockDeps).map { blockCaches =>
      BlockFlowGroupView.onlyBlocks(worldState, blockCaches)
    }
  }

  def updateBestDeps(mainGroup: Int, deps: BlockDeps): Unit = {
    assume(brokerConfig.containsRaw(mainGroup))
    val groupShift = brokerConfig.groupIndexOfBrokerUnsafe(mainGroup)
    bestDeps(groupShift) = deps
  }

  def getBlocksForUpdates(block: Block): IOResult[AVector[Block]] = {
    val chainIndex = block.chainIndex
    assume(chainIndex.isIntraGroup)
    for {
      diffs  <- getHashesForUpdates(chainIndex.from, block.blockDeps)
      blocks <- diffs.mapE(hash => getBlockChain(hash).getBlock(hash)).map(_ :+ block)
    } yield blocks
  }

  def getHashesForUpdates(groupIndex: GroupIndex): IOResult[AVector[BlockHash]] = {
    val bestDeps = getBestDeps(groupIndex)
    getHashesForUpdates(groupIndex, bestDeps)
  }

  def getHashesForUpdates(groupIndex: GroupIndex, deps: BlockDeps): IOResult[AVector[BlockHash]] = {
    val outDeps      = deps.outDeps
    val bestIntraDep = outDeps(groupIndex.value)
    for {
      newTips <- deps.inDeps.mapE(getInTip(_, groupIndex)).map(_ ++ outDeps)
      oldTips <- getInOutTips(bestIntraDep, groupIndex)
      diff    <- getTipsDiff(newTips, oldTips)
    } yield diff
  }

  def getBlockCachesForUpdates(
      groupIndex: GroupIndex,
      deps: BlockDeps
  ): IOResult[AVector[BlockCache]] = {
    for {
      diff        <- getHashesForUpdates(groupIndex, deps)
      blockCaches <- diff.mapE(getBlockCache(groupIndex, _))
    } yield blockCaches
  }

  // Note: update state only for intra group blocks
  def updateState(worldState: WorldState.Cached, block: Block): IOResult[Unit] = {
    val chainIndex = block.chainIndex
    assume(chainIndex.isIntraGroup)
    if (block.header.isGenesis) {
      BlockFlowState.updateState(worldState, block, chainIndex.from)
    } else {
      for {
        blocks <- getBlocksForUpdates(block)
        _ <- blocks
          .sortBy(_.timestamp)
          .foreachE(BlockFlowState.updateState(worldState, _, chainIndex.from))
      } yield ()
    }
  }
}
// scalastyle:on number.of.methods

object BlockFlowState {
  sealed trait BlockCache {
    def blockTime: TimeStamp
    def inputs: Set[TxOutputRef]
    def relatedOutputs: Map[TxOutputRef, TxOutput]
  }

  final case class InBlockCache(blockTime: TimeStamp, outputs: Map[TxOutputRef, TxOutput])
      extends BlockCache {
    def inputs: Set[TxOutputRef]                   = Set.empty
    def relatedOutputs: Map[TxOutputRef, TxOutput] = outputs
  }
  final case class OutBlockCache(
      blockTime: TimeStamp,
      inputs: Set[TxOutputRef],
      relatedOutputs: Map[TxOutputRef, TxOutput]
  ) extends BlockCache
  final case class InOutBlockCache(
      blockTime: TimeStamp,
      outputs: Map[TxOutputRef, TxOutput],
      inputs: Set[TxOutputRef]
  ) extends BlockCache { // For blocks on intra-group chain
    def relatedOutputs: Map[TxOutputRef, TxOutput] = outputs
  }

  private def convertInputs(block: Block): Set[TxOutputRef] = {
    block.transactions.flatMap { tx =>
      tx.unsigned.inputs.map[TxOutputRef](_.outputRef) ++ tx.contractInputs
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
    }
    outputs.toMap
  }

  // This is only used for out blocks for a specific group
  private def convertRelatedOutputs(block: Block, groupIndex: GroupIndex)(implicit
      brokerConfig: GroupConfig
  ): Map[TxOutputRef, TxOutput] = {
    convertOutputs(block).filter {
      case (outputRef: AssetOutputRef, _) if outputRef.fromGroup == groupIndex => true
      case _                                                                   => false
    }
  }

  def convertBlock(block: Block, groupIndex: GroupIndex)(implicit
      brokerConfig: BrokerConfig
  ): BlockCache = {
    val index = block.chainIndex
    assume(index.relateTo(groupIndex))
    if (index.isIntraGroup) {
      val outputs = convertOutputs(block)
      InOutBlockCache(block.timestamp, outputs, convertInputs(block))
    } else if (index.from == groupIndex) {
      OutBlockCache(block.timestamp, convertInputs(block), convertRelatedOutputs(block, groupIndex))
    } else {
      InBlockCache(block.timestamp, convertOutputs(block))
    }
  }

  def updateState(worldState: WorldState.Cached, block: Block, targetGroup: GroupIndex)(implicit
      brokerConfig: GroupConfig
  ): IOResult[Unit] = {
    val chainIndex = block.chainIndex
    assume(chainIndex.relateTo(targetGroup))
    if (chainIndex.isIntraGroup) {
      // note that script execution is already done in validation
      block.transactions.foreachE { tx =>
        updateStateForInOutBlock(worldState, tx, targetGroup, block.timestamp)
      }
    } else if (chainIndex.from == targetGroup) {
      block.transactions.foreachE(
        updateStateForOutBlock(worldState, _, targetGroup, block.timestamp)
      )
    } else if (chainIndex.to == targetGroup) {
      block.transactions.foreachE(
        updateStateForInBlock(worldState, _, targetGroup, block.timestamp)
      )
    } else {
      // dead branch
      Right(())
    }
  }

  def updateStateForInOutBlock(
      worldState: WorldState.Cached,
      tx: Transaction,
      targetGroup: GroupIndex,
      blockTs: TimeStamp
  )(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    for {
      _ <- updateStateForInputs(worldState, tx)
      _ <- updateStateForOutputs(worldState, tx, targetGroup, blockTs)
    } yield ()
  }

  def updateStateForOutBlock(
      worldState: WorldState.Cached,
      tx: Transaction,
      targetGroup: GroupIndex,
      blockTs: TimeStamp
  )(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    for {
      _ <- updateStateForInputs(worldState, tx)
      _ <- updateStateForOutputs(worldState, tx, targetGroup, blockTs)
    } yield ()
  }

  def updateStateForInBlock(
      worldState: WorldState.Cached,
      tx: Transaction,
      targetGroup: GroupIndex,
      blockTs: TimeStamp
  )(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    updateStateForOutputs(worldState, tx, targetGroup, blockTs)
  }

  // Note: contract inputs are updated during the execution of tx script
  def updateStateForInputs(worldState: WorldState.Cached, tx: Transaction): IOResult[Unit] = {
    tx.unsigned.inputs.foreachE(txInput => worldState.removeAsset(txInput.outputRef))
  }

  private def updateStateForOutputs(
      worldState: WorldState.Cached,
      tx: Transaction,
      targetGroup: GroupIndex,
      blockTs: TimeStamp
  )(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    tx.allOutputs.foreachWithIndexE {
      case (output: AssetOutput, index) if output.toGroup == targetGroup =>
        val outputRef = TxOutputRef.from(output, TxOutputRef.key(tx.id, index))
        val outputUpdated =
          if (output.lockTime < blockTs) output.copy(lockTime = blockTs) else output
        worldState.addAsset(outputRef, outputUpdated)
      case (_, _) => Right(()) // contract outputs are updated in VM
    }
  }

  sealed trait TxStatus

  final case class Confirmed(
      index: TxIndex,
      chainConfirmations: Int,
      fromGroupConfirmations: Int,
      toGroupConfirmations: Int
  ) extends TxStatus

  final case object MemPooled extends TxStatus
}
