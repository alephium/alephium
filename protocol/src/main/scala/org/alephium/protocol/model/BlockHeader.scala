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

import org.alephium.protocol.{ALPH, BlockHash, Hash}
import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.protocol.mining.PoW
import org.alephium.serde.{u256Serde => _, _}
import org.alephium.util.{AVector, TimeStamp, U256}

final case class BlockHeader(
    nonce: Nonce,
    version: Byte,
    blockDeps: BlockDeps,
    depStateHash: Hash,
    txsHash: Hash,
    timestamp: TimeStamp,
    target: Target
) extends FlowData {
  lazy val hash: BlockHash = PoW.hash(this)

  lazy val chainIndex: ChainIndex = {
    val groups = (blockDeps.length + 1) / 2
    ChainIndex.from(hash, groups)
  }

  def isGenesis: Boolean = timestamp == ALPH.GenesisTimestamp

  def parentHash: BlockHash = {
    assume(!isGenesis)
    blockDeps.uncleHash(chainIndex.to)
  }

  def uncleHash(toIndex: GroupIndex): BlockHash = {
    assume(!isGenesis)
    blockDeps.uncleHash(toIndex)
  }

  def inDeps: AVector[BlockHash] = {
    assume(!isGenesis)
    blockDeps.inDeps
  }

  def outDeps: AVector[BlockHash] = {
    assume(!isGenesis)
    blockDeps.outDeps
  }

  def intraDep: BlockHash = {
    assume(!isGenesis)
    blockDeps.intraDep(chainIndex)
  }

  def getOutTip(toIndex: GroupIndex): BlockHash = {
    assume(!isGenesis)
    if (toIndex == chainIndex.to) hash else blockDeps.getOutDep(toIndex)
  }

  def getGroupTip(targetGroup: GroupIndex): BlockHash = {
    assume(!isGenesis)
    if (targetGroup.value < chainIndex.from.value) {
      blockDeps.deps(targetGroup.value)
    } else if (targetGroup.value > chainIndex.from.value) {
      blockDeps.deps(targetGroup.value - 1)
    } else {
      hash
    }
  }

  def outTips: AVector[BlockHash] = {
    assume(!isGenesis)
    blockDeps.outDeps.replace(chainIndex.to.value, hash)
  }

  def `type`: String = "BlockHeader"
}

object BlockHeader {
  implicit val serde: Serde[BlockHeader] =
    Serde.forProduct7(
      apply,
      bh =>
        (bh.nonce, bh.version, bh.blockDeps, bh.depStateHash, bh.txsHash, bh.timestamp, bh.target)
    )

  def genesis(txsHash: Hash, target: Target, nonce: Nonce)(implicit
      config: GroupConfig
  ): BlockHeader = {
    val deps = BlockDeps.build(AVector.fill(config.depsNum)(BlockHash.zero))
    BlockHeader(nonce, defaultBlockVersion, deps, Hash.zero, txsHash, ALPH.GenesisTimestamp, target)
  }

  def genesis(chainIndex: ChainIndex, txsHash: Hash)(implicit
      groupConfig: GroupConfig,
      consensusConfig: ConsensusConfig
  ): BlockHeader = {
    @tailrec
    def iter(count: U256): BlockHeader = {
      val nonce  = Nonce.unsafe(count.toBytes.takeRight(Nonce.byteLength))
      val header = BlockHeader.genesis(txsHash, consensusConfig.maxMiningTarget, nonce)
      // Note: we do not validate difficulty target here
      if (header.chainIndex == chainIndex) header else iter(count.addOneUnsafe())
    }

    iter(U256.Zero)
  }

  def unsafeWithRawDeps(
      deps: AVector[BlockHash],
      depStateHash: Hash,
      txsHash: Hash,
      timestamp: TimeStamp,
      target: Target,
      nonce: Nonce
  ): BlockHeader = {
    val blockDeps = BlockDeps.unsafe(deps)
    BlockHeader(nonce, defaultBlockVersion, blockDeps, depStateHash, txsHash, timestamp, target)
  }

  def unsafe(
      deps: BlockDeps,
      depStateHash: Hash,
      txsHash: Hash,
      timestamp: TimeStamp,
      target: Target,
      nonce: Nonce
  ): BlockHeader = {
    unsafeWithRawDeps(deps.deps, depStateHash, txsHash, timestamp, target, nonce)
  }
}
