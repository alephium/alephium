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

import com.typesafe.scalalogging.StrictLogging

import org.alephium.crypto.Blake3
import org.alephium.flow.Utils
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, ConsensusSettings, MemPoolSetting}
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.io.RocksDBSource.ProdSettings
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{BrokerConfig, GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LogConfig, WorldState}
import org.alephium.util.{AVector, Env, Files, TimeStamp}

trait BlockFlow
    extends MultiChain
    with BlockFlowState
    with FlowUtils
    with ConflictedBlocks
    with BlockFlowValidation {
  def add(block: Block, weight: Weight): IOResult[Unit] = ???

  def add(header: BlockHeader, weight: Weight): IOResult[Unit] = ???

  def addAndUpdateView(block: Block, worldStateOpt: Option[WorldState.Cached]): IOResult[Unit]

  def addAndUpdateView(header: BlockHeader): IOResult[Unit]

  def calWeight(block: Block): IOResult[Weight]

  override protected def getSyncLocatorsUnsafe(): AVector[(ChainIndex, AVector[BlockHash])] = {
    val range = brokerConfig.groupRange
    AVector.tabulate(range.length * groups) { index =>
      val offset     = index / groups
      val fromGroup  = range(offset)
      val toGroup    = index % groups
      val chainIndex = ChainIndex.unsafe(fromGroup, toGroup)
      chainIndex -> getSyncLocatorsUnsafe(ChainIndex.unsafe(fromGroup, toGroup))
    }
  }

  private def getSyncLocatorsUnsafe(chainIndex: ChainIndex): AVector[BlockHash] = {
    if (brokerConfig.contains(chainIndex.from)) {
      val chain     = getHeaderChain(chainIndex)
      val maxHeight = chain.maxHeightUnsafe
      if (maxHeight == ALPH.GenesisHeight) {
        AVector.empty
      } else {
        val startHeight = Math.max(ALPH.GenesisHeight + 1, maxHeight - 2 * maxForkDepth)
        HistoryLocators
          .sampleHeights(startHeight, maxHeight)
          .map(height => Utils.unsafe(chain.getHashes(height).map(_.head)))
      }
    } else {
      AVector.empty
    }
  }

  override protected def getSyncInventoriesUnsafe(
      locators: AVector[AVector[BlockHash]],
      peerBrokerInfo: BrokerGroupInfo
  ): AVector[AVector[BlockHash]] = {
    val range = brokerConfig.calIntersection(peerBrokerInfo)
    locators.mapWithIndex { (locatorsPerChain, index) =>
      val offset     = index / groups
      val fromGroup  = range(offset)
      val toGroup    = index % groups
      val chainIndex = ChainIndex.unsafe(fromGroup, toGroup)
      val chain      = getBlockChain(chainIndex)
      if (locatorsPerChain.isEmpty) {
        chain.getSyncDataFromHeightUnsafe(ALPH.GenesisHeight + 1)
      } else {
        chain.getSyncDataUnsafe(locatorsPerChain)
      }
    }
  }

  def getChainTipsUnsafe(): AVector[ChainTip] = {
    brokerConfig.chainIndexes.map { chainIndex =>
      val chain = getBlockChain(chainIndex)
      chain.maxWeightTipUnsafe
    }
  }

  override protected def getIntraSyncInventoriesUnsafe(): AVector[AVector[BlockHash]] = {
    AVector.tabulate(brokerConfig.groupNumPerBroker * brokerConfig.groups) { index =>
      val fromGroup = brokerConfig.groupRange(index / brokerConfig.groups)
      val toGroup   = index % brokerConfig.groups
      val chain     = getBlockChain(GroupIndex.unsafe(fromGroup), GroupIndex.unsafe(toGroup))
      chain.getLatestHashesUnsafe()
    }
  }

  // data should be valid, and parent should be in blockflow already
  def isRecent(data: FlowData): Boolean = {
    data.timestamp > TimeStamp.now().minusUnsafe(consensusConfigs.recentBlockTimestampDiff)
  }

  def getBestIntraGroupTip(): BlockHash
}

object BlockFlow extends StrictLogging {
  type WorldStateUpdater = (WorldState.Cached, Block) => IOResult[Unit]

