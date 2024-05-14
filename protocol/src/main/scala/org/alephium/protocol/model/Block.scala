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

package org.alephium.protocol.model

import scala.annotation.tailrec
import scala.collection.IndexedSeqView
import scala.collection.immutable.ArraySeq
import scala.collection.mutable.ArrayBuffer

import org.alephium.crypto.MerkleHashable
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{ConsensusConfig, GroupConfig, NetworkConfig}
import org.alephium.protocol.model.BlockHash
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.{deserialize, Serde, SerdeResult}
import org.alephium.util.{AVector, TimeStamp, U256}

final case class Block(header: BlockHeader, transactions: AVector[Transaction])
    extends FlowData
    with SerializationCache {

  def hash: BlockHash = header.hash

  def chainIndex: ChainIndex = header.chainIndex

  def coinbase: Transaction = transactions.last

  def minerLockupScript: LockupScript.Asset = coinbase.unsigned.fixedOutputs(0).lockupScript

  private[model] var _ghostUncleData: Option[AVector[GhostUncleData]] = None
  def ghostUncleData(implicit
      networkConfig: NetworkConfig
  ): SerdeResult[AVector[GhostUncleData]] = {
    _ghostUncleData match {
      case Some(data) => Right(data)
      case None =>
        deserialize[CoinbaseData](coinbase.unsigned.fixedOutputs.head.additionalData).map {
          case v2: CoinbaseDataV2 =>
            _ghostUncleData = Some(v2.ghostUncleData)
            v2.ghostUncleData
          case _: CoinbaseDataV1 =>
            val data = AVector.empty[GhostUncleData]
            _ghostUncleData = Some(data)
            data
        }
    }
  }
  @inline def ghostUncleHashes(implicit
      networkConfig: NetworkConfig
  ): SerdeResult[AVector[BlockHash]] = {
    ghostUncleData.map(_.map(_.blockHash))
  }

  def coinbaseReward: U256 = coinbase.unsigned.fixedOutputs.head.amount

  // only use this after validation
  def gasFee: U256 = nonCoinbase.fold(U256.Zero)(_ addUnsafe _.gasFeeUnsafe)

  def nonCoinbase: AVector[Transaction] = transactions.init

  def nonCoinbaseLength: Int = transactions.length - 1

  override def timestamp: TimeStamp = header.timestamp

  override def target: Target = header.target

  def isGenesis: Boolean = header.isGenesis

  def blockDeps: BlockDeps = header.blockDeps

  def parentHash: BlockHash = {
    header.parentHash
  }

  def uncleHash(toIndex: GroupIndex): BlockHash = {
    header.uncleHash(toIndex)
  }

  def getScriptExecutionOrder: AVector[Int] = {
    if (isGenesis) {
      AVector.empty
    } else {
      Block.getScriptExecutionOrder(parentHash, nonCoinbase)
    }
  }

  def getNonCoinbaseExecutionOrder: AVector[Int] = {
    assume(!isGenesis)
    Block.getNonCoinbaseExecutionOrder(parentHash, nonCoinbase)
  }

  def `type`: String = "Block"
}

object Block {
  private val _serde: Serde[Block] = Serde.forProduct2(apply, b => (b.header, b.transactions))
  implicit val serde: Serde[Block] = SerializationCache.cachedSerde(_serde)

  def calTxsHash(transactions: AVector[Transaction]): Hash =
    MerkleHashable.rootHash(Hash, transactions)

  def from(
      deps: AVector[BlockHash],
      depStateHash: Hash,
      transactions: AVector[Transaction],
      target: Target,
      timeStamp: TimeStamp,
      nonce: Nonce
  )(implicit config: GroupConfig): Block = {
    val txsHash     = calTxsHash(transactions)
    val blockDeps   = BlockDeps.build(deps)
    val blockHeader = BlockHeader.unsafe(blockDeps, depStateHash, txsHash, timeStamp, target, nonce)
    Block(blockHeader, transactions)
  }

  def genesis(chainIndex: ChainIndex, transactions: AVector[Transaction])(implicit
      groupConfig: GroupConfig,
      consensusConfig: ConsensusConfig
  ): Block = {
    val txsHash = calTxsHash(transactions)
    Block(BlockHeader.genesis(chainIndex, txsHash), transactions)
  }

  def scriptIndexes[T <: TransactionAbstract](nonCoinbase: AVector[T]): ArrayBuffer[Int] = {
    val indexes = ArrayBuffer.empty[Int]
    nonCoinbase.foreachWithIndex { case (tx, txIndex) =>
      if (tx.unsigned.scriptOpt.nonEmpty) {
        indexes.addOne(txIndex)
      }
    }
    indexes
  }

  // we shuffle tx scripts randomly for execution to mitigate front-running
  def getScriptExecutionOrder[T <: TransactionAbstract](
      parentHash: BlockHash,
      nonCoinbase: AVector[T]
  ): AVector[Int] = {
    val nonCoinbaseLength = nonCoinbase.length
    val scriptOrders      = scriptIndexes(nonCoinbase)

    @tailrec
    def shuffle(index: Int, seed: Hash): Unit = {
      if (index < scriptOrders.length - 1) {
        val txRemaining = scriptOrders.length - index
        val randomIndex = index + Math.floorMod(seed.toRandomIntUnsafe, txRemaining)
        val tmp         = scriptOrders(index)
        scriptOrders(index) = scriptOrders(randomIndex)
        scriptOrders(randomIndex) = tmp
        shuffle(index + 1, nonCoinbase(randomIndex).id.value)
      }
    }

    if (scriptOrders.length > 1) {
      val initialSeed = {
        val maxIndex = nonCoinbaseLength - 1
        val samples  = ArraySeq(0, maxIndex / 2, maxIndex)
        samples.foldLeft(Hash.unsafe(parentHash.bytes)) { case (acc, index) =>
          val tx = nonCoinbase(index)
          Hash.xor(acc, tx.id.value)
        }
      }
      shuffle(0, initialSeed)
    }
    AVector.from(scriptOrders)
  }

  def getScriptExecutionOrder[T <: TransactionAbstract](
      parentHash: BlockHash,
      nonCoinbase: AVector[T],
      hardFork: HardFork
  ): AVector[Int] = {
    if (hardFork.isLemanEnabled()) {
      AVector.from(scriptIndexes(nonCoinbase))
    } else {
      getScriptExecutionOrder(parentHash, nonCoinbase)
    }
  }

  def getNonCoinbaseExecutionOrder[T <: TransactionAbstract](
      parentHash: BlockHash,
      nonCoinbase: AVector[T]
  ): AVector[Int] = {
    getScriptExecutionOrder(parentHash, nonCoinbase) ++ nonCoinbase.foldWithIndex(
      AVector.empty[Int]
    ) { case (acc, tx, index) =>
      if (tx.unsigned.scriptOpt.isEmpty) acc :+ index else acc
    }
  }

  def getNonCoinbaseExecutionOrder[T <: TransactionAbstract](
      parentHash: BlockHash,
      nonCoinbase: AVector[T],
      hardFork: HardFork
  ): IndexedSeqView[Int] = {
    if (hardFork.isLemanEnabled()) {
      nonCoinbase.indices.view
    } else {
      getNonCoinbaseExecutionOrder(parentHash, nonCoinbase).view
    }
  }
}
