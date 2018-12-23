package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockDeps
import org.alephium.protocol.model._
import org.alephium.util.{AVector, ConcurrentHashMap, ConcurrentHashSet}

import scala.reflect.ClassTag

trait BlockFlowState {
  implicit def config: PlatformConfig

  def mainGroup: GroupIndex = config.mainGroup

  val groups = config.groups

  private var bestDeps = {
    val deps1 = AVector.tabulate(groups - 1) { i =>
      if (i < mainGroup.value) config.genesisBlocks(i).head.hash
      else config.genesisBlocks(i + 1).head.hash
    }
    val deps2 = config.genesisBlocks(mainGroup.value).map(_.hash)
    BlockDeps(deps1 ++ deps2)
  }

  private val transactions = ConcurrentHashMap.empty[Keccak256, Transaction]
  private val utxos        = ConcurrentHashSet.empty[TxInput]

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

  def getBestDeps: BlockDeps = bestDeps

  def updateBestDeps(deps: BlockDeps): Unit = bestDeps = deps

  def updateTxs(block: Block): Unit = {
    block.transactions.foreach { tx =>
      transactions.put(tx.hash, tx)
      tx.unsigned.inputs.foreach(utxos.remove)
      tx.unsigned.outputs.foreachWithIndex { (output, i) =>
        val txInput = TxInput(tx.hash, i)
        utxos.add(txInput)
      }
    }
  }

  def numUTXOs: Int = utxos.size
}
