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

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockChain.ChainDiff
import org.alephium.flow.io._
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.IOResult
import org.alephium.protocol.{ALF, BlockHash}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.Block
import org.alephium.util.AVector

trait BlockChain extends BlockPool with BlockHeaderChain with BlockHashChain {
  def blockStorage: BlockStorage

  def getBlock(hash: BlockHash): IOResult[Block] = {
    blockStorage.get(hash)
  }

  def getBlockUnsafe(hash: BlockHash): Block = {
    blockStorage.getUnsafe(hash)
  }

  def add(block: Block, weight: BigInt): IOResult[Unit] = {
    assume {
      val assertion = for {
        isNewIncluded    <- contains(block.hash)
        isParentIncluded <- contains(block.parentHash)
      } yield !isNewIncluded && isParentIncluded
      assertion.getOrElse(false)
    }

    for {
      _ <- persistBlock(block)
      _ <- add(block.header, weight)
    } yield ()
  }

  protected def addGenesis(block: Block): IOResult[Unit] = {
    for {
      _ <- persistBlock(block)
      _ <- addGenesis(block.header)
    } yield ()
  }

  override protected def loadFromStorage(): IOResult[Unit] = {
    super.loadFromStorage()
  }

  protected def persistBlock(block: Block): IOResult[Unit] = {
    blockStorage.put(block)
  }

  protected def persistBlockUnsafe(block: Block): Unit = {
    blockStorage.putUnsafe(block)
    ()
  }

  def calBlockDiffUnsafe(newTip: BlockHash, oldTip: BlockHash): ChainDiff = {
    val hashDiff = Utils.unsafe(calHashDiff(newTip, oldTip))
    ChainDiff(hashDiff.toRemove.map(getBlockUnsafe), hashDiff.toAdd.map(getBlockUnsafe))
  }

  def getLatestHashesUnsafe(): AVector[BlockHash] = {
    val toHeight   = maxHeightUnsafe
    val fromHeight = math.max(ALF.GenesisHeight + 1, toHeight - 100)
    (fromHeight to toHeight).foldLeft(AVector.empty[BlockHash]) {
      case (acc, height) => acc ++ Utils.unsafe(getHashes(height))
    }
  }
}

object BlockChain {
  def fromGenesisUnsafe(storages: Storages)(genesisBlock: Block)(
      implicit brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting): BlockChain = {
    val initialize = initializeGenesis(genesisBlock)(_)
    createUnsafe(genesisBlock, storages, initialize)
  }

  def fromStorageUnsafe(storages: Storages)(genesisBlock: Block)(
      implicit brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting): BlockChain = {
    createUnsafe(genesisBlock, storages, initializeFromStorage)
  }

  def createUnsafe(
      rootBlock: Block,
      storages: Storages,
      initialize: BlockChain => IOResult[Unit]
  )(implicit _brokerConfig: BrokerConfig, _consensusSetting: ConsensusSetting): BlockChain = {
    val blockchain: BlockChain = new BlockChain {
      override val brokerConfig      = _brokerConfig
      override val consensusConfig   = _consensusSetting
      override val blockStorage      = storages.blockStorage
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
}
