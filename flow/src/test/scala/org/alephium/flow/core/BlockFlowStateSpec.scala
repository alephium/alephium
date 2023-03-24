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

import org.alephium.flow.FlowFixture
import org.alephium.io.IOError
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector, Bytes, Duration, TimeStamp}

class BlockFlowStateSpec extends AlephiumSpec {
  trait Fixture extends FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))
  }

  it should "calculate all the hashes for state update" in new Fixture {
    def prepare(chainIndex: ChainIndex): Block = {
      val block = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      block
    }

    val mainGroup = GroupIndex.unsafe(0)
    val block0    = prepare(ChainIndex.unsafe(0, 2))
    val block1    = prepare(ChainIndex.unsafe(1, 0))
    prepare(ChainIndex.unsafe(1, 1))
    val block2 = prepare(ChainIndex.unsafe(0, 1))
    blockFlow.getHashesForUpdates(mainGroup).rightValue.toSet is
      Set(block0.hash, block1.hash, block2.hash)
  }

  trait LockTimeFixture extends Fixture {
    def checkUtxo(blockFlow: BlockFlow, chainIndex: ChainIndex, block: Block, tx: Transaction) = {
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
      tx.unsigned.fixedOutputs.mapWithIndex { case (output, index) =>
        val outputRef = AssetOutputRef.from(output, TxOutputRef.key(tx.id, index))
        if (chainIndex.isIntraGroup) {
          worldState.getOutput(outputRef) isE output.copy(lockTime = block.timestamp)
        } else {
          // the Utxo is not persisted yet
          worldState.getOutput(outputRef).leftValue is a[IOError.KeyNotFound]
        }
      }
    }
  }

  it should "use block time as lock time for UTXOs when the original lock time in TxOutput is zero" in new LockTimeFixture {
    for {
      fromGroup <- 0 until groups0
      toGroup   <- 0 until groups0
    } {
      val blockFlow  = isolatedBlockFlow()
      val chainIndex = ChainIndex.unsafe(fromGroup, toGroup)
      val block      = transfer(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)

      val tx = block.nonCoinbase.head
      checkUtxo(blockFlow, chainIndex, block, tx)
    }
  }

  it should "use block time as lock time for UTXOs when the original lock time in TxOutput is nonzero" in new LockTimeFixture {
    for {
      group <- 0 until groups0
    } {
      val blockFlow  = isolatedBlockFlow()
      val chainIndex = ChainIndex.unsafe(group, group)
      val lockTime   = TimeStamp.now().minusUnsafe(Duration.ofSecondsUnsafe(1))
      val block      = transfer(blockFlow, chainIndex, lockTimeOpt = Some(lockTime))
      addAndCheck(blockFlow, block)

      val tx = block.nonCoinbase.head
      checkUtxo(blockFlow, chainIndex, block, tx)
    }
  }

  it should "update UTXO block time for reorg" in new LockTimeFixture {
    (0 until groups0).foreach { mainGroup =>
      val blockFlow0 = isolatedBlockFlow()
      val chainIndex = ChainIndex.unsafe(mainGroup, mainGroup)
      val block0     = transfer(blockFlow0, chainIndex)
      val tx0        = block0.nonCoinbase.head
      addAndCheck(blockFlow0, block0)
      checkUtxo(blockFlow0, chainIndex, block0, tx0)

      info("Create a fork to reorg block0")
      val blockFlow1 = isolatedBlockFlow()
      val block1     = mineWithTxs(blockFlow1, chainIndex, block0.nonCoinbase)
      block1.nonCoinbase is block0.nonCoinbase
      addAndCheck(blockFlow1, block1)
      val block2 = emptyBlock(blockFlow1, chainIndex)
      addAndCheck(blockFlow0, block1)
      addAndCheck(blockFlow0, block2)

      checkUtxo(blockFlow0, chainIndex, block1, tx0)
    }
  }

  it should "check database completeness" in new Fixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val chain      = blockFlow.getBlockChain(chainIndex)
    blockFlow.sanityCheckUnsafe()

    val block00 = transfer(blockFlow, chainIndex)
    val block01 = transfer(blockFlow, chainIndex)

    val (block10, block11) =
      if (Bytes.byteStringOrdering.compare(block00.hash.bytes, block01.hash.bytes) < 0) {
        addAndCheck(blockFlow, block00)
        val block10 = transfer(blockFlow, chainIndex)
        addAndCheck(blockFlow, block01)
        val block11 = transfer(blockFlow, chainIndex)
        block10 -> block11
      } else {
        addAndCheck(blockFlow, block01)
        val block11 = transfer(blockFlow, chainIndex)
        addAndCheck(blockFlow, block00)
        val block10 = transfer(blockFlow, chainIndex)
        block10 -> block11
      }
    addAndCheck0(blockFlow, block10)
    addAndCheck0(blockFlow, block11)
    block10.parentHash is block00.hash
    block11.parentHash is block01.hash

    chain.heightIndexStorage.getUnsafe(1).toSet is Set(block00.hash, block01.hash)
    chain.heightIndexStorage.put(1, AVector(block01.hash)) // remove block00 on purpose
    chain.getAllTips.toSet is Set(block10.hash, block11.hash)
    blockFlow.sanityCheckUnsafe()
    chain.getAllTips.toSet is Set(block11.hash)
  }
}