  def emptyUnsafe(config: AlephiumConfig): BlockFlow = {
    emptyAndStoragesUnsafe(config)._1
  }

  def emptyAndStoragesUnsafe(config: AlephiumConfig): (BlockFlow, Storages) = {
    val storages =
      Storages.createUnsafe(Files.tmpDir, BlockHash.random.toHexString, ProdSettings.writeOptions)(
        config.broker,
        config.node
      )
    val blockFlow = fromGenesisUnsafe(storages, config.genesisBlocks)(
      config.broker,
      config.network,
      config.consensus,
      config.mempool,
      config.node.eventLogConfig
    )
    (blockFlow, storages)
  }

  def fromGenesisUnsafe(config: AlephiumConfig, storages: Storages): BlockFlow = {
    fromGenesisUnsafe(storages, config.genesisBlocks)(
      config.broker,
      config.network,
      config.consensus,
      config.mempool,
      config.node.eventLogConfig
    )
  }

  def fromGenesisUnsafe(storages: Storages, genesisBlocks: AVector[AVector[Block]])(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusSettings: ConsensusSettings,
      memPoolSetting: MemPoolSetting,
      logConfig: LogConfig
  ): BlockFlow = {
    val blockFlow = new BlockFlowImpl(
      genesisBlocks,
      BlockChainWithState.fromGenesisUnsafe(storages),
      BlockChain.fromGenesisUnsafe(storages),
      BlockHeaderChain.fromGenesisUnsafe(storages)
    )
    cacheBlockFlow(blockFlow)
    blockFlow
  }

  def fromStorageUnsafe(config: AlephiumConfig, storages: Storages): BlockFlow = {
    fromStorageUnsafe(storages, config.genesisBlocks)(
      config.broker,
      config.network,
      config.consensus,
      config.mempool,
      config.node.eventLogConfig
    )
  }

  private def cacheBlockFlow(
      blockflow: BlockFlow
  )(implicit consensusSettings: ConsensusSettings): Unit = {
    blockflow.inBlockChains.foreach(_.foreach { chain =>
      cacheBlockChain(blockflow, chain)
    })
    blockflow.outBlockChains.foreach(_.foreach { chain =>
      cacheBlockChain(blockflow, chain)
    })
    blockflow.blockHeaderChains.foreach(_.foreach(cacheHeaderChain))
  }

  private def cacheBlockChain(blockflow: BlockFlow, chain: BlockChain)(implicit
      consensusSettings: ConsensusSettings
  ): Unit = {
    val maxHeight = chain.maxHeightByWeightUnsafe
    val startHeight =
      Math.max(ALPH.GenesisHeight, maxHeight - consensusSettings.blockCacheCapacityPerChain)
    (startHeight to maxHeight).foreach { height =>
      val block = chain.getBlockUnsafe(chain.getHashesUnsafe(height).head)
      blockflow.cacheBlock(block)
      chain.cacheBlock(block)
    }
  }

  private def cacheHeaderChain(chain: BlockHeaderChain)(implicit
      consensusSettings: ConsensusSettings
  ): Unit = {
    val maxHeight = chain.maxHeightByWeightUnsafe
    val startHeight =
      Math.max(ALPH.GenesisHeight, maxHeight - consensusSettings.blockCacheCapacityPerChain * 2)
    (startHeight to maxHeight).foreach { height =>
      val header = chain.getBlockHeaderUnsafe(chain.getHashesUnsafe(height).head)
      chain.cacheHeader(header)
      chain.cacheState(header.hash, chain.getStateUnsafe(header.hash))
      chain.cacheHashes(height, chain.getHashesUnsafe(height))
    }
  }

  def fromStorageUnsafe(storages: Storages, genesisBlocks: AVector[AVector[Block]])(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusSettings: ConsensusSettings,
      memPoolSetting: MemPoolSetting,
      logConfig: LogConfig
  ): BlockFlow = {
    val blockflow = new BlockFlowImpl(
      genesisBlocks,
      BlockChainWithState.fromStorageUnsafe(storages),
      BlockChain.fromStorageUnsafe(storages),
      BlockHeaderChain.fromStorageUnsafe(storages)
    )
    blockflow.sanityCheckUnsafe()
    Env.forProd(logger.info(s"Load BlockFlow from storage: #${blockflow.numHashes} blocks/headers"))
    blockflow.updateBestDepsAfterLoadingUnsafe()
    cacheBlockFlow(blockflow)
    blockflow
  }

