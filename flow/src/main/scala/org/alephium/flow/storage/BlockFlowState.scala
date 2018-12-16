package org.alephium.flow.storage

import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.util.AVector

import scala.reflect.ClassTag

trait BlockFlowState {
  implicit def config: PlatformConfig

  def mainGroup: GroupIndex = config.mainGroup

  val groups = config.groups

  private val inBlockChains: AVector[BlockChain] = AVector.tabulate(groups - 1) { k =>
    BlockChain.fromGenesisUnsafe(
      config.genesisBlocks(if (k < mainGroup.value) k else k + 1)(mainGroup.value))
  }
  private val outBlockChains: AVector[BlockChain] = AVector.tabulate(groups) { to =>
    BlockChain.fromGenesisUnsafe(config.genesisBlocks(mainGroup.value)(to))
  }
  private val blockHeaderChains: AVector[AVector[BlockHeaderPool with BlockHashChain]] =
    AVector.tabulate(groups, groups) {
      case (from, to) =>
        if (from == mainGroup.value) outBlockChains(to)
        else if (to == mainGroup.value) {
          inBlockChains(if (from < mainGroup.value) from else from - 1)
        } else BlockHeaderChain.fromGenesisUnsafe(config.genesisBlocks(from)(to))
    }

  protected def aggregate[T: ClassTag](f: BlockHashPool => T)(op: (T, T) => T): T = {
    blockHeaderChains.reduceBy { chains =>
      chains.reduceBy(f)(op)
    }(op)
  }

  def numTransactions: Int = {
    inBlockChains.sumBy(_.numTransactions) + outBlockChains.sumBy(_.numTransactions)
  }

  protected def getBlockChain(from: GroupIndex, to: GroupIndex): BlockChain = {
    assert(from == mainGroup || to == mainGroup)
    if (from == mainGroup) outBlockChains(to.value)
    else inBlockChains(if (from.value < mainGroup.value) from.value else from.value - 1)
  }

  protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderPool = {
    blockHeaderChains(from.value)(to.value)
  }

  protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain = {
    blockHeaderChains(from.value)(to.value)
  }
}
