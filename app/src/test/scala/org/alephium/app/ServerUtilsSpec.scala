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

package org.alephium.app

import org.alephium.api.model._
import org.alephium.flow.FlowFixture
import org.alephium.protocol.{ALF, Generators, SignatureSchema}
import org.alephium.protocol.model.{Address, ChainIndex}
import org.alephium.util.{AlephiumSpec, AVector}

class ServerUtilsSpec extends AlephiumSpec {
  it should "check tx status" in new FlowFixture with Generators {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val networkType = networkSetting.networkType
    val serverUtils = new ServerUtils(networkType)

    val chainIndex                         = chainIndexGen.sample.get
    val fromGroup                          = chainIndex.from
    val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
    val fromAddress                        = Address.p2pkh(networkType, fromPublicKey)
    val (_, toPublicKey)                   = chainIndex.to.generateKey
    val toAddress                          = Address.p2pkh(networkType, toPublicKey)

    val buildTransaction = serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey, Address.p2pkh(networkType, toPublicKey), None, ALF.alf(1)))
      .rightValue

    val signature = SignatureSchema.sign(buildTransaction.txId.bytes, fromPrivateKey)
    val txTemplate =
      serverUtils
        .createTxTemplate(SendTransaction(buildTransaction.unsignedTx, signature))
        .rightValue
    serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE NotFound

    blockFlow.getPool(chainIndex).add(chainIndex, AVector(txTemplate -> 1.0))
    serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE MemPooled
    serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE Balance(genesisBalance, 1)
    serverUtils.getBalance(blockFlow, GetBalance(toAddress)) isE Balance(0, 0)

    val block0 = mineFromMemPool(blockFlow, chainIndex)
    block0.chainIndex is chainIndex
    addAndCheck(blockFlow, block0)
    serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
      Confirmed(block0.hash, 0, 1)
    if (chainIndex.isIntraGroup) {
      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(genesisBalance - ALF.alf(1) - block0.transactions.head.gasFeeUnsafe, 1)
      serverUtils.getBalance(blockFlow, GetBalance(toAddress)) isE Balance(ALF.alf(1), 1)
    } else {
      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE Balance(genesisBalance, 1)
      serverUtils.getBalance(blockFlow, GetBalance(toAddress)) isE Balance(0, 0)
    }

    val block1 = emptyBlock(blockFlow, ChainIndex(chainIndex.from, chainIndex.from))
    addAndCheck(blockFlow, block1)
    if (chainIndex.isIntraGroup) {
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 2)
      serverUtils.getBalance(blockFlow, GetBalance(toAddress)) isE Balance(ALF.alf(1), 1)
    } else {
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(toAddress)) isE Balance(0, 0)
    }
    serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
      Balance(genesisBalance - ALF.alf(1) - block0.transactions.head.gasFeeUnsafe, 1)

    val block2 = emptyBlock(blockFlow, ChainIndex(chainIndex.to, chainIndex.to))
    addAndCheck(blockFlow, block2)
    if (chainIndex.isIntraGroup) {
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 3)
    } else {
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1)
    }
    serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
      Balance(genesisBalance - ALF.alf(1) - block0.transactions.head.gasFeeUnsafe, 1)
    serverUtils.getBalance(blockFlow, GetBalance(toAddress)) isE Balance(ALF.alf(1), 1)
  }
}
