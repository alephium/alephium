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

import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.config.{ConsensusConfigFixture, GroupConfigFixture}
import org.alephium.serde.serialize
import org.alephium.util.{AlephiumSpec, AVector, Hex, TimeStamp}

class BlockHeaderSpec
    extends AlephiumSpec
    with GroupConfigFixture.Default
    with ConsensusConfigFixture.Default {
  it should "have correct data" in {
    for {
      i <- 0 until groups
      j <- 0 until groups
    } {
      val chainIndex = ChainIndex.unsafe(i, j)
      val genesis    = BlockHeader.genesis(chainIndex, Hash.zero)

      genesis.isGenesis is true
      genesis.copy(timestamp = TimeStamp.unsafe(1)).isGenesis is false

      genesis.blockDeps.length is groupConfig.depsNum
      genesis.chainIndex is chainIndex
      ChainIndex.from(genesis.hash) is chainIndex
    }
  }

  it should "extract dependencies properly" in {
    val deps      = AVector.tabulate(groupConfig.depsNum)(i => BlockHash.hash(Seq(i.toByte)))
    val blockDeps = BlockDeps.build(deps)

    val genesis = BlockHeader.genesis(ChainIndex.unsafe(0, 0), Hash.zero)
    val header  = genesis.copy(blockDeps = blockDeps, timestamp = TimeStamp.unsafe(1))

    val chainIndex = header.chainIndex
    header.parentHash is blockDeps.deps(2 + chainIndex.to.value)
    header.intraDep is blockDeps.deps(2 + chainIndex.from.value)

    header.inDeps is deps.take(2)
    header.outDeps is deps.drop(2)
    header.outTips is deps.drop(2).replace(chainIndex.to.value, header.hash)
    for {
      i <- 0 until groups
    } {
      header.uncleHash(GroupIndex.unsafe(i)) is blockDeps.deps(2 + i)
    }
  }

  it should "not extract dependencies for genesis headers" in {
    val genesis = Block.genesis(ChainIndex.unsafe(0, 0), AVector.empty).header
    assertThrows[AssertionError](genesis.parentHash)
    assertThrows[AssertionError](genesis.intraDep)
    assertThrows[AssertionError](genesis.inDeps)
    assertThrows[AssertionError](genesis.outDeps)
    assertThrows[AssertionError](genesis.outTips)
    assertThrows[AssertionError](genesis.uncleHash(GroupIndex.unsafe(0)))
  }

  it should "handle version properly" in {
    defaultBlockVersion is 0.toByte
    defaultBlockVersionWithPoLW is 0x80.toByte

    val genesis = BlockHeader.genesis(ChainIndex.unsafe(0, 0), Hash.zero)
    genesis.version is 0.toByte
    genesis.isPoLWEnabled is false
    genesis.unmaskedVersion is 0.toByte

    val header0 = genesis.copy(version = defaultBlockVersion)
    header0.isPoLWEnabled is false
    header0.unmaskedVersion is defaultBlockVersion

    val header1 = genesis.copy(version = defaultBlockVersionWithPoLW)
    header1.isPoLWEnabled is true
    header1.unmaskedVersion is defaultBlockVersion
  }

  it should "serialize properly" in {
    import Hex._

    groups is 3

    val header = BlockHeader(
      version = defaultBlockVersion,
      blockDeps = BlockDeps.unsafe(AVector.fill(groupConfig.depsNum)(BlockHash.zero)),
      depStateHash =
        Hash.unsafe(hex"e5d64f886664c58378d41fe3b8c29dd7975da59245a4a6bf92c3a47339a9a0a9"),
      txsHash = Hash.unsafe(hex"c78682d23662320d6f59d6612f26e2bcb08caff68b589523064924328f6d0d59"),
      timestamp = TimeStamp.unsafe(1),
      target = consensusConfig.maxMiningTarget,
      nonce = Nonce.zero
    )

    val headerBlob =
      // nonce
      hex"000000000000000000000000000000000000000000000000" ++
        // version
        hex"00" ++
        // deps
        hex"05" ++ hex"00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" ++
        // state root hash
        hex"e5d64f886664c58378d41fe3b8c29dd7975da59245a4a6bf92c3a47339a9a0a9" ++
        // tx root hash
        hex"c78682d23662320d6f59d6612f26e2bcb08caff68b589523064924328f6d0d59" ++
        // timestamp
        hex"0000000000000001" ++
        // target
        hex"20ffffff"
    serialize(header) is headerBlob

    val block     = Block(header, AVector.empty)
    val blockBlob = headerBlob ++ hex"00"
    serialize(block) is blockBlob
  }
}
