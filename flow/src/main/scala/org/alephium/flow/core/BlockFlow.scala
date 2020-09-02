package org.alephium.flow.core

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.Utils
import org.alephium.flow.io.Storages
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.setting.{AlephiumConfig, ConsensusSetting, MemPoolSetting}
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.{ALF, Hash}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.WorldState
import org.alephium.util.{AVector, TimeStamp}

trait BlockFlow extends MultiChain with BlockFlowState with FlowUtils {
  def add(block: Block, weight: BigInt): IOResult[Unit] = ???

  def add(header: BlockHeader, weight: BigInt): IOResult[Unit] = ???

  override protected def getSyncLocatorsUnsafe(): AVector[AVector[Hash]] = {
    getSyncLocatorsUnsafe(brokerConfig)
  }

  private def getSyncLocatorsUnsafe(peerBrokerInfo: BrokerGroupInfo): AVector[AVector[Hash]] = {
    val (groupFrom, groupUntil) = brokerConfig.calIntersection(peerBrokerInfo)
    AVector.tabulate((groupUntil - groupFrom) * groups) { index =>
      val offset    = index / groups
      val fromGroup = groupFrom + offset
      val toGroup   = index % groups
      getSyncLocatorsUnsafe(ChainIndex.unsafe(fromGroup, toGroup))
    }
  }

  private def getSyncLocatorsUnsafe(chainIndex: ChainIndex): AVector[Hash] = {
    if (brokerConfig.contains(chainIndex.from)) {
      val chain = getHeaderChain(chainIndex)
      HistoryLocators
        .sampleHeights(ALF.GenesisHeight, chain.maxHeightUnsafe)
        .map(height => Utils.unsafe(chain.getHashes(height).map(_.head)))
    } else AVector.empty
  }

  override protected def getSyncInventoriesUnsafe(
      locators: AVector[AVector[Hash]]): AVector[AVector[Hash]] = {
    locators.map { locatorsPerChain =>
      if (locatorsPerChain.isEmpty) AVector.empty[Hash]
      else {
        val chainIndex = ChainIndex.from(locatorsPerChain.head)
        val chain      = getBlockChain(chainIndex)
        chain.getSyncDataUnsafe(locatorsPerChain)
      }
    }
  }

  override protected def getIntraSyncInventoriesUnsafe(
      remoteBroker: BrokerGroupInfo): AVector[AVector[Hash]] = {
    AVector.tabulate(brokerConfig.groupNumPerBroker * remoteBroker.groupNumPerBroker) { index =>
      val k         = index / remoteBroker.groupNumPerBroker
      val l         = index % remoteBroker.groupNumPerBroker
      val fromGroup = brokerConfig.groupFrom + k
      val toGroup   = remoteBroker.groupFrom + l
      val chain     = getBlockChain(GroupIndex.unsafe(fromGroup), GroupIndex.unsafe(toGroup))
      chain.getLatestHashesUnsafe()
    }
  }

  def isRecent(data: FlowData): IOResult[Boolean] = {
    if (data.timestamp > TimeStamp.now().minusUnsafe(consensusConfig.recentBlockTimestampDiff)) {
      Right(true)
    } else {
      val hashChain = getHashChain(data.hash)
      hashChain.isRecent(data.hash)
    }
  }
}

object BlockFlow extends StrictLogging {
  type TrieUpdater = (WorldState, Block) => IOResult[WorldState]

  def fromGenesisUnsafe(config: AlephiumConfig, storages: Storages): BlockFlow = {
    fromGenesisUnsafe(storages, config.genesisBlocks)(config.broker,
                                                      config.consensus,
                                                      config.mempool)
  }

  def fromGenesisUnsafe(storages: Storages, genesisBlocks: AVector[AVector[Block]])(
      implicit brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting,
      memPoolSetting: MemPoolSetting): BlockFlow = {
    logger.info(s"Initialize storage for BlockFlow")
    new BlockFlowImpl(
      genesisBlocks,
      BlockChainWithState.fromGenesisUnsafe(storages),
      BlockChain.fromGenesisUnsafe(storages),
      BlockHeaderChain.fromGenesisUnsafe(storages)
    )
  }

