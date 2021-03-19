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

import scala.annotation.tailrec
import scala.collection.mutable

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockChain.TxIndex
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.{IOError, IOResult, IOUtils}
import org.alephium.protocol.{ALF, BlockHash, Hash, PublicKey}
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.util._
import org.alephium.util.Bytes.byteStringOrdering

// scalastyle:off number.of.methods
trait BlockFlowState extends FlowTipsUtil {
  import BlockFlowState._

  implicit def brokerConfig: BrokerConfig
  def consensusConfig: ConsensusSetting
  def groups: Int = brokerConfig.groups
  def genesisBlocks: AVector[AVector[Block]]

  lazy val genesisHashes: AVector[AVector[BlockHash]] = genesisBlocks.map(_.map(_.hash))
  lazy val bestGenesisHashes: AVector[BlockHash]      = genesisHashes.map(_.maxBy(_.bytes))

  protected[core] val bestDeps = Array.tabulate(brokerConfig.groupNumPerBroker) { fromShift =>
    val mainGroup = brokerConfig.groupFrom + fromShift
    val deps1 = AVector.tabulate(groups - 1) { i =>
      if (i < mainGroup) {
        bestGenesisHashes(i)
      } else {
        bestGenesisHashes(i + 1)
      }
    }
    val deps2 = genesisBlocks(mainGroup).map(_.hash)
    BlockDeps.build(deps1 ++ deps2)
  }

