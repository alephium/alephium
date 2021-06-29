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
import org.alephium.flow.mempool.MemPool
import org.alephium.flow.validation.TxValidation
import org.alephium.protocol.{ALF, Generators}
import org.alephium.protocol.model.{defaultGasFee, defaultGasPrice, ChainIndex, TransactionTemplate}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.AlephiumSpec

class TxUtilsSpec extends AlephiumSpec {
  it should "consider minimal gas fee" in new FlowFixture {
    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (genesisPriKey, _, _) = genesisKeys(0)
    val (toPriKey, _)         = chainIndex.from.generateKey
    val block0                = transfer(blockFlow, genesisPriKey, toPriKey.publicKey, amount = defaultGasFee)
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, genesisPriKey, toPriKey.publicKey, amount = defaultGasFee)
    addAndCheck(blockFlow, block1)

    blockFlow
      .transfer(
        toPriKey.publicKey,
        getGenesisLockupScript(chainIndex),
        None,
        defaultGasFee / 2,
        None,
        defaultGasPrice
      )
      .rightValue
      .isRight is true
  }

  it should "consider outputs for inter-group blocks" in new FlowFixture {
    val chainIndex            = ChainIndex.unsafe(0, 1)
    val (genesisPriKey, _, _) = genesisKeys(0)
    val (_, toPubKey)         = chainIndex.to.generateKey
    val block                 = transfer(blockFlow, genesisPriKey, toPubKey, ALF.alf(1))
    addAndCheck(blockFlow, block)

    val unsignedTx = blockFlow
      .transfer(
        genesisPriKey.publicKey,
        LockupScript.p2pkh(toPubKey),
        None,
        ALF.cent(50),
        None,
        defaultGasPrice
      )
      .rightValue
      .rightValue
    val tx = TransactionTemplate.from(unsignedTx, genesisPriKey)
    TxValidation.build.validateGrandPoolTxTemplate(tx, blockFlow) isE ()
    blockFlow.getMemPool(chainIndex).addNewTx(chainIndex, tx) is MemPool.AddedToSharedPool
    TxValidation.build.validateMempoolTxTemplate(tx, blockFlow) isE ()
  }

  it should "calculate preOutputs for txs in new blocks" in new FlowFixture with Generators {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    forAll(groupIndexGen, groupIndexGen) { (fromGroup, toGroup) =>
      val chainIndex = ChainIndex(fromGroup, toGroup)

      val block = transfer(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)

      val tx        = block.nonCoinbase.head
      val groupView = blockFlow.getMutableGroupView(chainIndex.from).rightValue
      groupView.getPreOutput(tx.unsigned.inputs.head.outputRef) isE None
      tx.assetOutputRefs.foreachWithIndex { case (outputRef, index) =>
        val output = tx.unsigned.fixedOutputs(index)
        if (output.toGroup equals chainIndex.from) {
          groupView.getPreOutput(outputRef) isE Some(output)
        } else {
          groupView.getPreOutput(outputRef) isE None
        }
      }
    }
  }

  it should "calculate preOutputs for txs in shared pool" in new FlowFixture with Generators {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    forAll(groupIndexGen, groupIndexGen) { (fromGroup, toGroup) =>
      val chainIndex = ChainIndex(fromGroup, toGroup)

      val block = transfer(blockFlow, chainIndex)
      val tx    = block.nonCoinbase.head
      blockFlow.getMemPool(chainIndex).addNewTx(chainIndex, tx.toTemplate)

      {
        val groupView = blockFlow.getMutableGroupView(fromGroup).rightValue
        tx.assetOutputRefs.foreach { outputRef =>
          groupView.getPreOutput(outputRef) isE None
        }
      }

      {
        val groupView = blockFlow.getMutableGroupViewIncludePool(fromGroup).rightValue
        groupView.getPreOutput(tx.unsigned.inputs.head.outputRef) isE None
        tx.assetOutputRefs.foreachWithIndex { case (outputRef, index) =>
          val output = tx.unsigned.fixedOutputs(index)
          if (output.toGroup equals chainIndex.from) {
            groupView.getPreOutput(outputRef) isE Some(output)
          } else {
            groupView.getPreOutput(outputRef) isE None
          }
        }
      }
    }
  }
}