  def fromStorageUnsafe(config: AlephiumConfig, storages: Storages): BlockFlow = {
    fromStorageUnsafe(storages, config.genesisBlocks)(config.broker,
                                                      config.consensus,
                                                      config.mempool)
  }

  def fromStorageUnsafe(storages: Storages, genesisBlocks: AVector[AVector[Block]])(
      implicit brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting,
      memPoolSetting: MemPoolSetting): BlockFlow = {
    val blockflow = new BlockFlowImpl(
      genesisBlocks,
      BlockChainWithState.fromStorageUnsafe(storages),
      BlockChain.fromStorageUnsafe(storages),
      BlockHeaderChain.fromStorageUnsafe(storages)
    )
    logger.info(s"Load BlockFlow from storage: #${blockflow.numHashes} blocks/headers")
    blockflow.updateBestDepsUnsafe()
    blockflow
  }

  class BlockFlowImpl(
      val genesisBlocks: AVector[AVector[Block]],
      val blockchainWithStateBuilder: (Block, BlockFlow.TrieUpdater) => BlockChainWithState,
      val blockchainBuilder: Block                                   => BlockChain,
      val blockheaderChainBuilder: BlockHeader                       => BlockHeaderChain
  )(implicit val brokerConfig: BrokerConfig,
    val consensusConfig: ConsensusSetting,
    val mempoolSetting: MemPoolSetting)
      extends BlockFlow {
    def add(block: Block): IOResult[Unit] = {
      val index = block.chainIndex
      assume(index.relateTo(brokerConfig))

      cacheBlock(block)
      for {
        weight <- calWeight(block)
        _      <- getBlockChain(index).add(block, weight)
        _      <- updateBestDeps()
      } yield ()
    }

    def add(header: BlockHeader): IOResult[Unit] = {
      val index = header.chainIndex
      assume(!index.relateTo(brokerConfig))

      for {
        weight <- calWeight(header)
        _      <- getHeaderChain(index).add(header, weight)
        _      <- updateBestDeps()
      } yield ()
    }

    private def calWeight(block: Block): IOResult[BigInt] = {
      calWeight(block.header)
    }

    private def calWeight(header: BlockHeader): IOResult[BigInt] = {
      IOUtils.tryExecute(calWeightUnsafe(header))
    }

    private def calWeightUnsafe(header: BlockHeader): BigInt = {
      if (header.isGenesis) ALF.GenesisWeight
      else {
        val weight1 = header.inDeps.sumBy(calGroupWeightUnsafe)
        val weight2 = header.outDeps.sumBy(getChainWeightUnsafe)
        weight1 + weight2 + header.target
      }
    }

    private def calGroupWeightUnsafe(hash: Hash): BigInt = {
      val header = getBlockHeaderUnsafe(hash)
      if (header.isGenesis) ALF.GenesisWeight
      else {
        header.outDeps.sumBy(getChainWeightUnsafe) + header.target
      }
    }

    def getBestTipUnsafe: Hash = {
      val ordering = Ordering.BigInt.on[Hash](getWeightUnsafe)
      aggregateHash(_.getBestTipUnsafe)(ordering.max)
    }

    override def getAllTips: AVector[Hash] = {
      aggregateHash(_.getAllTips)(_ ++ _)
    }

    // Rtips means tip representatives for all groups
    private def getRtipsUnsafe(tip: Hash, from: GroupIndex): Array[Hash] = {
      val rdeps = new Array[Hash](groups)
      rdeps(from.value) = tip

      val header = getBlockHeaderUnsafe(tip)
      val deps   = header.blockDeps
      if (header.isGenesis) {
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

    private def isExtendingUnsafe(current: Hash, previous: Hash): Boolean = {
      val index1 = ChainIndex.from(current)
      val index2 = ChainIndex.from(previous)
      assume(index1.from == index2.from)

      val chain = getHashChain(index2)
      if (index1.to == index2.to) Utils.unsafe(chain.isBefore(previous, current))
      else {
        val groupDeps = getGroupDepsUnsafe(current, index1.from)
        Utils.unsafe(chain.isBefore(previous, groupDeps(index2.to.value)))
      }
    }

    private def isCompatibleUnsafe(rtips: Array[Hash], tip: Hash, from: GroupIndex): Boolean = {
      val newRtips = getRtipsUnsafe(tip, from)
      assume(rtips.length == newRtips.length)
      rtips.indices forall { k =>
        val t1 = rtips(k)
        val t2 = newRtips(k)
        isExtendingUnsafe(t1, t2) || isExtendingUnsafe(t2, t1)
      }
    }

    private def updateRtipsUnsafe(rtips: Array[Hash], tip: Hash, from: GroupIndex): Unit = {
      val newRtips = getRtipsUnsafe(tip, from)
      assume(rtips.length == newRtips.length)
      rtips.indices foreach { k =>
        val t1 = rtips(k)
        val t2 = newRtips(k)
        if (isExtendingUnsafe(t2, t1)) {
          rtips(k) = t2
        }
      }
    }

    private def getGroupDepsUnsafe(tip: Hash, from: GroupIndex): AVector[Hash] = {
      val header = getBlockHeaderUnsafe(tip)
      if (header.isGenesis) {
        genesisBlocks(from.value).map(_.hash)
      } else {
        header.outDeps
      }
    }

    def calBestDepsUnsafe(group: GroupIndex): BlockDeps = {
      val bestTip   = getBestTipUnsafe
      val bestIndex = ChainIndex.from(bestTip)
      val rtips     = getRtipsUnsafe(bestTip, bestIndex.from)
      val deps1 = (0 until groups)
        .filter(_ != group.value)
        .foldLeft(AVector.empty[Hash]) {
          case (deps, _k) =>
            val k = GroupIndex.unsafe(_k)
            if (k == bestIndex.from) deps :+ bestTip
            else {
              val toTries = (0 until groups).foldLeft(AVector.empty[Hash]) { (acc, _l) =>
                val l = GroupIndex.unsafe(_l)
                acc ++ getHashChain(k, l).getAllTips
              }
              val validTries = toTries.filter(tip => isCompatibleUnsafe(rtips, tip, k))
              if (validTries.isEmpty) deps :+ rtips(k.value)
              else {
                val bestTry = validTries.maxBy(getWeightUnsafe) // TODO: improve
                updateRtipsUnsafe(rtips, bestTry, k)
                deps :+ bestTry
              }
            }
        }
      val groupTip  = rtips(group.value)
      val groupDeps = getGroupDepsUnsafe(groupTip, group)
      val deps2 = (0 until groups)
        .foldLeft(deps1) {
          case (deps, _l) =>
            val l       = GroupIndex.unsafe(_l)
            val chain   = getHashChain(group, l)
            val toTries = chain.getAllTips
            val validTries =
              toTries.filter(tip => Utils.unsafe(chain.isBefore(groupDeps(l.value), tip)))
            if (validTries.isEmpty) deps :+ groupDeps(l.value)
            else {
              val bestTry = validTries.maxBy(getWeightUnsafe) // TODO: use better selection function
              deps :+ bestTry
            }
        }
      BlockDeps(deps2)
    }

    def updateBestDepsUnsafe(): Unit =
      brokerConfig.groupFrom until brokerConfig.groupUntil foreach { mainGroup =>
        val deps = calBestDepsUnsafe(GroupIndex.unsafe(mainGroup))
        updateMemPoolUnsafe(mainGroup, deps)
        updateBestDeps(mainGroup, deps)
      }

    def updateBestDeps(): IOResult[Unit] = {
      IOUtils.tryExecute(updateBestDepsUnsafe())
    }
  }
}
