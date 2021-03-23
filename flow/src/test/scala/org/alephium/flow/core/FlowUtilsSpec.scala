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
import org.alephium.protocol.SignatureSchema
import org.alephium.protocol.model._
import org.alephium.protocol.vm.StatefulScript
import org.alephium.util.{AlephiumSpec, AVector, Bytes}

class FlowUtilsSpec extends AlephiumSpec {
  it should "generate failed tx" in new FlowFixture with NoIndexModelGeneratorsLike {
    val groupIndex = GroupIndex.unsafe(0)
    forAll(assetsToSpendGen(2, 2, 0, 1, p2pkScriptGen(groupIndex))) { assets =>
      val inputs     = assets.map(_.txInput)
      val script     = StatefulScript.alwaysFail
      val unsignedTx = UnsignedTransaction(txScriptOpt = Some(script), inputs, AVector.empty)
      val tx = TransactionTemplate(
        unsignedTx,
        assets.map(asset => SignatureSchema.sign(unsignedTx.hash.bytes, asset.privateKey)),
        AVector.empty
      )

      val worldState = blockFlow.getBestCachedWorldState(groupIndex).rightValue
      assets.foreach { asset =>
        worldState.addAsset(asset.txInput.outputRef, asset.referredOutput).isRight is true
      }
      val firstInput  = assets.head.referredOutput.asInstanceOf[AssetOutput]
      val firstOutput = firstInput.copy(amount = firstInput.amount.subUnsafe(tx.gasFeeUnsafe))
      FlowUtils.generateFullTx(worldState, tx, script).rightValue is
        Transaction(
          unsignedTx,
          AVector.empty,
          firstOutput +: assets.tail.map(_.referredOutput),
          tx.inputSignatures,
          tx.contractSignatures
        )
    }
  }

  it should "check hash order" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val newBlocks = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(i, j))
    newBlocks.foreach { block =>
      addAndCheck(blockFlow, block, 1)
      blockFlow.getWeight(block) isE consensusConfig.maxMiningTarget * 1
    }

    newBlocks.map(_.hash).sorted(blockFlow.blockHashOrdering).map(_.bytes) is
      newBlocks.map(_.hash.bytes).sorted(Bytes.byteStringOrdering)

    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(0).hash, newBlocks(0).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(1).hash, newBlocks(0).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(0).hash, newBlocks(1).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(1).hash, newBlocks(1).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(0).hash, newBlocks(2).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(1).hash, newBlocks(2).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(0).hash, newBlocks(3).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(1).hash, newBlocks(3).hash) is true
  }

  it should "filter double spending txs" in new NoIndexModelGenerators {
    val tx0 = transactionGen(minInputs = 2, maxInputs = 2).sample.get
    val tx1 = transactionGen(minInputs = 2, maxInputs = 2).sample.get
    val tx2 = transactionGen(minInputs = 2, maxInputs = 2).sample.get
    FlowUtils.filterDoubleSpending(AVector(tx0, tx1, tx2)) is AVector(tx0, tx1, tx2)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx0, tx2)) is AVector(tx0, tx2)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx1, tx0)) is AVector(tx0, tx1)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx2, tx2)) is AVector(tx0, tx2)
  }
}
