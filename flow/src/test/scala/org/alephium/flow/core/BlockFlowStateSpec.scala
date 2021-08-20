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
import org.alephium.util.{AlephiumSpec, Duration, TimeStamp}

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
          worldState.getOutput(outputRef).leftValue is a[IOError.KeyNotFound[_]]
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
}
