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
import org.alephium.flow.validation.TxValidation
import org.alephium.protocol.ALF
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
  }
}
