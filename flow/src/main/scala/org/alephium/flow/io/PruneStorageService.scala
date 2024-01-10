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

package org.alephium.flow.io

import scala.annotation.tailrec
import scala.collection.mutable.Queue

import akka.util.ByteString
import org.slf4j.{Logger, LoggerFactory}

import org.alephium.crypto.{Blake2b => Hash}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.validation.BlockValidation
import org.alephium.io.{IOResult, RocksDBKeyValueStorage, SparseMerkleTrie}
import org.alephium.io.SparseMerkleTrie.Node
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BlockHash, ChainIndex}
import org.alephium.protocol.vm.ContractStorageImmutableState
import org.alephium.serde.Serde
import org.alephium.util.AVector
import org.alephium.util.BloomFilter

class PruneStorageService(
    storages: Storages
)(implicit
    blockFlow: BlockFlow,
    groupConfig: GroupConfig
) {
  private val logger: Logger         = LoggerFactory.getLogger(this.getClass)
  private val retainedHeight         = 128
  private val bloomNumberOfHashes    = 80000000L
  private val bloomFalsePositiveRate = 0.01

  def buildBloomFilter(): IOResult[BloomFilter] = {
    for {
      retainedBlockHashes <- getRetainedBlockHashes()
      bloomFilter <- retainedBlockHashes
        .foldE(BloomFilter(bloomNumberOfHashes, bloomFalsePositiveRate)) {
          case (bloomFilter, blockHashes) =>
            buildBloomFilter(blockHashes, bloomFilter)
        }
    } yield bloomFilter
  }

  def buildBloomFilter(
      blockHashes: AVector[BlockHash],
      bloomFilter: BloomFilter
  ): IOResult[BloomFilter] = {
    assume(
      blockHashes.length == retainedHeight,
      s"blocks length ${blockHashes.length} should be equal to $retainedHeight"
    )
    for {
      bloomFilter0 <- buildBloomFilter(blockHashes.head, bloomFilter)
      bloomFilter  <- applyLatestBlocks(blockHashes.head, blockHashes.tail, bloomFilter0)
    } yield bloomFilter
  }

  def buildBloomFilter(
      blockHash: BlockHash,
      bloomFilter: BloomFilter
  ): IOResult[BloomFilter] = {
    val chainIndex = ChainIndex.from(blockHash)
    for {
      persistedWorldState <- storages.worldStateStorage.getPersistedWorldState(blockHash)
      bloomFilter0 <- buildBloomFilter(
        s"outputState for ${chainIndex}",
        persistedWorldState.outputState,
        Queue(Seq(persistedWorldState.outputState.rootHash)),
        bloomFilter,
        0
      )
      bloomFilter1 <- buildBloomFilter(
        s"contractState for ${chainIndex}",
        persistedWorldState.contractState,
        Queue(Seq(persistedWorldState.contractState.rootHash)),
        bloomFilter0,
        0
      )
      bloomFilter <- buildBloomFilter(
        s"codeState for ${chainIndex}",
        persistedWorldState.codeState,
        Queue(Seq(persistedWorldState.codeState.rootHash)),
        bloomFilter1,
        0
      )
    } yield bloomFilter
  }

  @tailrec
  final def buildBloomFilter[K, V](
      label: String,
      smt: SparseMerkleTrie[K, V],
      currentHashesQueue: Queue[Seq[Hash]],
      bloomFilter: BloomFilter,
      accHashes: Int
  ): IOResult[BloomFilter] = {
    logger.info(s"$label: current bloom filter items: ${accHashes}")

    if (currentHashesQueue.nonEmpty) {
      val currentHashes = currentHashesQueue.dequeue()

      currentHashes.map(_.bytes).foreach(bloomFilter.add(_))

      val allNodes = smt.getNodesUnsafe(currentHashes)
      val newHashes: Seq[Hash] = allNodes.flatMap { node =>
        node match {
          case SparseMerkleTrie.BranchNode(_, children) =>
            children.toSeq.flatten
          case SparseMerkleTrie.LeafNode(_, _) =>
            Seq.empty
        }
      }
      newHashes.grouped(256).foreach(currentHashesQueue.enqueue(_))
      buildBloomFilter(
        label,
        smt,
        currentHashesQueue,
        bloomFilter,
        accHashes + currentHashes.length
      )
    } else {
      Right(bloomFilter)
    }
  }

  def applyLatestBlocks(
      currentBlockHash: BlockHash,
      blockHashesToApply: AVector[BlockHash],
      bloomFilter: BloomFilter
  ): IOResult[BloomFilter] = {
    blockHashesToApply.foldE(bloomFilter) { (bloomFilter, blockHash) =>
      logger.info(s"applying block ${blockHash.toHexString}")

      applyBlock(currentBlockHash, blockHash).map { newKeys =>
        newKeys.map(_.bytes).foreach(bloomFilter.add(_))
        bloomFilter
      }
    }
  }

  def applyBlock(
      currentBlockHash: BlockHash,
      blockHash: BlockHash
  ): IOResult[AVector[Hash]] = {
    val blockValidation = BlockValidation.build(blockFlow)
    val chainIndex      = ChainIndex.from(currentBlockHash)
    assume(chainIndex.isIntraGroup)
    val blockchain = blockFlow.getBlockChain(chainIndex)

    for {
      block <- blockchain.getBlock(blockHash)
      cachedWorldState <- blockValidation.validate(block, blockFlow) match {
        case Right(Some(cachedWorldState)) =>
          Right(cachedWorldState)
        case Left(Left(error)) =>
          Left(error)
        case _ =>
          throw new Error(s"block ${blockHash.toHexString} is invalid")
      }
      _                 <- blockFlow.updateState(cachedWorldState, block)
      outputStateKeys   <- cachedWorldState.outputState.getNodeKeys()
      contractStateKeys <- cachedWorldState.contractState.getNodeKeys()
      codeStateKeys     <- cachedWorldState.codeState.getNodeKeys()
    } yield outputStateKeys ++ contractStateKeys ++ codeStateKeys
  }

  def prune(bloomFilter: BloomFilter): (Int, Int) = {
    val trieStorage =
      storages.worldStateStorage.trieStorage.asInstanceOf[RocksDBKeyValueStorage[Hash, Node]]
    var totalCount = 0
    var pruneCount = 0

    val result = trieStorage.iterateRawE((key, value) => {
      if (totalCount % 1000000 == 0) {
        logger.info(s"[Pruning..] totalCount: ${totalCount}, pruneCount: ${pruneCount}")
      }

      if (!bloomFilter.mightContain(key) && notImmutableState(value)) {
        pruneCount += 1
        trieStorage.deleteRawUnsafe(key)
      }
      totalCount += 1
      Right(())
    })

    result match {
      case Left(error) =>
        throw error
      case Right(_) =>
        (totalCount, pruneCount)
    }
  }

  def getRetainedBlockHashes(): IOResult[AVector[AVector[BlockHash]]] = {
    for {
      result <- groupConfig.cliqueGroupIndexes.mapE { groupIndex =>
        getRetainBlockHashesForChain(ChainIndex(groupIndex, groupIndex))
      }
    } yield result
  }

  def getRetainBlockHashesForChain(chainIndex: ChainIndex): IOResult[AVector[BlockHash]] = {
    for {
      bestTip <- blockFlow.getHeaderChain(chainIndex).getBestTip()
      result  <- getRetainBlockHashes(bestTip, 0, AVector(bestTip))
    } yield result
  }

  @tailrec
  private def getRetainBlockHashes(
      blockHash: BlockHash,
      currentHeight: Int,
      allBlockHashes: AVector[BlockHash]
  ): IOResult[AVector[BlockHash]] = {
    if (currentHeight >= retainedHeight - 1) {
      Right(allBlockHashes)
    } else {
      storages.headerStorage.get(blockHash).map(_.parentHash) match {
        case Right(parentHash) =>
          getRetainBlockHashes(
            parentHash,
            currentHeight + 1,
            parentHash +: allBlockHashes
          )
        case Left(err) =>
          Left(err)
      }
    }
  }

  def notImmutableState(value: ByteString): Boolean = {
    implicitly[Serde[ContractStorageImmutableState]].deserialize(value).isLeft
  }
}
