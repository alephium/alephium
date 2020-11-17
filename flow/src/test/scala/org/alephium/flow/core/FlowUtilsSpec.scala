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
import org.alephium.util.{AlephiumSpec, AVector}

class FlowUtilsSpec extends AlephiumSpec with NoIndexModelGenerators {
  it should "generate failed tx" in new FlowFixture {
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

      val worldState = blockFlow.getBestCachedWorldState(groupIndex).extractedValue()
      assets.foreach { asset =>
        worldState.addAsset(asset.txInput.outputRef, asset.referredOutput).isRight is true
      }
      val firstInput  = assets.head.referredOutput.asInstanceOf[AssetOutput]
      val firstOutput = firstInput.copy(amount = firstInput.amount.subUnsafe(tx.gasFeeUnsafe))
      FlowUtils.generateFullTx(worldState, tx, script).extractedValue()._1 is
        Transaction(unsignedTx,
                    AVector.empty,
                    firstOutput +: assets.tail.map(_.referredOutput),
                    tx.inputSignatures,
                    tx.contractSignatures)
    }
  }
}
