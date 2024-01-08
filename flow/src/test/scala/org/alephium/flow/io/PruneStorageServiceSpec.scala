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

import scala.util.Random

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.BlockFlow
import org.alephium.io.RocksDBKeyValueStorage
import org.alephium.io.SparseMerkleTrie.Node
import org.alephium.protocol.Hash
import org.alephium.protocol.model.Block
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.AlephiumSpec
import org.alephium.util.UnsecureRandom

class PruneStorageServiceSpec extends AlephiumSpec {
  trait Fixture extends FlowFixture {
    lazy val brokerGroup = UnsecureRandom.sample(brokerConfig.groupRange)
    lazy val chainIndex  = ChainIndex.unsafe(brokerGroup, brokerGroup)
    val blockChain       = blockFlow.getBlockChain(chainIndex)

    def addOneBlock(blockFlow: BlockFlow, block: Block): Block = {
      addAndCheck(blockFlow, block)
      block
    }
  }

  it should "verify that applyBlock works" in new Fixture {
    val pruneStateService = new PruneStorageService(storages)(blockFlow, groupConfig)
    val trieStorage =
      storages.worldStateStorage.trieStorage.asInstanceOf[RocksDBKeyValueStorage[Hash, Node]]

    addOneBlock(blockFlow, transfer(blockFlow, chainIndex))
    addOneBlock(blockFlow, emptyBlock(blockFlow, chainIndex))

    val block3          = addOneBlock(blockFlow, transfer(blockFlow, chainIndex))
    val allKeysAtBlock3 = getAllKeys(trieStorage)

    val restOfBlocks = (0 until 97).map { _ =>
      val block = Random.nextBoolean() match {
        case true  => transfer(blockFlow, chainIndex)
        case false => emptyBlock(blockFlow, chainIndex)
      }
      addOneBlock(blockFlow, block)
    }

    val allKeysFinal: Set[Hash] = getAllKeys(trieStorage).toSet

    blockFlow.getMaxHeight(chainIndex).rightValue is 100

    val keysAfterApplyingRestOfBlocks = restOfBlocks
      .foldLeft(allKeysAtBlock3) { (acc, block) =>
        val result = pruneStateService.applyBlock(block3.hash, block.hash).rightValue
        acc ++ result
      }
      .toSet

    allKeysFinal is keysAfterApplyingRestOfBlocks
  }

  private def getAllKeys(trieStorage: RocksDBKeyValueStorage[Hash, Node]): Seq[Hash] = {
    var allKeys: Seq[Hash] = Seq.empty[Hash]
    trieStorage.iterateRawE((k, _) => {
      allKeys = allKeys :+ Hash.unsafe(k)
      Right(())
    })
    allKeys
  }
}
