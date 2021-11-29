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

import org.alephium.crypto.Blake3
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.config.{ConsensusConfigFixture, GroupConfigFixture}
import org.alephium.serde.serialize
import org.alephium.util.{AlephiumSpec, AVector, Hex, TimeStamp, U256}

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
    DefaultBlockVersion is 0.toByte

    val genesis = BlockHeader.genesis(ChainIndex.unsafe(0, 0), Hash.zero)
    genesis.version is 0.toByte

    val header = genesis.copy(version = DefaultBlockVersion)
    header.version is 0.toByte
  }

  it should "serde the snapshots properly" in new ModelSnapshots {
    implicit val basePath = "src/test/resources/models/blockheader"

    import Hex._

    groups is 3

    info("header1")

    val nonce1 = Nonce.zero
    val header1 = BlockHeader(
      version = DefaultBlockVersion,
      blockDeps = BlockDeps.unsafe(AVector.fill(groupConfig.depsNum)(BlockHash.zero)),
      depStateHash =
        Hash.unsafe(hex"e5d64f886664c58378d41fe3b8c29dd7975da59245a4a6bf92c3a47339a9a0a9"),
      txsHash = Hash.unsafe(hex"c78682d23662320d6f59d6612f26e2bcb08caff68b589523064924328f6d0d59"),
      timestamp = TimeStamp.unsafe(1),
      target = consensusConfig.maxMiningTarget,
      nonce = nonce1
    )

    val header1Blob = header1.verify("header1")

    val block     = Block(header1, AVector.empty)
    val blockBlob = header1Blob ++ hex"00"
    serialize(block) is blockBlob

    info("header2")

    val nonce2 = Nonce.unsafe(U256.Two.toBytes.takeRight(Nonce.byteLength))
    val header2 = BlockHeader(
      version = DefaultBlockVersion,
      blockDeps = BlockDeps.build(
        deps = AVector(
          Blake3.unsafe(hex"1b08f56d011b4d1ad498064e21cdcb07ac6a28bc3831be97d96034708de50e07"),
          Blake3.unsafe(hex"6a5ab35f69b467512c90d53f9f06f5697f4fad672da15576fa76003ee748e212"),
          Blake3.unsafe(hex"aedb5ceabb1e2d2f001096c59726408b86bbde953fe67eb27973cc8056517c93"),
          Blake3.unsafe(hex"c2a55cd706725874ab1ffe46eeafc51be5352582f2ff02afb098a127e052135d"),
          Blake3.unsafe(hex"4325372753815ecfd0410812a28473824d88e58c8e5686dccfd7a3d1c3a1f405")
        )
      ),
      depStateHash =
        Hash.unsafe(hex"798e9e137aec7c2d59d9655b4ffa640f301f628bf7c365083bb255f6aa5f89ef"),
      txsHash = Hash.unsafe(hex"bdaf9dc514ce7d34b6474b8ca10a3dfb93ba997cb9d5ff1ea724ebe2af48abe5"),
      timestamp = TimeStamp.unsafe(102348),
      target = consensusConfig.maxMiningTarget,
      nonce = nonce2
    )

    header2.verify("header2")
  }
}
