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

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockChain.{ChainDiff, TxIndex, TxStatus}
import org.alephium.flow.io._
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.{ALPH, BlockHash, Hash}
import org.alephium.protocol.config.{BrokerConfig, NetworkConfig}
import org.alephium.protocol.model.{Block, Weight}
import org.alephium.protocol.vm.WorldState
import org.alephium.serde.Serde
import org.alephium.util.{AVector, TimeStamp}

trait BlockChain extends BlockPool with BlockHeaderChain with BlockHashChain {
  def blockStorage: BlockStorage
  def txStorage: TxStorage

  def validateBlockHeight(block: Block, maxForkDepth: Int): IOResult[Boolean] = {
    for {
      tipHeight    <- maxHeight
      parentHeight <- getHeight(block.parentHash)
    } yield {
      val blockHeight = parentHeight + 1
      (blockHeight + maxForkDepth) >= tipHeight
    }
  }

  def getBlock(hash: BlockHash): IOResult[Block] = {
    blockStorage.get(hash)
  }

  def getBlockUnsafe(hash: BlockHash): Block = {
    blockStorage.getUnsafe(hash)
  }

  def getMainChainBlockByHeight(height: Int): IOResult[Option[Block]] = {
    getHashes(height).flatMap { hashes =>
      hashes.headOption match {
        case Some(hash) => getBlock(hash).map(Some(_))
        case None       => Right(None)
      }
    }
  }

  def getHeightedBlocks(
      fromTs: TimeStamp,
      toTs: TimeStamp
  ): IOResult[AVector[(Block, Int)]] =
    for {
      height <- maxHeight
      result <- searchByTimestampHeight(height, fromTs, toTs)
    } yield result

  //Binary search until we found an height containing blocks within time range
  @tailrec
  private def locateTimeRangedHeight(
      fromTs: TimeStamp,
      toTs: TimeStamp,
      low: Int,
      high: Int
  ): IOResult[Option[(Int, Block)]] = {
    if (low > high) {
      Right(None)
    } else {
      val middle = low + (high - low) / 2
      getMainChainBlockByHeight(middle) match {
        case Right(Some(block)) =>
          if (block.timestamp > toTs) { //search lower
            locateTimeRangedHeight(fromTs, toTs, low, middle - 1)
          } else if (block.timestamp >= fromTs) { //got you
            Right(Some((middle, block)))
          } else { //search higher
            locateTimeRangedHeight(fromTs, toTs, middle + 1, high)
          }
        case Right(None) =>
          locateTimeRangedHeight(fromTs, toTs, low, middle - 1)
        case Left(error) => Left(error)
      }
    }
  }

  private def searchByTimestampHeight(
      maxHeight: Int,
      fromTs: TimeStamp,
      toTs: TimeStamp
  ): IOResult[AVector[(Block, Int)]] = {
    locateTimeRangedHeight(fromTs, toTs, ALPH.GenesisHeight, maxHeight).flatMap {
      case None => Right(AVector.empty)
      case Some((height, init)) => {
        for {
          previous <- getLowerBlocks(ALPH.GenesisHeight, height - 1, fromTs, toTs)
          later    <- getUpperBlocks(maxHeight, height + 1, fromTs, toTs)
        } yield {
          (previous.reverse :+ ((init, height))) ++ later
        }
      }
    }
  }

  private def getTimeRangedBlocks(
      threshold: Int,
      done: (Int, Int) => Boolean,
      step: Int,
      initHeight: Int,
      fromTs: TimeStamp,
      toTs: TimeStamp
  ): IOResult[AVector[(Block, Int)]] = {
    @tailrec
    def rec(
        height: Int,
        prev: AVector[(Block, Int)]
    ): IOResult[AVector[(Block, Int)]] = {
      if (done(threshold, height)) {
        Right(prev)
      } else {
        getMainChainBlockByHeight(height) match {
          case Right(blockOpt) =>
            val filteredHeader =
              blockOpt.filter(block => block.timestamp >= fromTs && block.timestamp <= toTs)
            filteredHeader match {
              case None => Right(prev)
              case Some(block) =>
                val next = prev :+ ((block, height))
                rec(height + step, next)
            }
          case Left(error) => Left(error)
        }
      }
    }
    rec(initHeight, AVector.empty)
  }

  private def getUpperBlocks(
      maxHeight: Int,
      initHeight: Int,
      fromTs: TimeStamp,
      toTs: TimeStamp
  ): IOResult[AVector[(Block, Int)]] = {
    getTimeRangedBlocks(
      maxHeight,
      (threshold, height) => height > threshold,
      1,
      initHeight,
      fromTs,
      toTs
    )
  }

  private def getLowerBlocks(
      minHeight: Int,
      initHeight: Int,
      fromTs: TimeStamp,
      toTs: TimeStamp
  ): IOResult[AVector[(Block, Int)]] = {
    getTimeRangedBlocks(
      minHeight,
      (threshold, height) => height < threshold,
      -1,
      initHeight,
      fromTs,
      toTs
    )
  }

  def add(
      block: Block,
      weight: Weight
  ): IOResult[Unit] = {
    add(block, weight, None)
  }

