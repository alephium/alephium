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

import org.apache.pekko.util.ByteString

import org.alephium.flow.FlowFixture
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.protocol.vm.nodeindexes.{TxIdTxOutputLocators, TxOutputLocator}
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp, U256}

class NodeIndexesUtilsSpec extends AlephiumSpec {
  it should "copy output bytes instead of sharing the source block buffer" in new FlowFixture {
    override val configValues: Map[String, Any] = Map(
      "alephium.node.indexes.tx-output-ref-index" -> true
    )

    val output = AssetOutput(
      U256.One,
      LockupScript.p2pkh(Hash.generate),
      TimeStamp.zero,
      AVector((TokenId.generate, U256.One)),
      ByteString(1, 2, 3)
    )
    val block       = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    val tx          = block.coinbase.copy(generatedOutputs = AVector(output))
    val blockBytes  = serialize(block.copy(transactions = AVector(tx))).toArray
    val storedBlock = deserialize[Block](ByteString.fromArrayUnsafe(blockBytes)).rightValue
    blockFlow.getBlockChain(block.chainIndex).blockCache.put(block.hash, storedBlock)

    val outputIndex = tx.unsigned.fixedOutputs.length
    val outputRef   = tx.outputRefs(outputIndex)
    blockFlow.txOutputRefIndexStorage.rightValue.put(
      outputRef.key,
      TxIdTxOutputLocators(tx.id, AVector(TxOutputLocator(block.hash, 0, outputIndex)))
    ) isE ()
    val copiedOutput = blockFlow.getTxOutput(outputRef, block.hash).rightValue.value._2

    java.util.Arrays.fill(blockBytes, 0.toByte)
    copiedOutput is output
  }

  it should "calc the block diff properly" in new FlowFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    private def mineBlock(chainIndex: ChainIndex, height: Int): Block = {
      val block = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      blockFlow.getHeightUnsafe(block.hash) is height
      block
    }

    val group0 = GroupIndex.random
    val group1 = GroupIndex.unsafe((group0.value + 1) % groupConfig.groups)
    val group2 = GroupIndex.random

    val block01_1 = mineBlock(ChainIndex(group0, group1), 1)
    val block01_2 = mineBlock(ChainIndex(group0, group1), 2)
    val block01_3 = mineBlock(ChainIndex(group0, group1), 3)
    blockFlow.calcBlockDiffUnsafe(block01_1.hash, block01_2.hash) is 1
    blockFlow.calcBlockDiffUnsafe(block01_1.hash, block01_3.hash) is 2

    mineBlock(ChainIndex(group0, group0), 1)
    val block12_1 = mineBlock(ChainIndex(group1, group2), 1)
    blockFlow.calcBlockDiffUnsafe(block01_1.hash, block12_1.hash) is 2
    blockFlow.calcBlockDiffUnsafe(block01_2.hash, block12_1.hash) is 1
  }
}
