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
import org.alephium.flow.setting.ConsensusSettings
import org.alephium.io.{IOError, IOResult, IOUtils, KeyValueStorage}
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.event.LogStorage
import org.alephium.protocol.vm.nodeindexes.{
  ConflictedTxsStorage,
  TxIdTxOutputLocators,
  TxOutputLocator
}
import org.alephium.protocol.vm.subcontractindex.SubContractIndexStorage
import org.alephium.util._

// scalastyle:off number.of.methods
trait BlockFlowState extends FlowTipsUtil {
  import BlockFlowState._

  implicit def brokerConfig: BrokerConfig
  implicit def networkConfig: NetworkConfig

  def consensusConfigs: ConsensusSettings
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
  @volatile protected[core] var bestFlowSkeleton = {
    val flow = BlockFlowSkeleton.Builder(groups)
    brokerConfig.cliqueGroups.foreach { g =>
      flow.setTip(g, genesisHashes(g.value)(g.value), genesisHashes(g.value))
    }
    flow.getResult()
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

  val logStorage: LogStorage = {
    assume(intraGroupBlockChains.nonEmpty, "No intraGroupBlockChains")
    intraGroupBlockChains.head.worldStateStorage.nodeIndexesStorage.logStorage
  }

  lazy val txOutputRefIndexStorage
      : IOResult[KeyValueStorage[TxOutputRef.Key, TxIdTxOutputLocators]] = {
    assume(intraGroupBlockChains.nonEmpty, "No intraGroupBlockChains")

    intraGroupBlockChains.head.worldStateStorage.nodeIndexesStorage.txOutputRefIndexStorage match {
      case Some(storage) =>
        Right(storage)
      case None =>
        Left(
          IOError.configError(
            "Please set `alephium.node.indexes.tx-output-ref-index = true` to query transaction id from transaction output reference"
          )
        )
    }
  }

  lazy val subContractIndexStorage: IOResult[SubContractIndexStorage] = {
    assume(intraGroupBlockChains.nonEmpty, "No intraGroupBlockChains")
    intraGroupBlockChains.head.worldStateStorage.nodeIndexesStorage.subContractIndexStorage match {
      case Some(storage) =>
        Right(storage)
      case None =>
        Left(
          IOError.configError(
            "Please set `alephium.node.indexes.subcontract-index = true` to query parent contract or subcontracts"
          )
        )
    }
  }

  val conflictedTxsStorage: ConflictedTxsStorage = {
    assume(intraGroupBlockChains.nonEmpty, "No intraGroupBlockChains")
    intraGroupBlockChains.head.worldStateStorage.nodeIndexesStorage.conflictedTxsStorage
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
      chain.cleanTips()
    })
    outBlockChains.foreach(_.foreach { chain =>
      chain.cleanTips()
    })
    intraGroupBlockChains.foreach { chain =>
      chain.cleanTips()
    }
    blockHeaderChains.foreach(_.foreach { chain =>
      chain.cleanTips()
    })
  }

  // Cache latest blocks for trie update, UTXO indexing
  lazy val groupCaches = AVector.fill(brokerConfig.groupNumPerBroker) {
    FlowCache.blockCaches(consensusConfigs.blockCacheCapacityPerChain)
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
    getPersistedWorldState(hash, groupIndex)
  }

  private[flow] def getPersistedWorldState(
      targetHash: BlockHash,
      groupIndex: GroupIndex
  ): IOResult[WorldState.Persisted] = {
    getBlockChainWithState(groupIndex).getPersistedWorldState(targetHash)
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

  def getBestFlowSkeleton(): BlockFlowSkeleton = {
    bestFlowSkeleton
  }

  def getPersistedWorldState(hash: BlockHash): IOResult[WorldState.Persisted] = {
    val chainIndex = ChainIndex.from(hash)
    assume(brokerConfig.contains(chainIndex.from))
    getBlockChainWithState(chainIndex.from).getPersistedWorldState(hash)
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

  // This should only be used for mempool tx handling
  def getImmutableGroupView(
      mainGroup: GroupIndex
  ): IOResult[BlockFlowGroupView[WorldState.Persisted]] = {
    val blockDeps = getBestDeps(mainGroup)
    for {
      worldState  <- getPersistedWorldState(blockDeps, mainGroup)
      blockCaches <- getBlockCachesForUpdates(mainGroup, blockDeps)
    } yield BlockFlowGroupView.onlyBlocks(worldState, blockCaches)
  }

  // This should only be used for mempool tx handling
  def getMutableGroupView(
      mainGroup: GroupIndex
  ): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    getMutableGroupViewPreDanube(mainGroup, getBestDeps(mainGroup))
  }

  def getMemPool(mainGroup: GroupIndex): MemPool

  // This should only be used for mempool tx handling
  def getImmutableGroupViewIncludePool(
      mainGroup: GroupIndex
  ): IOResult[BlockFlowGroupView[WorldState.Persisted]] = {
    getImmutableGroupViewIncludePool(mainGroup, None)
  }

  // This should only be used for mempool tx handling
  def getImmutableGroupViewIncludePool(
      mainGroup: GroupIndex,
      targetBlockHashOpt: Option[BlockHash]
  ): IOResult[BlockFlowGroupView[WorldState.Persisted]] = {
    for {
      blockDeps <- targetBlockHashOpt match {
        case Some(blockHash) => getBlockHeader(blockHash).map(_.blockDeps)
        case None            => Right(getBestDeps(mainGroup))
      }
      worldState  <- getPersistedWorldState(blockDeps, mainGroup)
      blockCaches <- getBlockCachesForGroupViewIncludePool(mainGroup, blockDeps)
    } yield BlockFlowGroupView.includePool(worldState, blockCaches, getMemPool(mainGroup))
  }

  // This should only be used for mempool tx handling
  def getMutableGroupViewIncludePool(
      mainGroup: GroupIndex
  ): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    val blockDeps = getBestDeps(mainGroup)
    for {
      worldState  <- getCachedWorldState(blockDeps, mainGroup)
      blockCaches <- getBlockCachesForGroupViewIncludePool(mainGroup, blockDeps)
    } yield BlockFlowGroupView.includePool(worldState, blockCaches, getMemPool(mainGroup))
  }

  def getMutableGroupViewForTxHandling(
      mainGroup: GroupIndex,
      blockDeps: BlockDeps
  ): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    getMutableGroupViewPreDanube(mainGroup, blockDeps)
  }

  def getMutableGroupViewPreDanube(
      block: Block
  ): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    getMutableGroupViewPreDanube(block.chainIndex.from, block.blockDeps)
  }

  def getMutableGroupViewPreDanube(
      mainGroup: GroupIndex,
      blockDeps: BlockDeps
  ): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    for {
      worldState  <- getCachedWorldState(blockDeps, mainGroup)
      blockCaches <- getBlockCachesForUpdates(mainGroup, blockDeps)
    } yield BlockFlowGroupView.onlyBlocks(worldState, blockCaches)
  }

  def getMutableGroupViewDanube(
      chainIndex: ChainIndex,
      blockDeps: BlockDeps
  ): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    if (chainIndex.isIntraGroup) {
      getCachedWorldState(blockDeps, chainIndex.from).map { worldState =>
        BlockFlowGroupView.onlyBlocks(worldState, AVector.empty)
      }
    } else {
      getMutableGroupViewPreDanube(chainIndex.from, blockDeps)
    }
  }

  def getMutableGroupView(
      chainIndex: ChainIndex,
      blockDeps: BlockDeps,
      hardFork: HardFork
  ): IOResult[BlockFlowGroupView[WorldState.Cached]] = {
    if (hardFork.isDanubeEnabled()) {
      getMutableGroupViewDanube(chainIndex, blockDeps)
    } else {
      getMutableGroupViewPreDanube(chainIndex.from, blockDeps)
    }
  }

  def getMutableGroupViewPreDanube(
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

  def updateBestFlowSkeleton(skeleton: BlockFlowSkeleton): Unit = {
    bestFlowSkeleton = skeleton
  }

  def getInterGroupBlocksForUpdates(block: Block): IOResult[AVector[Block]] = {
    val chainIndex = block.chainIndex
    assume(chainIndex.isIntraGroup)
    for {
      diffs  <- getHashesForUpdates(chainIndex.from, block.blockDeps)
      blocks <- diffs.mapE(hash => getBlockChain(hash).getBlock(hash))
    } yield blocks.sortBy(_.timestamp)
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

  def getIncomingBlockCaches(
      targetGroupIndex: GroupIndex,
      deps: BlockDeps
  ): IOResult[AVector[BlockCache]] = {
    for {
      deps   <- getIncomingBlockDeps(targetGroupIndex, deps)
      caches <- deps.mapE(getBlockCache(targetGroupIndex, _))
    } yield caches
  }

  def getBlockCachesForGroupViewIncludePool(
      mainGroup: GroupIndex,
      blockDeps: BlockDeps
  ): IOResult[AVector[BlockCache]] = {
    for {
      groupBlockCaches    <- getBlockCachesForUpdates(mainGroup, blockDeps)
      incomingBlockCaches <- getIncomingBlockCaches(mainGroup, blockDeps)
    } yield groupBlockCaches ++ incomingBlockCaches
  }

  /** Checks if a given intra-group block hash is part of the block flow by verifying if the tip of
    * the checkpoint block extends from the test block
    *
    * @param checkpointBlock
    *   The block used as a reference point for checking whether the test block is part of the block
    *   flow
    * @param testIntraBlock
    *   Hash of the intra-group block to test
    * @return
    *   true if testIntraBlock descends from the group tip at checkpointBlock, false otherwise
    */
  private def isInBlockFlowUnsafe(checkpointBlock: Block, testIntraBlock: BlockHash): Boolean = {
    val testBlockIndex = ChainIndex.from(testIntraBlock)
    assume(testBlockIndex.isIntraGroup) // Verify the test block is an intra-group block

    val tip = getGroupTip(checkpointBlock.header, testBlockIndex.from)
    isExtendingUnsafe(tip, testIntraBlock) // Check if the test block descends from the group tip
  }

  // Note: update state only for intra group blocks
  def updateState(worldState: WorldState.Cached, block: Block): IOResult[Unit] = {
    val chainIndex = block.chainIndex
    val hardfork   = networkConfig.getHardFork(block.timestamp)
    assume(chainIndex.isIntraGroup)
    if (block.header.isGenesis) {
      BlockFlowState.updateState(worldState, block, block, chainIndex.from, hardfork, _ => true)
    } else {
      for {
        interGroupBlocks <- getInterGroupBlocksForUpdates(block)
        // From the Danube upgrade, we execute the intra-group block first for two reasons:
        // 1. The state changes from contract execution can be reused
        // 2. Inter-group transactions are simple transfers, making it easier to handle potential conflicts
        blocks =
          if (hardfork.isDanubeEnabled()) block +: interGroupBlocks else interGroupBlocks :+ block
        _ <- blocks
          .foreachE(blockToUpdate =>
            BlockFlowState.updateState(
              worldState,
              block,
              blockToUpdate,
              chainIndex.from,
              hardfork,
              sourceIntraBlock => isInBlockFlowUnsafe(block, sourceIntraBlock)
            )
          )
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

  // Transaction validation for inter-group blocks:
  // - Post-Danube upgrade: Validate transactions to ensure inputs exist and are unspent
  // - Pre-Danube upgrade: Skip validation since it was not required in the protocol
  // scalastyle:off method.length
  def updateState(
      worldState: WorldState.Cached,
      checkpointBlock: Block,
      block: Block,
      targetGroup: GroupIndex,
      hardfork: HardFork,
      isInBlockFlowUnsafe: BlockHash => Boolean
  )(implicit
      brokerConfig: GroupConfig
  ): IOResult[Unit] = {
    val chainIndex = block.chainIndex
    assume(chainIndex.relateTo(targetGroup))
    if (chainIndex.isIntraGroup) {
      // note that script execution is already done in validation
      block.transactions.foreachWithIndexE { case (tx, txIndex) =>
        updateStateForInOutBlock(worldState, tx, txIndex, targetGroup, block)
      }
    } else if (chainIndex.from == targetGroup) {
      val conflictedTxs = mutable.ArrayBuffer.empty[TransactionId]
      val resultE = block.transactions.foreachWithIndexE { case (tx, txIndex) =>
        updateStateForOutBlock(
          worldState,
          tx,
          txIndex,
          targetGroup,
          block,
          hardfork,
          conflictedTxs
        )
      }
      resultE.flatMap { result =>
        if (conflictedTxs.nonEmpty) {
          worldState.nodeIndexesState.conflictedTxsStorageCache
            .addConflicts(
              checkpointBlock.hash,
              block.hash,
              AVector.from(conflictedTxs)
            )
            .map(_ => result)
        } else {
          Right(result)
        }
      }
    } else if (chainIndex.to == targetGroup) {
      block.transactions.foreachWithIndexE { case (tx, txIndex) =>
        updateStateForInBlock(
          worldState,
          tx,
          txIndex,
          targetGroup,
          block,
          hardfork,
          isInBlockFlowUnsafe
        )
      }
    } else {
      // dead branch
      Right(())
    }
  }
  // scalastyle:on method.length

  // For intra-group blocks (blocks within the same group), we skip input validation during state updates
  // because:
  // 1. Input validation was already performed during block validation
  // 2. Intra-group blocks are executed before inter-group blocks
  def updateStateForInOutBlock(
      worldState: WorldState.Cached,
      tx: Transaction,
      txIndex: Int,
      targetGroup: GroupIndex,
      block: Block
  )(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    for {
      _ <- updateStateForInputs(worldState, tx)
      _ <- updateStateForOutputs(worldState, tx, txIndex, targetGroup, block)
    } yield ()
  }

  // Outgoing blocks are executed with inputs validation
  // All outgoing blocks are inter-group blocks
  def updateStateForOutBlock(
      worldState: WorldState.Cached,
      tx: Transaction,
      txIndex: Int,
      targetGroup: GroupIndex,
      block: Block,
      hardfork: HardFork,
      conflictedTxs: mutable.ArrayBuffer[TransactionId]
  )(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    // Post-Danube upgrade, we validate that transaction inputs exist and are unspent
    // Pre-Danube upgrade, we skip this validation since it was not required in the protocol
    val conflictedTxE = if (hardfork.isDanubeEnabled()) {
      tx.unsigned.inputs.forallE(input => worldState.existOutput(input.outputRef))
    } else {
      Right(true)
    }

    conflictedTxE.flatMap { conflictedTx =>
      if (!conflictedTx) {
        for {
          _ <- updateStateForInputs(worldState, tx)
          _ <- updateStateForOutputs(worldState, tx, txIndex, targetGroup, block)
        } yield ()
      } else {
        conflictedTxs += tx.id
        Right(()) // Skip the transaction since its inputs are already spent (conflicted)
      }
    }
  }

  // For the Danube upgrade, we need to detect transaction conflicts before processing:
  // - For blocks from our own broker: Check the cache for conflicted transaction info
  // - For blocks from other brokers: Peers should send conflicted transaction info
  //
  // Note: All incoming blocks are inter-group blocks
  def updateStateForInBlock(
      worldState: WorldState.Cached,
      tx: Transaction,
      txIndex: Int,
      targetGroup: GroupIndex,
      block: Block,
      hardFork: HardFork,
      isInBlockFlowUnsafe: BlockHash => Boolean
  )(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    val shouldUpdateE = if (hardFork.isDanubeEnabled()) {
      val conflictedTxsIndex =
        worldState.nodeIndexesState.conflictedTxsStorageCache.conflictedTxsReversedIndex
      conflictedTxsIndex.getOpt(block.hash).flatMap {
        case Some(sources) =>
          IOUtils.tryExecute(
            !sources.exists(source =>
              source.txs.contains(tx.id) && isInBlockFlowUnsafe(source.intraBlock)
            )
          )
        case None => Right(true)
      }
    } else {
      Right(true)
    }
    shouldUpdateE.flatMap {
      case true =>
        updateStateForOutputs(worldState, tx, txIndex, targetGroup, block)
      case false => Right(())
    }
  }

  // Note: contract inputs are updated during the execution of tx script
  def updateStateForInputs(worldState: WorldState.Cached, tx: Transaction): IOResult[Unit] = {
    tx.unsigned.inputs.foreachE(txInput => worldState.removeAsset(txInput.outputRef))
  }

  private def updateStateForOutputs(
      worldState: WorldState.Cached,
      tx: Transaction,
      txIndex: Int,
      targetGroup: GroupIndex,
      block: Block
  )(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    val blockTs = block.timestamp
    tx.allOutputs.foreachWithIndexE {
      case (output: AssetOutput, index) if output.toGroup == targetGroup =>
        val outputRef = TxOutputRef.from(tx.id, index, output)
        val outputUpdated =
          if (output.lockTime < blockTs) output.copy(lockTime = blockTs) else output

        worldState.addAsset(
          outputRef,
          outputUpdated,
          tx.id,
          Some(TxOutputLocator(block.hash, txIndex, index))
        )
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