  def add(
      block: Block,
      weight: Weight,
      worldStateOpt: Option[WorldState.Cached]
  ): IOResult[Unit] = {
    assume(worldStateOpt.isEmpty)
    assume {
      val assertion = for {
        isNewIncluded    <- contains(block.hash)
        isParentIncluded <- contains(block.parentHash)
      } yield !isNewIncluded && isParentIncluded
      assertion.getOrElse(false)
    }

    for {
      _ <- persistBlock(block)
      _ <- persistTxs(block)
      _ <- add(block.header, weight)
    } yield ()
  }

  protected def addGenesis(block: Block): IOResult[Unit] = {
    for {
      _ <- persistBlock(block)
      _ <- persistTxs(block)
      _ <- addGenesis(block.header)
    } yield ()
  }

  override protected def loadFromStorage(): IOResult[Unit] = {
    super.loadFromStorage()
  }

  protected def persistBlock(block: Block): IOResult[Unit] = {
    blockStorage.put(block)
  }

  protected def persistTxs(block: Block): IOResult[Unit] = {
    if (brokerConfig.contains(block.chainIndex.from)) {
      block.transactions.foreachWithIndexE { case (tx, index) =>
        txStorage.add(tx.id, TxIndex(block.hash, index))
      }
    } else {
      Right(())
    }
  }

  def isTxConfirmed(txId: Hash): IOResult[Boolean] = txStorage.exists(txId)

  def getTxStatus(txId: Hash): IOResult[Option[TxStatus]] =
    IOUtils.tryExecute(getTxStatusUnsafe(txId))

  def getTxStatusUnsafe(txId: Hash): Option[TxStatus] = {
    txStorage.getOptUnsafe(txId).flatMap { txIndexes =>
      val canonicalIndex = txIndexes.indexes.filter(index => isCanonicalUnsafe(index.hash))
      if (canonicalIndex.nonEmpty) {
        val selectedIndex      = canonicalIndex.head
        val selectedHeight     = getHeightUnsafe(selectedIndex.hash)
        val maxHeight          = maxHeightUnsafe
        val chainConfirmations = maxHeight - selectedHeight + 1
        Some(TxStatus(selectedIndex, chainConfirmations))
      } else {
        None
      }
    }
  }

  def calBlockDiffUnsafe(newTip: BlockHash, oldTip: BlockHash): ChainDiff = {
    val hashDiff = Utils.unsafe(calHashDiff(newTip, oldTip))
    ChainDiff(hashDiff.toRemove.map(getBlockUnsafe), hashDiff.toAdd.map(getBlockUnsafe))
  }

  def getLatestHashesUnsafe(): AVector[BlockHash] = {
    val toHeight   = maxHeightUnsafe
    val fromHeight = math.max(ALPH.GenesisHeight + 1, toHeight - 20)
    (fromHeight to toHeight).foldLeft(AVector.empty[BlockHash]) { case (acc, height) =>
      acc ++ Utils.unsafe(getHashes(height))
    }
  }
}

object BlockChain {
  def fromGenesisUnsafe(storages: Storages)(
      genesisBlock: Block
  )(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusSetting: ConsensusSetting
  ): BlockChain = {
    val initialize = initializeGenesis(genesisBlock)(_)
    createUnsafe(genesisBlock, storages, initialize)
  }

  def fromStorageUnsafe(storages: Storages)(
      genesisBlock: Block
  )(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusSetting: ConsensusSetting
  ): BlockChain = {
    createUnsafe(genesisBlock, storages, initializeFromStorage)
  }

  def createUnsafe(
      rootBlock: Block,
      storages: Storages,
      initialize: BlockChain => IOResult[Unit]
  )(implicit
      _brokerConfig: BrokerConfig,
      _networkConfig: NetworkConfig,
      _consensusSetting: ConsensusSetting
  ): BlockChain = {
    val blockchain: BlockChain = new BlockChain {
      override val brokerConfig      = _brokerConfig
      override val networkConfig     = _networkConfig
      override val consensusConfig   = _consensusSetting
      override val blockStorage      = storages.blockStorage
      override val txStorage         = storages.txStorage
      override val headerStorage     = storages.headerStorage
      override val blockStateStorage = storages.blockStateStorage
      override val heightIndexStorage =
        storages.nodeStateStorage.heightIndexStorage(rootBlock.chainIndex)
      override val chainStateStorage =
        storages.nodeStateStorage.chainStateStorage(rootBlock.chainIndex)
      override val genesisHash: BlockHash = rootBlock.hash
    }

    Utils.unsafe(initialize(blockchain))
    blockchain
  }

  def initializeGenesis(genesisBlock: Block)(chain: BlockChain): IOResult[Unit] = {
    chain.addGenesis(genesisBlock)
  }

  def initializeFromStorage(chain: BlockChain): IOResult[Unit] = {
    chain.loadFromStorage()
  }

  final case class ChainDiff(toRemove: AVector[Block], toAdd: AVector[Block])

  final case class TxIndex(hash: BlockHash, index: Int)
  object TxIndex {
    implicit val serde: Serde[TxIndex] = Serde.forProduct2(TxIndex.apply, t => (t.hash, t.index))
  }

  final case class TxIndexes(indexes: AVector[TxIndex])
  object TxIndexes {
    implicit val serde: Serde[TxIndexes] = Serde.forProduct1(TxIndexes.apply, t => t.indexes)
  }

  final case class TxStatus(index: TxIndex, confirmations: Int)
}