  class BlockFlowImpl(
      val genesisBlocks: AVector[AVector[Block]],
      val blockchainWithStateBuilder: (Block, BlockFlow.WorldStateUpdater) => BlockChainWithState,
      val blockchainBuilder: Block => BlockChain,
      val blockheaderChainBuilder: BlockHeader => BlockHeaderChain
  )(implicit
      val brokerConfig: BrokerConfig,
      val networkConfig: NetworkConfig,
      val consensusConfigs: ConsensusSettings,
      val mempoolSetting: MemPoolSetting,
      val logConfig: LogConfig
  ) extends BlockFlow {

    // for intra-group block, worldStateOpt should not be empty
    // for inter-group block, worldStateOpt should be empty
    def add(block: Block, worldStateOpt: Option[WorldState.Cached]): IOResult[Unit] = {
      val index = block.chainIndex
      assume(index.relateTo(brokerConfig))

      cacheBlock(block)
      if (brokerConfig.contains(block.chainIndex.from)) {
        cacheForConflicts(block)
      }
      for {
        weight <- calWeight(block)
        _      <- getBlockChain(index).add(block, weight, worldStateOpt)
      } yield {
        cacheDiffAndTimeSpan(block.header)
      }
    }

    def addAndUpdateView(block: Block, worldStateOpt: Option[WorldState.Cached]): IOResult[Unit] = {
      for {
        _ <- add(block, worldStateOpt)
        _ <- updateBestDeps()
      } yield ()
    }

    def add(header: BlockHeader): IOResult[Unit] = {
      val index = header.chainIndex
      assume(!index.relateTo(brokerConfig))

      for {
        weight <- calWeight(header)
        _      <- getHeaderChain(index).add(header, weight)
      } yield {
        cacheDiffAndTimeSpan(header)
      }
    }

    def addAndUpdateView(header: BlockHeader): IOResult[Unit] = {
      for {
        _ <- add(header)
        _ <- updateBestDeps()
      } yield ()
    }

    def calWeight(block: Block): IOResult[Weight] = {
      calWeight(block.header)
    }

    private def calWeight(header: BlockHeader): IOResult[Weight] = {
      IOUtils.tryExecute(calWeightUnsafe(header))
    }

    private def calWeightUnsafe(header: BlockHeader): Weight = {
      if (header.isGenesis) {
        ALPH.GenesisWeight
      } else {
        val targetGroup  = header.chainIndex.from
        val depsFlowTips = FlowTips.from(header.blockDeps, targetGroup)
        calWeightUnsafe(depsFlowTips, targetGroup) + header.weight
      }
    }

    private def calWeightUnsafe(currentFlowTips: FlowTips, targetGroup: GroupIndex): Weight = {
      val intraDep      = currentFlowTips.outTips(targetGroup.value)
      val intraFlowTips = getFlowTipsUnsafe(intraDep, targetGroup)
      val diffs         = getFlowTipsDiffUnsafe(currentFlowTips, intraFlowTips)

      val intraWeight = getWeightUnsafe(intraDep)
      calWeightUnsafe(intraWeight, diffs)
    }

    private def calWeightUnsafe(initialWeight: Weight, diffs: AVector[BlockHash]): Weight = {
      val diffsWeight = diffs.fold(Weight.zero) { case (acc, diff) =>
        acc + getBlockHeaderUnsafe(diff).weight
      }

      initialWeight + diffsWeight
    }

    def getBestTipUnsafe(): BlockHash = {
      aggregateHash(_.getBestTipUnsafe())(blockHashOrdering.max)
    }

    override def getAllTips: AVector[BlockHash] = {
      aggregateHash(_.getAllTips)(_ ++ _)
    }

    def tryExtendUnsafe(
        sourceTips: FlowTips,
        sourceWeight: Weight,
        group: GroupIndex,
        groupTip: BlockHash,
        toTry: AVector[BlockHash]
    ): (FlowTips, Weight) = {
      toTry
        .filter(isExtendingUnsafe(_, groupTip))
        .sorted(blockHashOrdering.reverse) // useful for draw situation
        .fold[(FlowTips, Weight)](sourceTips -> sourceWeight) { case ((maxTips, maxWeight), tip) =>
          tryMergeUnsafe(sourceTips, tip, group) match {
            case Some(merged) =>
              val diffs  = getFlowTipsDiffUnsafe(merged, sourceTips)
              val weight = calWeightUnsafe(sourceWeight, diffs)
              if (weight > maxWeight) (merged, weight) else (maxTips, maxWeight)
            case None => (maxTips, maxWeight)
          }
        }
    }

    def getBestIntraGroupTip(): BlockHash = {
      intraGroupHeaderChains.reduceBy(_.getBestTipUnsafe())(blockHashOrdering.max)
    }

    def calBestDepsUnsafe(group: GroupIndex): BlockDeps = {
      val bestTip    = getBestIntraGroupTip()
      val bestIndex  = ChainIndex.from(bestTip)
      val flowTips0  = getFlowTipsUnsafe(bestTip, group)
      val weight0    = getWeightUnsafe(bestTip)
      val groupOrder = BlockFlow.randomGroupOrders(bestTip)

      val (flowTips1, weight1) =
        (if (bestIndex.from == group) groupOrder.filter(_ != bestIndex.to.value) else groupOrder)
          .fold(flowTips0 -> weight0) { case ((tipsCur, weightCur), _r) =>
            val groupTip = tipsCur.outTips(_r)
            val r        = GroupIndex.unsafe(_r)
            val chain    = getHashChain(group, r)
            tryExtendUnsafe(tipsCur, weightCur, group, groupTip, chain.getAllTips)
          }
      val (flowTips2, _) = groupOrder
        .filter(g => g != group.value && g != bestIndex.from.value)
        .fold(flowTips1 -> weight1) { case ((tipsCur, weightCur), _l) =>
          val l        = GroupIndex.unsafe(_l)
          val toTry    = getHashChain(l, l).getAllTips
          val groupTip = if (_l < group.value) tipsCur.inTips(_l) else tipsCur.inTips(_l - 1)
          tryExtendUnsafe(tipsCur, weightCur, group, groupTip, toTry)
        }
      flowTips2.toBlockDeps
    }

    def updateBestDepsUnsafe(): Unit =
      brokerConfig.groupRange.foreach { mainGroup =>
        val mainGroupIndex = GroupIndex.unsafe(mainGroup)
        val oldDeps        = getBestDeps(mainGroupIndex)
        val newDeps        = calBestDepsUnsafe(mainGroupIndex)
        updateGrandPoolUnsafe(mainGroupIndex, newDeps, oldDeps)
        updateBestDeps(mainGroup, newDeps) // this update must go after pool updates
      }

    def updateBestDepsAfterLoadingUnsafe(): Unit =
      brokerConfig.groupRange.foreach { mainGroup =>
        val deps = calBestDepsUnsafe(GroupIndex.unsafe(mainGroup))
        updateBestDeps(mainGroup, deps)
      }

    def updateBestDeps(): IOResult[Unit] = {
      IOUtils.tryExecute(updateBestDepsUnsafe())
    }
  }

  def randomGroupOrders(hash: BlockHash)(implicit config: GroupConfig): AVector[Int] = {
    val groupOrders = Array.tabulate(config.groups)(identity)

    @tailrec
    def shuffle(index: Int, seed: Blake3): Unit = {
      if (index < groupOrders.length - 1) {
        val groupRemaining = groupOrders.length - index
        val randomIndex    = index + Math.floorMod(seed.toRandomIntUnsafe, groupRemaining)
        val tmp            = groupOrders(index)
        groupOrders(index) = groupOrders(randomIndex)
        groupOrders(randomIndex) = tmp
        shuffle(index + 1, Blake3.hash(seed.bytes))
      }
    }

    shuffle(0, hash.value)
    AVector.unsafe(groupOrders)
  }
}
