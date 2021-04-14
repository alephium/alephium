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

import java.math.BigInteger

import scala.annotation.tailrec

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.Utils
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, ConsensusSetting, MemPoolSetting}
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.{ALF, BlockHash}
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.WorldState
import org.alephium.util.{AVector, TimeStamp}

trait BlockFlow
    extends MultiChain
    with BlockFlowState
    with FlowUtils
    with ConflictedBlocks
    with BlockFlowValidation {
  def add(block: Block, weight: BigInteger): IOResult[Unit] = ???

  def add(header: BlockHeader, weight: BigInteger): IOResult[Unit] = ???

  def addNew(block: Block): IOResult[AVector[TransactionTemplate]]

  def addNew(header: BlockHeader): IOResult[AVector[TransactionTemplate]]

  override protected def getSyncLocatorsUnsafe(): AVector[AVector[BlockHash]] = {
    getSyncLocatorsUnsafe(brokerConfig)
  }

  private def getSyncLocatorsUnsafe(
      peerBrokerInfo: BrokerGroupInfo
  ): AVector[AVector[BlockHash]] = {
    val (groupFrom, groupUntil) = brokerConfig.calIntersection(peerBrokerInfo)
    AVector.tabulate((groupUntil - groupFrom) * groups) { index =>
      val offset    = index / groups
      val fromGroup = groupFrom + offset
      val toGroup   = index % groups
      getSyncLocatorsUnsafe(ChainIndex.unsafe(fromGroup, toGroup))
    }
  }

  private def getSyncLocatorsUnsafe(chainIndex: ChainIndex): AVector[BlockHash] = {
    if (brokerConfig.contains(chainIndex.from)) {
      val chain = getHeaderChain(chainIndex)
      HistoryLocators
        .sampleHeights(ALF.GenesisHeight, chain.maxHeightUnsafe)
        .map(height => Utils.unsafe(chain.getHashes(height).map(_.head)))
    } else {
      AVector.empty
    }
  }

  override protected def getSyncInventoriesUnsafe(
      locators: AVector[AVector[BlockHash]]
  ): AVector[AVector[BlockHash]] = {
    locators.map { locatorsPerChain =>
      if (locatorsPerChain.isEmpty) {
        AVector.empty[BlockHash]
      } else {
        val chainIndex = ChainIndex.from(locatorsPerChain.head)
        val chain      = getBlockChain(chainIndex)
        chain.getSyncDataUnsafe(locatorsPerChain)
      }
    }
  }

  override protected def getIntraSyncInventoriesUnsafe(
      remoteBroker: BrokerGroupInfo
  ): AVector[AVector[BlockHash]] = {
    AVector.tabulate(brokerConfig.groupNumPerBroker * remoteBroker.groupNumPerBroker) { index =>
      val k         = index / remoteBroker.groupNumPerBroker
      val l         = index % remoteBroker.groupNumPerBroker
      val fromGroup = brokerConfig.groupFrom + k
      val toGroup   = remoteBroker.groupFrom + l
      val chain     = getBlockChain(GroupIndex.unsafe(fromGroup), GroupIndex.unsafe(toGroup))
      chain.getLatestHashesUnsafe()
    }
  }

  // data should be valid, and parent should be in blockflow already
  def isRecent(data: FlowData): Boolean = {
    data.timestamp > TimeStamp.now().minusUnsafe(consensusConfig.recentBlockTimestampDiff)
  }
}

object BlockFlow extends StrictLogging {
  type WorldStateUpdater = (WorldState.Cached, Block) => IOResult[Unit]

  def fromGenesisUnsafe(config: AlephiumConfig, storages: Storages): BlockFlow = {
    fromGenesisUnsafe(storages, config.genesisBlocks)(
      config.broker,
      config.consensus,
      config.mempool
    )
  }

  def fromGenesisUnsafe(storages: Storages, genesisBlocks: AVector[AVector[Block]])(implicit
      brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting,
      memPoolSetting: MemPoolSetting
  ): BlockFlow = {
    logger.info(s"Initialize storage for BlockFlow")
    new BlockFlowImpl(
      genesisBlocks,
      BlockChainWithState.fromGenesisUnsafe(storages),
      BlockChain.fromGenesisUnsafe(storages),
      BlockHeaderChain.fromGenesisUnsafe(storages)
    )
  }

