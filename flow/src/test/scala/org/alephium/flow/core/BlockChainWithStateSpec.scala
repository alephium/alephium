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

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.io.Storages
import org.alephium.io.RocksDBSource.ProdSettings
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Block, ChainIndex, NoIndexModelGeneratorsLike, Weight}
import org.alephium.util.AVector

class BlockChainWithStateSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  trait Fixture {
    val genesis =
      Block.genesis(ChainIndex.unsafe(0, 0), AVector.empty)(groupConfig, consensusConfigs.mainnet)
    val blockGen = blockGenOf(AVector.fill(brokerConfig.depsNum)(genesis.hash), Hash.zero)
    val chainGen = chainGenOf(4, genesis)
    val heightDB = storages.nodeStateStorage.heightIndexStorage(ChainIndex.unsafe(0, 0))
    val stateDB  = storages.nodeStateStorage.chainStateStorage(ChainIndex.unsafe(0, 0))

    var updateCount = 0

    def buildGenesis(): BlockChainWithState = {
      val dbFolder = "db-" + Hash.random.toHexString
      val storages = Storages.createUnsafe(rootPath, dbFolder, ProdSettings.syncWrite)
      BlockChainWithState.createUnsafe(
        genesis,
        storages,
        (_, _) => { updateCount += 1; Right(()) },
        BlockChainWithState.initializeGenesis(genesis, storages.emptyWorldState)(_)
      )
    }

    def addAndCheck(chain: BlockChainWithState, block: Block, weight: Weight = Weight.zero) = {
      val worldState = chain.getCachedWorldState(block.parentHash).rightValue
      chain.add(block, weight, Some(worldState)).isRight is true
    }
  }

  it should "add block" in new Fixture {
    forAll(blockGen) { block =>
      val chain = buildGenesis()
      chain.numHashes is 1
      val blocksSize1  = chain.numHashes
      val initialCount = updateCount
      addAndCheck(chain, block)
      val blocksSize2 = chain.numHashes
      blocksSize1 + 1 is blocksSize2
      updateCount is initialCount + 1
    }
  }

  it should "add blocks correctly" in new Fixture {
    forAll(chainGen) { blocks =>
      val chain        = buildGenesis()
      val blocksSize1  = chain.numHashes
      val initialCount = updateCount
      blocks.foreach(block => addAndCheck(chain, block))
      val blocksSize2 = chain.numHashes
      blocksSize1 + blocks.length is blocksSize2
      updateCount is initialCount + blocks.length
    }
  }

  it should "get maximal height based on weights" in new Fixture {
    val longChain  = chainGenOf(3, genesis).sample.get
    val shortChain = chainGenOf(2, genesis).sample.get
    val chain      = buildGenesis()
    longChain.foreachWithIndex { case (block, index) =>
      addAndCheck(chain, block, Weight(index))
    }
    shortChain.foreachWithIndex { case (block, index) =>
      addAndCheck(chain, block, Weight(index * 3))
    }
    chain.getAllTips.toSet is Set(longChain.last.hash, shortChain.last.hash)
    chain.getBestTipUnsafe() is shortChain.last.hash
    chain.maxWeight isE Weight(3)
    chain.maxHeightByWeight isE 2
    chain.maxHeightByWeightUnsafe is 2
  }
}
