package org.alephium.flow.storage

import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.IOResult
import org.alephium.flow.model.BlockDeps
import org.alephium.protocol.model._
import org.alephium.util.{AVector, ConcurrentHashSet}

import scala.reflect.ClassTag

trait BlockFlowState {
  implicit def config: PlatformConfig

  val groups = config.groups

  private val bestDeps = Array.tabulate(config.groupNumPerBroker) { fromShift =>
    val mainGroup = config.groupFrom + fromShift
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
      val mainGroup = config.groupFrom + toShift
      val fromIndex = if (k < config.groupFrom) k else k + config.groupFrom
      BlockChain.fromGenesisUnsafe(config.genesisBlocks(fromIndex)(mainGroup))
    }
  private val outBlockChains: AVector[AVector[BlockChain]] =
    AVector.tabulate(config.groupNumPerBroker, groups) { (fromShift, to) =>
      val mainGroup = config.groupFrom + fromShift
      BlockChain.fromGenesisUnsafe(config.genesisBlocks(mainGroup)(to))
    }
  private val blockHeaderChains: AVector[AVector[BlockHeaderPool with BlockHashChain]] =
    AVector.tabulate(groups, groups) {
      case (from, to) =>
        if (config.brokerId.containsRaw(from)) {
          val fromShift = from - config.groupFrom
          outBlockChains(fromShift)(to)
        } else if (config.brokerId.containsRaw(to)) {
          val toShift   = to - config.groupFrom
          val fromIndex = if (from < config.groupFrom) from else from + config.groupNumPerBroker
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
    assert(config.brokerId.contains(from) || config.brokerId.contains(to))
    if (config.brokerId.contains(from)) outBlockChains(from.value - config.groupFrom)(to.value)
    else {
      val fromIndex =
        if (from.value < config.groupFrom) from.value
        else from.value - config.groupNumPerBroker
      inBlockChains(to.value)(fromIndex)
    }
  }

  protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderPool = {
    blockHeaderChains(from.value)(to.value)
  }

  protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain = {
    blockHeaderChains(from.value)(to.value)
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps = {
    val groupShift = groupIndex.value - config.groupFrom
    bestDeps(groupShift)
  }

  def updateBestDeps(mainGroup: Int, deps: BlockDeps): Unit = {
    assert(config.brokerId.containsRaw(mainGroup))
    val groupShift = mainGroup - config.groupFrom
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
