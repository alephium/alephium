package org.alephium.flow.storage

import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.IOResult
import org.alephium.flow.model.BlockDeps
import org.alephium.protocol.model._
import org.alephium.util.{AVector, ConcurrentHashSet}

import scala.reflect.ClassTag

trait BlockFlowState {
  implicit def config: PlatformConfig

  def brokerInfo: BrokerInfo

  val groups = config.groups

  private val bestDeps = Array.tabulate(config.groupNumPerBroker) { fromShift =>
    val mainGroup = brokerInfo.groupFrom + fromShift
    val deps1 = AVector.tabulate(groups - 1) { i =>
      if (i < mainGroup) config.genesisBlocks(i).head.hash
      else config.genesisBlocks(i + 1).head.hash
    }
    val deps2 = config.genesisBlocks(mainGroup).map(_.hash)
    BlockDeps(deps1 ++ deps2)
  }

  private val utxos = ConcurrentHashSet.empty[TxOutputPoint]

  private val inBlockChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(config.groupNumPerBroker, groups - config.groupNumPerBroker) { (toShift, k) =>
      val mainGroup = brokerInfo.groupFrom + toShift
      val fromIndex = if (k < brokerInfo.groupFrom) k else k + config.groupNumPerBroker
      BlockChain.fromGenesisUnsafe(config.genesisBlocks(fromIndex)(mainGroup))
    }
  private val outBlockChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(config.groupNumPerBroker, groups) { (fromShift, to) =>
      val mainGroup = brokerInfo.groupFrom + fromShift
      BlockChain.fromGenesisUnsafe(config.genesisBlocks(mainGroup)(to))
    }
  private val blockHeaderChains: AVector[AVector[BlockHeaderPool with BlockHashChain]] =
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

  protected def aggregate[T: ClassTag](f: BlockHashPool => T)(op: (T, T) => T): T = {
    blockHeaderChains.reduceBy { chains =>
      chains.reduceBy(f)(op)
    }(op)
  }

  def numTransactions: Int = {
    inBlockChains.sumBy(_.sumBy(_.numTransactions)) +
      outBlockChains.sumBy(_.sumBy(_.numTransactions))
  }

  protected def getBlockChain(from: GroupIndex, to: GroupIndex): BlockChain = {
    assert(brokerInfo.contains(from) || brokerInfo.contains(to))
    if (brokerInfo.contains(from))
      outBlockChains(from.value - brokerInfo.groupFrom)(to.value)
    else {
      val fromIndex =
        if (from.value < brokerInfo.groupFrom) from.value
        else from.value - config.groupNumPerBroker
      val toShift = to.value - brokerInfo.groupFrom
      inBlockChains(toShift)(fromIndex)
    }
  }

  protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderPool = {
    blockHeaderChains(from.value)(to.value)
  }

  protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain = {
    blockHeaderChains(from.value)(to.value)
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps = {
    val groupShift = groupIndex.value - brokerInfo.groupFrom
    bestDeps(groupShift)
  }

  def updateBestDeps(mainGroup: Int, deps: BlockDeps): Unit = {
    assert(brokerInfo.containsRaw(mainGroup))
    val groupShift = mainGroup - brokerInfo.groupFrom
    bestDeps(groupShift) = deps
  }

  // TODO: bulk update & atomic update
  def updateTxs(block: Block): IOResult[Unit] = {
    block.transactions.foreachF { tx =>
      for {
        _ <- tx.unsigned.inputs.foreachF(input => config.trie.remove(input))
        _ <- tx.unsigned.outputs.foreachWithIndexF { (output, i) =>
          val outputPoint = TxOutputPoint(tx.hash, i)
          config.trie.put(outputPoint, output)
        }
      } yield ()
    }
  }

  def numUTXOs: Int = utxos.size
}
