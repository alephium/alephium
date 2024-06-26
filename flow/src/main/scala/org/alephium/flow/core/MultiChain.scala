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

import scala.reflect.ClassTag

import akka.util.ByteString

import org.alephium.flow.model.BlockState
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{BlockEnv, WorldState}
import org.alephium.serde.serialize
import org.alephium.util.{AVector, Cache, RWLock, TimeStamp}

// scalastyle:off number.of.methods
trait MultiChain extends BlockPool with BlockHeaderPool with FlowDifficultyAdjustment {
  implicit def brokerConfig: BrokerConfig

  def groups: Int

  protected def aggregateHash[T](f: BlockHashPool => T)(op: (T, T) => T): T

  protected def aggregateHashE[T](f: BlockHashPool => IOResult[T])(op: (T, T) => T): IOResult[T]

  protected def concatOutBlockChainsE[T: ClassTag](
      f: BlockChain => IOResult[T]
  ): IOResult[AVector[T]]

  protected def concatIntraBlockChainsE[T: ClassTag](
      f: BlockChain => IOResult[T]
  ): IOResult[AVector[T]]

  def numHashes: Int = aggregateHash(_.numHashes)(_ + _)

  /* BlockHash apis */
  def contains(hash: BlockHash): IOResult[Boolean] = {
    val index = ChainIndex.from(hash)
    val chain = getHashChain(index)
    chain.contains(hash)
  }

  def containsUnsafe(hash: BlockHash): Boolean = {
    val index = ChainIndex.from(hash)
    val chain = getHashChain(index)
    chain.containsUnsafe(hash)
  }

  protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain

  def getHashChain(chainIndex: ChainIndex): BlockHashChain = {
    getHashChain(chainIndex.from, chainIndex.to)
  }

  def getHashChain(hash: BlockHash): BlockHashChain = {
    val index = ChainIndex.from(hash)
    getHashChain(index.from, index.to)
  }

  def isTip(hash: BlockHash): Boolean = {
    getHashChain(hash).isTip(hash)
  }

  def getHeightedBlocks(
      fromTs: TimeStamp,
      toTs: TimeStamp
  ): IOResult[AVector[(ChainIndex, AVector[(Block, Int)])]] =
    concatOutBlockChainsE(_.getHeightedBlocks(fromTs, toTs))

  def getHeightedIntraBlocks(
      fromTs: TimeStamp,
      toTs: TimeStamp
  ): IOResult[AVector[(ChainIndex, AVector[(Block, Int)])]] =
    concatIntraBlockChainsE(_.getHeightedBlocks(fromTs, toTs))

  def getHashesAfter(locator: BlockHash): IOResult[AVector[BlockHash]] =
    getHashChain(locator).getHashesAfter(locator)

  def getPredecessor(hash: BlockHash, height: Int): IOResult[BlockHash] =
    getHashChain(hash).getPredecessor(hash, height)

  def chainBackUntil(hash: BlockHash, heightUntil: Int): IOResult[AVector[BlockHash]] =
    getHashChain(hash).chainBackUntil(hash, heightUntil)

  def getState(hash: BlockHash): IOResult[BlockState] =
    getHashChain(hash).getState(hash)

  def getStateUnsafe(hash: BlockHash): BlockState =
    getHashChain(hash).getStateUnsafe(hash)

  def getHeight(hash: BlockHash): IOResult[Int] =
    getHashChain(hash).getHeight(hash)

  def getHeightUnsafe(hash: BlockHash): Int =
    getHashChain(hash).getHeightUnsafe(hash)

  def getWeight(hash: BlockHash): IOResult[Weight] =
    getHashChain(hash).getWeight(hash)

  def getWeightUnsafe(hash: BlockHash): Weight =
    getHashChain(hash).getWeightUnsafe(hash)

  def getBlockHashSlice(hash: BlockHash): IOResult[AVector[BlockHash]] =
    getHashChain(hash).getBlockHashSlice(hash)

  /* BlockHeader apis */

  protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderChain

  def getHeaderChain(chainIndex: ChainIndex): BlockHeaderChain =
    getHeaderChain(chainIndex.from, chainIndex.to)

  def getHeaderChain(header: BlockHeader): BlockHeaderChain =
    getHeaderChain(header.chainIndex)

  def getHeaderChain(hash: BlockHash): BlockHeaderChain =
    getHeaderChain(ChainIndex.from(hash))

  def getBlockHeader(hash: BlockHash): IOResult[BlockHeader] =
    getHeaderChain(hash).getBlockHeader(hash)