  def fromStorageUnsafe(config: AlephiumConfig, storages: Storages): BlockFlow = {
    fromStorageUnsafe(storages, config.genesisBlocks)(
      config.broker,
      config.consensus,
      config.mempool
    )
  }

  def fromStorageUnsafe(storages: Storages, genesisBlocks: AVector[AVector[Block]])(implicit
      brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting,
      memPoolSetting: MemPoolSetting
  ): BlockFlow = {
    val blockflow = new BlockFlowImpl(
      genesisBlocks,
      BlockChainWithState.fromStorageUnsafe(storages),
      BlockChain.fromStorageUnsafe(storages),
      BlockHeaderChain.fromStorageUnsafe(storages)
    )
    logger.info(s"Load BlockFlow from storage: #${blockflow.numHashes} blocks/headers")
    blockflow.updateBestDepsAfterLoadingUnsafe()
    blockflow
  }

  class BlockFlowImpl(
      val genesisBlocks: AVector[AVector[Block]],
      val blockchainWithStateBuilder: (Block, BlockFlow.WorldStateUpdater) => BlockChainWithState,
      val blockchainBuilder: Block => BlockChain,
      val blockheaderChainBuilder: BlockHeader => BlockHeaderChain
  )(implicit
      val brokerConfig: BrokerConfig,
      val consensusConfig: ConsensusSetting,
      val mempoolSetting: MemPoolSetting
  ) extends BlockFlow {

    def add(block: Block): IOResult[Unit] = {
      addNew(block).map(_ => ())
    }

    def addNew(block: Block): IOResult[AVector[TransactionTemplate]] = {
      val index = block.chainIndex
      assume(index.relateTo(brokerConfig))

      cacheBlock(block)
      if (brokerConfig.contains(block.chainIndex.from)) {
        cacheForConflicts(block)
      }
      for {
        weight       <- calWeight(block)
        _            <- getBlockChain(index).add(block, weight)
        newPooledTxs <- updateBestDeps()
      } yield newPooledTxs
    }

    def add(header: BlockHeader): IOResult[Unit] = {
      addNew(header).map(_ => ())
    }

    def addNew(header: BlockHeader): IOResult[AVector[TransactionTemplate]] = {
      val index = header.chainIndex
      assume(!index.relateTo(brokerConfig))

      for {
        weight       <- calWeight(header)
        _            <- getHeaderChain(index).add(header, weight)
        newPooledTxs <- updateBestDeps()
      } yield newPooledTxs
    }

    private def calWeight(block: Block): IOResult[BigInteger] = {
      calWeight(block.header)
    }

    private def calWeight(header: BlockHeader): IOResult[BigInteger] = {
      IOUtils.tryExecute(calWeightUnsafe(header))
    }

    private def calWeightUnsafe(header: BlockHeader): BigInteger = {
      if (header.isGenesis) {
        ALF.GenesisWeight multiply BigInt(brokerConfig.chainNum).bigInteger
      } else {
        val targetGroup  = header.chainIndex.from
        val depsFlowTips = FlowTips.from(header.blockDeps, targetGroup)
        calWeightUnsafe(depsFlowTips, targetGroup) add header.target.value
      }
    }

    private def calWeightUnsafe(currentFlowTips: FlowTips, targetGroup: GroupIndex): BigInteger = {
      val intraDep      = currentFlowTips.outTips(targetGroup.value)
      val intraFlowTips = getFlowTipsUnsafe(intraDep, targetGroup)
      val diffs         = getFlowTipsDiffUnsafe(currentFlowTips, intraFlowTips)

      val intraWeight = getWeightUnsafe(intraDep)
      val diffsWeight = diffs.fold(BigInteger.ZERO) { case (acc, diff) =>
        acc add getBlockHeaderUnsafe(diff).target.value
      }

      intraWeight add diffsWeight
    }

    def getBestTipUnsafe: BlockHash = {
      aggregateHash(_.getBestTipUnsafe)(blockHashOrdering.max)
    }

    override def getAllTips: AVector[BlockHash] = {
      aggregateHash(_.getAllTips)(_ ++ _)
    }

    def tryExtendUnsafe(
        tipsCur: FlowTips,
        weightCur: BigInteger,
        group: GroupIndex,
        toTry: AVector[BlockHash],
        bestTip: BlockHash
    ): (FlowTips, BigInteger) = {
      toTry
        .sorted(blockHashOrdering.reverse) // useful for draw situation
        .fold[(FlowTips, BigInteger)](tipsCur -> weightCur) { case ((maxTips, maxWeight), tip) =>
          // only consider tips < bestTip
          if (blockHashOrdering.lt(tip, bestTip)) {
            tryMergeUnsafe(tipsCur, tip, group, checkTxConflicts = true) match {
              case Some(merged) =>
                val weight = calWeightUnsafe(merged, group)
                if (weight.compareTo(maxWeight) > 0) (merged, weight) else (maxTips, maxWeight)
              case None => (maxTips, maxWeight)
            }
          } else {
            maxTips -> maxWeight
          }
        }
    }

    def calBestDepsUnsafe(group: GroupIndex): BlockDeps = {
      val bestTip    = getBestTipUnsafe
      val bestIndex  = ChainIndex.from(bestTip)
      val flowTips0  = getFlowTipsUnsafe(bestTip, group)
      val weight0    = calWeightUnsafe(flowTips0, group)
      val groupOrder = BlockFlow.randomGroupOrders(bestTip)

      val (flowTips1, weight1) =
        (if (bestIndex.from == group) groupOrder.filter(_ != bestIndex.to.value) else groupOrder)
          .fold(flowTips0 -> weight0) { case ((tipsCur, weightCur), _r) =>
            val r     = GroupIndex.unsafe(_r)
            val chain = getHashChain(group, r)
            tryExtendUnsafe(tipsCur, weightCur, group, chain.getAllTips, bestTip)
          }
      val (flowTips2, _) = groupOrder
        .filter(g => g != group.value && g != bestIndex.from.value)
        .fold(flowTips1 -> weight1) { case ((tipsCur, weightCur), _l) =>
          val l = GroupIndex.unsafe(_l)
          val toTry = (0 until groups).foldLeft(AVector.empty[BlockHash]) { (acc, _r) =>
            val r = GroupIndex.unsafe(_r)
            acc ++ getHashChain(l, r).getAllTips
          }
          tryExtendUnsafe(tipsCur, weightCur, group, toTry, bestTip)
        }
      flowTips2.toBlockDeps
    }

    def updateBestDepsUnsafe(): AVector[TransactionTemplate] =
      (brokerConfig.groupFrom until brokerConfig.groupUntil).foldLeft(
        AVector.empty[TransactionTemplate]
      ) { case (acc, mainGroup) =>
        val mainGroupIndex = GroupIndex.unsafe(mainGroup)
        val oldDeps        = getBestDeps(mainGroupIndex)
        val newDeps        = calBestDepsUnsafe(mainGroupIndex)
        val result         = acc ++ updateGrandPoolUnsafe(mainGroupIndex, newDeps, oldDeps)
        updateBestDeps(mainGroup, newDeps) // this update must go after pool updates
        result
      }

    def updateBestDepsAfterLoadingUnsafe(): Unit =
      brokerConfig.groupFrom until brokerConfig.groupUntil foreach { mainGroup =>
        val deps = calBestDepsUnsafe(GroupIndex.unsafe(mainGroup))
        updateBestDeps(mainGroup, deps)
      }

    def updateBestDeps(): IOResult[AVector[TransactionTemplate]] = {
      IOUtils.tryExecute(updateBestDepsUnsafe())
    }
  }

  def randomGroupOrders(hash: BlockHash)(implicit config: GroupConfig): AVector[Int] = {
    val groupOrders = Array.tabulate(config.groups)(identity)

    @tailrec
    def shuffle(index: Int, seed: BlockHash): Unit = {
      if (index < groupOrders.length - 1) {
        val groupRemaining = groupOrders.length - index
        val randomIndex    = index + Math.floorMod(seed.toRandomIntUnsafe, groupRemaining)
        val tmp            = groupOrders(index)
        groupOrders(index) = groupOrders(randomIndex)
        groupOrders(randomIndex) = tmp
        shuffle(index + 1, BlockHash.hash(seed.bytes))
      }
    }

    shuffle(0, hash)
    AVector.unsafe(groupOrders)
  }
}
