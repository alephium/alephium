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
import org.alephium.flow.setting.ConsensusSettings
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.{ALPH}
import org.alephium.protocol.config.{BrokerConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, WorldState}
import org.alephium.serde.Serde
import org.alephium.util.{AVector, TimeStamp}

// scalastyle:off number.of.methods

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

  def getUsedUnclesAndAncestorsUnsafe(
      header: BlockHeader
  ): (AVector[BlockHash], AVector[BlockHash]) = {
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def iter(
        fromHash: BlockHash,
        num: Int,
        unclesAcc: AVector[BlockHash],
        ancestorsAcc: AVector[BlockHash]
    ): (AVector[BlockHash], AVector[BlockHash]) = {
      if (num == 0) {
        (unclesAcc, ancestorsAcc)
      } else {
        val block = getBlockUnsafe(fromHash)
        if (block.isGenesis) {
          (unclesAcc, ancestorsAcc)
        } else {
          val parentHash = block.parentHash
          val uncles = block.uncleHashes match {
            case Right(hashes) => hashes
            case Left(error)   => throw error
          }
          iter(parentHash, num - 1, unclesAcc ++ uncles, ancestorsAcc :+ parentHash)
        }
      }
    }
    iter(header.hash, ALPH.MaxUncleAge, AVector.empty, AVector.empty)
  }

  def getUsedUnclesAndAncestors(
      header: BlockHeader
  ): IOResult[(AVector[BlockHash], AVector[BlockHash])] = {
    IOUtils.tryExecute(getUsedUnclesAndAncestorsUnsafe(header))
  }

  def selectUnclesUnsafe(
      header: BlockHeader,
      validator: BlockHeader => Boolean
  ): AVector[(BlockHash, LockupScript.Asset)] = {
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def iter(
        fromHeader: BlockHeader,
        num: Int,
        usedUncles: AVector[BlockHash],
        ancestors: AVector[BlockHash],
        unclesAcc: AVector[(BlockHash, LockupScript.Asset)]
    ): AVector[(BlockHash, LockupScript.Asset)] = {
      if (fromHeader.isGenesis || num == 0 || unclesAcc.length >= ALPH.MaxUncleSize) {
        unclesAcc
      } else {
        val height      = getHeightUnsafe(fromHeader.hash)
        val uncleHashes = getHashesUnsafe(height).filter(_ != fromHeader.hash)
        val uncleBlocks = uncleHashes.map(getBlockUnsafe)
        val selected = uncleBlocks
          .filter(uncle =>
            !usedUncles.contains(uncle.hash) &&
              ancestors.exists(_ == uncle.parentHash) &&
              validator(uncle.header)
          )
          .map(block => (block.hash, block.coinbase.unsigned.fixedOutputs(0).lockupScript))
        val parentHeader = getBlockHeaderUnsafe(fromHeader.parentHash)
        iter(parentHeader, num - 1, usedUncles, ancestors, unclesAcc ++ selected)
      }
    }

    val (usedUncles, ancestors) = getUsedUnclesAndAncestorsUnsafe(header)
    val availableUncles = iter(header, ALPH.MaxUncleAge, usedUncles, ancestors, AVector.empty)
    if (availableUncles.length <= ALPH.MaxUncleSize) {
      availableUncles
    } else {
      availableUncles.take(ALPH.MaxUncleSize)
    }
  }

  def selectUncles(
      header: BlockHeader,
      validator: BlockHeader => Boolean
  ): IOResult[AVector[(BlockHash, LockupScript.Asset)]] = {
    IOUtils.tryExecute(selectUnclesUnsafe(header, validator))
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
  ): IOResult[(ChainIndex, AVector[(Block, Int)])] =
    for {
      height <- maxHeight
      result <- searchByTimestampHeight(height, fromTs, toTs)
    } yield (chainIndex, result)

  // Binary search until we found an height containing blocks within time range
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
          if (block.timestamp > toTs) { // search lower
            locateTimeRangedHeight(fromTs, toTs, low, middle - 1)
          } else if (block.timestamp >= fromTs) { // got you
            Right(Some((middle, block)))
          } else { // search higher
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

  def getTransaction(txId: TransactionId): IOResult[Option[Transaction]] = {
    IOUtils.tryExecute(getCanonicalTxIndex(txId)).flatMap {
      case Some(index) => getBlock(index.hash).map(block => Some(block.transactions(index.index)))
      case None        => Right(None)
    }
  }

  def isTxConfirmed(txId: TransactionId): IOResult[Boolean] = txStorage.exists(txId)

  private def getCanonicalTxIndex(txId: TransactionId): Option[TxIndex] = {
    txStorage.getOptUnsafe(txId).flatMap { txIndexes =>
      val canonicalIndex = txIndexes.indexes.filter(index => isCanonicalUnsafe(index.hash))
      if (canonicalIndex.nonEmpty) {
        Some(canonicalIndex.head)
      } else {
        None
      }
    }
  }

  def getTxStatus(txId: TransactionId): IOResult[Option[TxStatus]] =
    IOUtils.tryExecute(getTxStatusUnsafe(txId))

  def getTxStatusUnsafe(txId: TransactionId): Option[TxStatus] = {
    getCanonicalTxIndex(txId).flatMap { selectedIndex =>
      val selectedHeight     = getHeightUnsafe(selectedIndex.hash)
      val maxHeight          = maxHeightUnsafe
      val chainConfirmations = maxHeight - selectedHeight + 1
      Some(TxStatus(selectedIndex, chainConfirmations))
    }
  }

  def calBlockDiffUnsafe(index: ChainIndex, newTip: BlockHash, oldTip: BlockHash): ChainDiff = {
    val hashDiff = Utils.unsafe(calHashDiff(newTip, oldTip))
    ChainDiff(index, hashDiff.toRemove.map(getBlockUnsafe), hashDiff.toAdd.map(getBlockUnsafe))
  }

  def getLatestHashesUnsafe(): AVector[BlockHash] = {
    val toHeight   = maxHeightUnsafe
    val fromHeight = math.max(ALPH.GenesisHeight + 1, toHeight - 20)
    (fromHeight to toHeight).foldLeft(AVector.empty[BlockHash]) { case (acc, height) =>
      acc ++ Utils.unsafe(getHashes(height))
    }
  }

  override def checkCompletenessUnsafe(hash: BlockHash): Boolean = {
    checkCompletenessHelper(hash, blockStorage.existsUnsafe, super.checkCompletenessUnsafe)
  }
}

object BlockChain {
  def fromGenesisUnsafe(storages: Storages)(
      genesisBlock: Block
  )(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusSettings: ConsensusSettings
  ): BlockChain = {
    val initialize = initializeGenesis(genesisBlock)(_)
    createUnsafe(genesisBlock, storages, initialize)
  }

  def fromStorageUnsafe(storages: Storages)(
      genesisBlock: Block
  )(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusSettings: ConsensusSettings
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
      _consensusSettings: ConsensusSettings
  ): BlockChain = {
    val blockchain: BlockChain = new BlockChain {
      override val brokerConfig      = _brokerConfig
      override val networkConfig     = _networkConfig
      override val consensusConfigs  = _consensusSettings
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

  final case class ChainDiff(
      chainIndex: ChainIndex,
      toRemove: AVector[Block],
      toAdd: AVector[Block]
  )

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