  def blockchainWithStateBuilder: (Block, BlockFlow.WorldStateUpdater) => BlockChainWithState
  def blockchainBuilder: Block                                         => BlockChain
  def blockheaderChainBuilder: BlockHeader                             => BlockHeaderChain

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
    LruCache[BlockHash, BlockCache, IOError](
      consensusConfig.blockCacheCapacityPerChain * brokerConfig.depsNum)
  }

  def getGroupCache(groupIndex: GroupIndex): LruCache[BlockHash, BlockCache, IOError] = {
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

  def getBlockCache(groupIndex: GroupIndex, hash: BlockHash): IOResult[BlockCache] = {
    assume(ChainIndex.from(hash).relateTo(groupIndex))
    getGroupCache(groupIndex).get(hash) {
      getBlockChain(hash).getBlock(hash).map(convertBlock(_, groupIndex))
    }
  }

  protected def aggregateHash[T](f: BlockHashPool => T)(op: (T, T) => T): T = {
    blockHeaderChains.reduceBy { chains =>
      chains.reduceBy(f)(op)
    }(op)
  }

  protected def aggregateHashE[T](f: BlockHashPool => IOResult[T])(op: (T, T) => T): IOResult[T] = {
    blockHeaderChains.reduceByE { chains =>
      chains.reduceByE(f)(op)
    }(op)
  }

  protected def aggregateHeaderE[T](f: BlockHeaderPool => IOResult[T])(
      op: (T, T)                                       => T): IOResult[T] = {
    blockHeaderChains.reduceByE { chains =>
      chains.reduceByE(f)(op)
    }(op)
  }

  def getBlockChain(hash: BlockHash): BlockChain

  def getBlockChain(chainIndex: ChainIndex): BlockChain

  protected def getBlockChain(from: GroupIndex, to: GroupIndex): BlockChain = {
    assume(brokerConfig.contains(from) || brokerConfig.contains(to))
    if (brokerConfig.contains(from)) {
      outBlockChains(from.value - brokerConfig.groupFrom)(to.value)
    } else {
      val fromIndex =
        if (from.value < brokerConfig.groupFrom) {
          from.value
        } else {
          from.value - brokerConfig.groupNumPerBroker
        }
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

  protected def getPersistedWorldState(deps: BlockDeps,
                                       groupIndex: GroupIndex): IOResult[WorldState.Persisted] = {
    assume(deps.length == brokerConfig.depsNum)
    val hash = deps.uncleHash(groupIndex)
    getBlockChainWithState(groupIndex).getPersistedWorldState(hash)
  }

  protected def getCachedWorldState(deps: BlockDeps,
                                    groupIndex: GroupIndex): IOResult[WorldState.Cached] = {
    assume(deps.length == brokerConfig.depsNum)
    val hash = deps.uncleHash(groupIndex)
    getBlockChainWithState(groupIndex).getCachedWorldState(hash)
  }

  def getPersistedWorldState(block: Block): IOResult[WorldState.Persisted] = {
    val header = block.header
    getPersistedWorldState(header.blockDeps, header.chainIndex.from)
  }

  def getCachedWorldState(block: Block): IOResult[WorldState.Cached] = {
    val header = block.header
    getCachedWorldState(header.blockDeps, header.chainIndex.from)
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps = {
    val groupShift = groupIndex.value - brokerConfig.groupFrom
    bestDeps(groupShift)
  }

  def getBestHeight(chainIndex: ChainIndex): IOResult[Int] = {
    val bestParent = getBestDeps(chainIndex.from).getOutDep(chainIndex.to)
    getHashChain(chainIndex.from, chainIndex.to).getHeight(bestParent)
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

  def getHashesForUpdates(groupIndex: GroupIndex): IOResult[AVector[BlockHash]] = {
    val bestDeps = getBestDeps(groupIndex)
    getHashesForUpdates(groupIndex, bestDeps)
  }

  def getHashesForUpdates(groupIndex: GroupIndex, deps: BlockDeps): IOResult[AVector[BlockHash]] = {
    val outDeps      = deps.outDeps
    val bestIntraDep = outDeps(groupIndex.value)
    for {
      newTips <- deps.inDeps.mapE(getInTip(_, groupIndex)).map(_ ++ outDeps)
      oldTips <- getInOutTips(bestIntraDep, groupIndex, inclusive = true)
      diff    <- getTipsDiff(newTips, oldTips)
    } yield diff
  }

  def getHashesForUpdatesUnsafe(groupIndex: GroupIndex, deps: BlockDeps): AVector[BlockHash] = {
    Utils.unsafe(getHashesForUpdates(groupIndex, deps))
  }

  def getBlocksForUpdates(groupIndex: GroupIndex): IOResult[AVector[BlockCache]] = {
    for {
      diff        <- getHashesForUpdates(groupIndex)
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
        _      <- blocks.foreachE(BlockFlowState.updateState(worldState, _, chainIndex.from))
      } yield ()
    }
  }

  private def ableToUse(output: TxOutput,
                        lockupScript: LockupScript,
                        currentTs: TimeStamp): Boolean = output match {
    case o: AssetOutput    => o.lockupScript == lockupScript && o.lockTime <= currentTs
    case _: ContractOutput => false
  }

  def getUtxos(lockupScript: LockupScript): IOResult[AVector[(AssetOutputRef, AssetOutput)]] = {
    val groupIndex = lockupScript.groupIndex
    val currentTs  = TimeStamp.now()
    assume(brokerConfig.contains(groupIndex))

    for {
      bestWorldState <- getBestPersistedWorldState(groupIndex)
      persistedUtxos <- bestWorldState
        .getAssetOutputs(lockupScript.assetHintBytes)
        .map(_.filter(p => ableToUse(p._2, lockupScript, currentTs)))
    } yield persistedUtxos
  }

  def getBalance(lockupScript: LockupScript): IOResult[(U256, Int)] = {
    getUtxos(lockupScript).map { utxos =>
      val balance = utxos.fold(U256.Zero)(_ addUnsafe _._2.amount)
      (balance, utxos.length)
    }
  }

  def getUtxosInCache(lockupScript: LockupScript,
                      groupIndex: GroupIndex,
                      persistedUtxos: AVector[(AssetOutputRef, AssetOutput)])
    : IOResult[(AVector[TxOutputRef], AVector[(AssetOutputRef, AssetOutput)])] = {
    val currentTs = TimeStamp.now()
    getBlocksForUpdates(groupIndex).map { blockCaches =>
      val usedUtxos = blockCaches.flatMap[TxOutputRef] { blockCache =>
        AVector.from(blockCache.inputs.view.filter(input => persistedUtxos.exists(_._1 == input)))
      }
      val newUtxos = blockCaches.flatMap { blockCache =>
        AVector
          .from(blockCache.relatedOutputs.view.filter(p =>
            ableToUse(p._2, lockupScript, currentTs) && p._1.isAssetType && p._2.isAsset))
          .asUnsafe[(AssetOutputRef, AssetOutput)]
      }
      (usedUtxos, newUtxos)
    }
  }

  def prepareUnsignedTx(fromKey: PublicKey,
                        toLockupScript: LockupScript,
                        lockTimeOpt: Option[TimeStamp],
                        amount: U256): IOResult[Either[String, UnsignedTransaction]] = {
    val fromLockupScript = LockupScript.p2pkh(fromKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromKey)
    getUtxos(fromLockupScript).map { utxos =>
      for {
        selected <- UtxoUtils.select(utxos,
                                     amount,
                                     defaultGasFee,
                                     defaultGasFeePerInput,
                                     defaultGasFeePerOutput,
                                     2) // sometime only 1 output, but 2 is always safe
        gas <- GasBox
          .from(selected.gasFee, defaultGasPrice)
          .toRight(s"Invalid gas: ${selected.gasFee} / $defaultGasPrice")
        unsignedTx <- UnsignedTransaction
          .transferAlf(
            selected.assets,
            fromLockupScript,
            fromUnlockScript,
            toLockupScript,
            lockTimeOpt,
            amount,
            gas,
            defaultGasPrice
          )
      } yield {
        unsignedTx
      }
    }
  }

  def getTxStatus(txId: Hash, chainIndex: ChainIndex): IOResult[Option[TxStatus]] =
    IOUtils.tryExecute {
      assume(brokerConfig.contains(chainIndex.from))
      val chain = getBlockChain(chainIndex)
      chain.getTxStatusUnsafe(txId).flatMap { chainStatus =>
        val confirmations = chainStatus.confirmations
        if (chainIndex.isIntraGroup) {
          Some(TxStatus(chainStatus.index, confirmations, confirmations, confirmations))
        } else {
          val confirmHash = chainStatus.index.hash
          val fromGroupConfirmations =
            getFromGroupConfirmationsUnsafe(confirmHash, chainIndex)
          val toGroupConfirmations =
            getToGroupConfirmationsUnsafe(confirmHash, chainIndex)
          Some(
            TxStatus(chainStatus.index,
                     confirmations,
                     fromGroupConfirmations,
                     toGroupConfirmations))
        }
      }
    }

  def getFromGroupConfirmationsUnsafe(hash: BlockHash, chainIndex: ChainIndex): Int = {
    assume(ChainIndex.from(hash) == chainIndex)
    val header        = getBlockHeaderUnsafe(hash)
    val fromChain     = getHeaderChain(chainIndex.from, chainIndex.from)
    val fromTip       = getOutTip(header, chainIndex.from)
    val fromTipHeight = fromChain.getHeightUnsafe(fromTip)

    @tailrec
    def iter(height: Int): Option[Int] = {
      val hashes = fromChain.getHashesUnsafe(height)
      if (hashes.isEmpty) {
        None
      } else {
        val header   = fromChain.getBlockHeaderUnsafe(hashes.head)
        val chainDep = header.uncleHash(chainIndex.to)
        if (fromChain.isBeforeUnsafe(hash, chainDep)) Some(height) else iter(height + 1)
      }
    }

    iter(fromTipHeight + 1) match {
      case None => 0
      case Some(firstConfirmationHeight) =>
        fromChain.maxHeightUnsafe - firstConfirmationHeight + 1
    }
  }

  def getToGroupConfirmationsUnsafe(hash: BlockHash, chainIndex: ChainIndex): Int = {
    assume(ChainIndex.from(hash) == chainIndex)
    val header        = getBlockHeaderUnsafe(hash)
    val toChain       = getHeaderChain(chainIndex.to, chainIndex.to)
    val toGroupTip    = getGroupTip(header, chainIndex.to)
    val toGroupHeader = getBlockHeaderUnsafe(toGroupTip)
    val toTip         = getOutTip(toGroupHeader, chainIndex.to)
    val toTipHeight   = toChain.getHeightUnsafe(toTip)

    assume(ChainIndex.from(toTip) == ChainIndex(chainIndex.to, chainIndex.to))

    @tailrec
    def iter(height: Int): Option[Int] = {
      val hashes = toChain.getHashesUnsafe(height)
      if (hashes.isEmpty) {
        None
      } else {
        val header   = toChain.getBlockHeaderUnsafe(hashes.head)
        val chainDep = getGroupTip(header, chainIndex.from)
        if (isExtendingUnsafe(chainDep, hash)) Some(height) else iter(height + 1)
      }
    }

    if (header.isGenesis) {
      toChain.maxHeightUnsafe - ALF.GenesisHeight + 1
    } else {
      iter(toTipHeight + 1) match {
        case None => 0
        case Some(firstConfirmationHeight) =>
          toChain.maxHeightUnsafe - firstConfirmationHeight + 1
      }
    }
  }
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
        tx.contractInputs.as[TxOutputRef]
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

  def updateState(worldState: WorldState.Cached, block: Block, targetGroup: GroupIndex)(
      implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    val chainIndex = block.chainIndex
    assume(chainIndex.relateTo(targetGroup))
    if (chainIndex.isIntraGroup) {
      for {
        _ <- block.getScriptExecutionOrder.foreachE { index =>
          updateStateForTxScript(worldState, block.transactions(index))
        }
        _ <- block.transactions.foreachE { tx =>
          updateStateForInOutBlock(worldState, tx, targetGroup)
        }
      } yield ()
    } else if (chainIndex.from == targetGroup) {
      block.transactions.foreachE(updateStateForOutBlock(worldState, _, targetGroup))
    } else if (chainIndex.to == targetGroup) {
      block.transactions.foreachE(updateStateForInBlock(worldState, _, targetGroup))
    } else {
      // dead branch though
      Right(())
    }
  }

  def updateStateForInOutBlock(
      worldState: WorldState.Cached,
      tx: Transaction,
      targetGroup: GroupIndex)(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    for {
      _ <- updateStateForInputs(worldState, tx)
      _ <- updateStateForOutputs(worldState, tx, targetGroup)
    } yield ()
  }

  def updateStateForOutBlock(
      worldState: WorldState.Cached,
      tx: Transaction,
      targetGroup: GroupIndex)(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    for {
      _ <- updateStateForInputs(worldState, tx)
      _ <- updateStateForOutputs(worldState, tx, targetGroup)
    } yield ()
  }

  def updateStateForInBlock(
      worldState: WorldState.Cached,
      tx: Transaction,
      targetGroup: GroupIndex)(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    updateStateForOutputs(worldState, tx, targetGroup)
  }

  def updateStateForTxScript(worldState: WorldState.Cached, tx: Transaction): IOResult[Unit] = {
    tx.unsigned.scriptOpt match {
      case Some(script) =>
        // we set gasRemaining = initial gas as the tx is already validated
        StatefulVM.runTxScript(worldState, tx, script, tx.unsigned.startGas) match {
          case Right(_)                        => Right(())
          case Left(IOErrorUpdateState(error)) => Left(error)
          case Left(error) =>
            throw new RuntimeException(s"Updating world state for invalid tx: $error")
        }
      case None => Right(())
    }
  }

  // Note: contract inputs are updated during the execution of tx script
  def updateStateForInputs(worldState: WorldState.Cached, tx: Transaction): IOResult[Unit] = {
    tx.unsigned.inputs.foreachE(txInput => worldState.removeAsset(txInput.outputRef))
  }

  private def updateStateForOutputs(
      worldState: WorldState.Cached,
      tx: Transaction,
      targetGroup: GroupIndex)(implicit brokerConfig: GroupConfig): IOResult[Unit] = {
    tx.allOutputs.foreachWithIndexE {
      case (output: AssetOutput, index) if output.toGroup == targetGroup =>
        val outputRef = TxOutputRef.from(output, TxOutputRef.key(tx.id, index))
        worldState.addAsset(outputRef, output)
      case (_, _) => Right(()) // contract outputs are updated in VM
    }
  }

  final case class TxStatus(index: TxIndex,
                            chainConfirmations: Int,
                            fromGroupConfirmations: Int,
                            toGroupConfirmations: Int)
}