  def getBlockHeaderUnsafe(hash: BlockHash): BlockHeader =
    getHeaderChain(hash).getBlockHeaderUnsafe(hash)

  def getGhostUncles(
      parentHeader: BlockHeader,
      validator: BlockHeader => Boolean
  ): IOResult[AVector[SelectedGhostUncle]] =
    getBlockChain(parentHeader.chainIndex).selectGhostUncles(parentHeader, validator)

  def getGhostUnclesUnsafe(
      parentHeader: BlockHeader,
      validator: BlockHeader => Boolean
  ): AVector[SelectedGhostUncle] =
    getBlockChain(parentHeader.chainIndex).selectGhostUnclesUnsafe(parentHeader, validator)

  def add(header: BlockHeader): IOResult[Unit]

  def getHashes(chainIndex: ChainIndex, height: Int): IOResult[AVector[BlockHash]] =
    getHeaderChain(chainIndex).getHashes(height)

  def getMaxHeightByWeight(chainIndex: ChainIndex): IOResult[Int] =
    getHeaderChain(chainIndex).maxHeightByWeight

  def getDryrunBlockEnv(chainIndex: ChainIndex): IOResult[BlockEnv] = {
    getHeaderChain(chainIndex).getDryrunBlockEnv()
  }

  /* BlockChain apis */

  protected def getBlockChain(from: GroupIndex, to: GroupIndex): BlockChain

  def getBlockChain(chainIndex: ChainIndex): BlockChain =
    getBlockChain(chainIndex.from, chainIndex.to)

  def getBlockChain(block: Block): BlockChain = getBlockChain(block.chainIndex)

  def getBlockChain(hash: BlockHash): BlockChain = {
    getBlockChain(ChainIndex.from(hash))
  }

  def getBlockUnsafe(hash: BlockHash): Block = {
    getBlockChain(hash).getBlockUnsafe(hash)
  }

  def getBlock(hash: BlockHash): IOResult[Block] = {
    getBlockChain(hash).getBlock(hash)
  }

  def getBlockBytes(hash: BlockHash): IOResult[ByteString] = {
    getBlockChain(hash).getBlockBytes(hash)
  }

  private def getMainChainBlockByGhostUncleUnsafe(
      chainIndex: ChainIndex,
      ghostUncleHash: BlockHash
  ): Option[(Block, Int)] = {
    val chain            = getBlockChain(chainIndex)
    val maxHeight        = chain.maxHeightByWeightUnsafe
    val ghostUncleHeight = getHeightUnsafe(ghostUncleHash)
    val fromHeight       = Math.min(ghostUncleHeight + 1, maxHeight)
    val toHeight         = Math.min(ghostUncleHeight + ALPH.MaxGhostUncleAge, maxHeight)
    var mainChainBlock: Option[(Block, Int)] = None
    (fromHeight to toHeight).find { height =>
      val blockHash = chain.getHashesUnsafe(height).head
      val block     = chain.getBlockUnsafe(blockHash)
      block.ghostUncleHashes match {
        case Right(hashes) =>
          val result = hashes.contains(ghostUncleHash)
          if (result) mainChainBlock = Some((block, height))
          result
        case Left(error) => throw error
      }
    }
    mainChainBlock
  }

  def getMainChainBlockByGhostUncle(
      chainIndex: ChainIndex,
      ghostUncleHash: BlockHash
  ): IOResult[Option[(Block, Int)]] = {
    IOUtils.tryExecute(getMainChainBlockByGhostUncleUnsafe(chainIndex, ghostUncleHash))
  }

  val bodyVerifyingBlocks = MultiChain.bodyVerifyingBlocks(brokerConfig.chainNum * 2)
  def getHeaderVerifiedBlockBytes(hash: BlockHash): IOResult[ByteString] = {
    bodyVerifyingBlocks.get(hash) match {
      case Some(block) => Right(serialize(block))
      case None        => getBlockBytes(hash)
    }
  }

  def cacheHeaderVerifiedBlock(block: Block): Unit = {
    bodyVerifyingBlocks.put(block)
  }

  def add(block: Block, worldStateOpt: Option[WorldState.Cached]): IOResult[Unit]
}

object MultiChain {
  def bodyVerifyingBlocks(capacity: Int): BodyVerifyingBlocks =
    BodyVerifyingBlocks(Cache.fifo[BlockHash, Block](capacity))

  final case class BodyVerifyingBlocks(cache: Cache[BlockHash, Block]) extends RWLock {
    def put(block: Block): Unit             = writeOnly(cache.put(block.hash, block))
    def get(hash: BlockHash): Option[Block] = readOnly(cache.get(hash))
  }
}
