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

import scala.collection.mutable
import akka.util.ByteString
import org.alephium.flow.{AlephiumFlowSpec, FlowFixture}
import org.alephium.protocol.ALPH
import org.alephium.protocol.Generators.hashGen
import org.alephium.protocol.model.{
  AssetOutput,
  ChainIndex,
  ContractOutputRef,
  GroupIndex,
  Hint,
  TxOutput,
  TxOutputRef
}
import org.alephium.util.{AVector, TimeStamp}

class BlockFlowGroupViewSpec extends AlephiumFlowSpec {
  it should "fetch getPreAssetOutputs and getPreOutputs" in new FlowFixture {
    val blockFlow1 = isolatedBlockFlow()
    val now        = TimeStamp.now()
    val block0     = transfer(blockFlow1, ChainIndex.unsafe(0, 1), now)
    addAndCheck(blockFlow1, block0)
    val block1 = transfer(blockFlow1, ChainIndex.unsafe(0, 2), now.plusMillisUnsafe(1))
    addAndCheck(blockFlow1, block1)
    val block2 = transfer(blockFlow1, ChainIndex.unsafe(0, 1), now.plusMillisUnsafe(2))
    addAndCheck(blockFlow1, block2)
    val block3 = transfer(blockFlow1, ChainIndex.unsafe(0, 0), now.plusMillisUnsafe(3))
    addAndCheck(blockFlow1, block3)

    addAndCheck(blockFlow, block0)

    val grandPool = blockFlow.getGrandPool()
    val mempool   = blockFlow.getMemPool(ChainIndex.unsafe(0, 0))

    val mainGroup    = GroupIndex.unsafe(0)
    val lockupScript = getGenesisLockupScript(ChainIndex(mainGroup, mainGroup))

    val tx1        = block1.nonCoinbase.head.toTemplate
    val groupView1 = blockFlow.getImmutableGroupView(mainGroup).rightValue
    groupView1.getPreAssetOutputs(tx1.unsigned.inputs).rightValue.get is
      block0.nonCoinbase.head.unsigned.fixedOutputs.tail
    groupView1.getRelevantUtxos(lockupScript, Int.MaxValue, false).rightValue.map(_.output) is
      block0.nonCoinbase.head.unsigned.fixedOutputs.tail
    grandPool.add(block1.chainIndex, tx1, now)
    mempool.contains(tx1) is true

    val tx2        = block2.nonCoinbase.head.toTemplate
    val groupView2 = blockFlow.getImmutableGroupViewIncludePool(mainGroup).rightValue
    groupView2.getPreAssetOutputs(tx1.unsigned.inputs).rightValue.isEmpty is true
    groupView2
      .getPreOutputs(block2.nonCoinbase.head, None)
      .rightValue
      .get is block1.nonCoinbase.head.unsigned.fixedOutputs.tail.as[TxOutput]
    groupView2.getPreAssetOutputs(tx2.unsigned.inputs).rightValue.get is
      block1.nonCoinbase.head.unsigned.fixedOutputs.tail
    groupView2.getRelevantUtxos(lockupScript, Int.MaxValue, false).rightValue.map(_.output) is
      block1.nonCoinbase.head.unsigned.fixedOutputs.tail
    grandPool.add(block2.chainIndex, tx2, now.plusMillisUnsafe(1))
    mempool.contains(tx2) is true

    val tx3        = block3.nonCoinbase.head.toTemplate
    val groupView3 = blockFlow.getImmutableGroupViewIncludePool(mainGroup).rightValue
    groupView3.getPreAssetOutputs(tx1.unsigned.inputs).rightValue.isEmpty is true
    groupView3.getPreAssetOutputs(tx2.unsigned.inputs).rightValue.isEmpty is true
    groupView3.getPreAssetOutputs(tx3.unsigned.inputs).rightValue.get is
      block2.nonCoinbase.head.unsigned.fixedOutputs.tail
    groupView3.getRelevantUtxos(lockupScript, Int.MaxValue, false).rightValue.map(_.output) is
      block2.nonCoinbase.head.unsigned.fixedOutputs.tail

    val outputInfos = tx1.unsigned.inputs.map { input =>
      input.outputRef -> AssetOutput(
        ALPH.oneAlph,
        lockupScript,
        TimeStamp.zero,
        AVector.empty,
        ByteString.empty
      )
    }
    val additionalCache = mutable.Map.from(outputInfos)
    groupView3.getPreOutputs(block1.nonCoinbase.head, None).rightValue.isDefined is false
    groupView3
      .getPreOutputs(block1.nonCoinbase.head, Some(additionalCache))
      .rightValue
      .isDefined is true
    groupView2.exists(block1.nonCoinbase.head.unsigned.inputs, Set.empty).rightValue is false
    groupView2
      .exists(block1.nonCoinbase.head.unsigned.inputs, additionalCache.keySet)
      .rightValue is true
  }

  it should "fetch getPreContractOutput and getPreOutputs" in new TxOutputRefIndexFixture {
    override def enableTxOutputRefIndex: Boolean = true

    val missingContractOutputRef = ContractOutputRef.unsafe(
      Hint.unsafe(0),
      TxOutputRef.unsafeKey(hashGen.sample.get)
    )

    val groupView = blockFlow.getImmutableGroupView(GroupIndex.unsafe(0)).rightValue

    // test non-existing contract is not found
    groupView.getPreContractOutput(missingContractOutputRef).rightValue.isEmpty is true

    // test new contract is not spent
    groupView
      .getPreContractOutput(contractOutputRef)
      .rightValue
      .get
      .lockupScript is contractOutputScript

    // call script
    val withdrawBlock = testTxScriptCalling()

    // test both asset and contract are collected from inputs
    val tx = withdrawBlock.transactions.head
    tx.contractInputs.nonEmpty is true
    groupView
      .getPreOutputs(tx, None)
      .rightValue
      .get
      .map(out => out.lockupScript) is AVector(
      fromLockupScript,
      contractOutputScript
    )
  }
}
