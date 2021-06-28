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
import org.alephium.flow.core.BlockFlow
import org.alephium.protocol.{ALF, Hash, PrivateKey, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, ChainIndex, NetworkType, TransactionTemplate}
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}

class ServerUtilsSpec extends AlephiumSpec {
  it should "check tx status for intra group txs" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val networkType          = networkSetting.networkType
    implicit val serverUtils = new ServerUtils(networkType)

    for {
      targetGroup <- 0 until groups0
    } {
      val chainIndex                         = ChainIndex.unsafe(targetGroup, targetGroup)
      val fromGroup                          = chainIndex.from
      val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
      val fromAddress                        = Address.p2pkh(networkType, fromPublicKey)
      val destination1                       = generateDestination(chainIndex, networkType)
      val destination2                       = generateDestination(chainIndex, networkType)

      val destinations = AVector(destination1, destination2)
      val buildTransaction = serverUtils
        .buildTransaction(
          blockFlow,
          BuildTransaction(fromPublicKey, destinations)
        )
        .rightValue

      val txTemplate = signAndAddToMemPool(
        buildTransaction.txId,
        buildTransaction.unsignedTx,
        chainIndex,
        fromPrivateKey,
        blockFlow
      )

      val senderBalanceWithGas = genesisBalance - destination1.amount - destination2.amount

      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(senderBalanceWithGas - txTemplate.gasFeeUnsafe, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(destination1.address)) isE
        Balance(destination1.amount, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(destination2.address)) isE
        Balance(destination2.amount, 0, 1)

      val block0 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 1, 1)
      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(destination1.address)) isE
        Balance(destination1.amount, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(destination2.address)) isE
        Balance(destination2.amount, 0, 1)

      val block1 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block1)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 2, 2, 2)
      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(destination1.address)) isE
        Balance(destination1.amount, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(destination2.address)) isE
        Balance(destination2.amount, 0, 1)
    }
  }

  it should "check tx status for inter group txs" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val networkType          = networkSetting.networkType
    implicit val serverUtils = new ServerUtils(networkType)

    for {
      from <- 0 until groups0
      to   <- 0 until groups0
      if from != to
    } {
      val blockFlow                          = isolatedBlockFlow()
      val chainIndex                         = ChainIndex.unsafe(from, to)
      val fromGroup                          = chainIndex.from
      val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
      val fromAddress                        = Address.p2pkh(networkType, fromPublicKey)
      val destination1                       = generateDestination(chainIndex, networkType)
      val destination2                       = generateDestination(chainIndex, networkType)

      val destinations = AVector(destination1, destination2)
      val buildTransaction = serverUtils
        .buildTransaction(
          blockFlow,
          BuildTransaction(fromPublicKey, destinations)
        )
        .rightValue

      val txTemplate = signAndAddToMemPool(
        buildTransaction.txId,
        buildTransaction.unsignedTx,
        chainIndex,
        fromPrivateKey,
        blockFlow
      )

      val senderBalanceWithGas = genesisBalance - destination1.amount - destination2.amount

      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(senderBalanceWithGas - txTemplate.gasFeeUnsafe, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(destination1.address)) isE Balance(0, 0, 0)
      serverUtils.getBalance(blockFlow, GetBalance(destination2.address)) isE Balance(0, 0, 0)

      val block0 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 0, 0)
      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(senderBalanceWithGas - txTemplate.gasFeeUnsafe, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(destination1.address)) isE
        Balance(0, 0, 0)
      serverUtils.getBalance(blockFlow, GetBalance(destination2.address)) isE
        Balance(0, 0, 0)

      val block1 = emptyBlock(blockFlow, ChainIndex(chainIndex.from, chainIndex.from))
      addAndCheck(blockFlow, block1)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 1, 0)
      serverUtils.getBalance(blockFlow, GetBalance(destination1.address)) isE
        Balance(destination1.amount, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(destination2.address)) isE
        Balance(destination2.amount, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe, 0, 1)

      val block2 = emptyBlock(blockFlow, ChainIndex(chainIndex.to, chainIndex.to))
      addAndCheck(blockFlow, block2)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 1, 1)
      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(destination1.address)) isE
        Balance(destination1.amount, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(destination2.address)) isE
        Balance(destination2.amount, 0, 1)
    }
  }

  it should "check sweep all tx status for intra group txs" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val networkType          = networkSetting.networkType
    implicit val serverUtils = new ServerUtils(networkType)

    for {
      targetGroup <- 0 until groups0
    } {
      val chainIndex                         = ChainIndex.unsafe(targetGroup, targetGroup)
      val fromGroup                          = chainIndex.from
      val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
      val fromAddress                        = Address.p2pkh(networkType, fromPublicKey)
      val selfDestination                    = Destination(fromAddress, ALF.oneAlf)

      info("Sending some coins to itself twice, creating 3 UTXOs in total for the same public key")
      val destinations = AVector(selfDestination, selfDestination)
      val buildTransaction = serverUtils
        .buildTransaction(
          blockFlow,
          BuildTransaction(fromPublicKey, destinations)
        )
        .rightValue

      val txTemplate = signAndAddToMemPool(
        buildTransaction.txId,
        buildTransaction.unsignedTx,
        chainIndex,
        fromPrivateKey,
        blockFlow
      )

      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(genesisBalance - txTemplate.gasFeeUnsafe, 0, 3)

      val block0 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 1, 1)
      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(genesisBalance - block0.transactions.head.gasFeeUnsafe, 0, 3)

      info("Sweep coins from the 3 UTXOs for this public key to another address")
      val senderBalanceBeforeSweep = genesisBalance - block0.transactions.head.gasFeeUnsafe
      val sweepAllToAddress        = generateAddress(chainIndex, networkType)
      val buildSweepAllTransaction = serverUtils
        .buildSweepAllTransaction(
          blockFlow,
          BuildSweepAllTransaction(fromPublicKey, sweepAllToAddress)
        )
        .rightValue

      val sweepAllTxTemplate = signAndAddToMemPool(
        buildSweepAllTransaction.txId,
        buildSweepAllTransaction.unsignedTx,
        chainIndex,
        fromPrivateKey,
        blockFlow
      )

      serverUtils.getBalance(blockFlow, GetBalance(sweepAllToAddress)) isE
        Balance(senderBalanceBeforeSweep - sweepAllTxTemplate.gasFeeUnsafe, 0, 1)

      val block1 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block1)
      serverUtils.getTransactionStatus(blockFlow, sweepAllTxTemplate.id, chainIndex) isE
        Confirmed(block1.hash, 0, 1, 1, 1)
      serverUtils.getBalance(blockFlow, GetBalance(sweepAllToAddress)) isE
        Balance(senderBalanceBeforeSweep - sweepAllTxTemplate.gasFeeUnsafe, 0, 1)
    }
  }

  it should "check sweep all tx status for inter group txs" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val networkType          = networkSetting.networkType
    implicit val serverUtils = new ServerUtils(networkType)

    for {
      from <- 0 until groups0
      to   <- 0 until groups0
      if from != to
    } {
      val blockFlow                          = isolatedBlockFlow()
      val chainIndex                         = ChainIndex.unsafe(from, to)
      val fromGroup                          = chainIndex.from
      val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
      val fromAddress                        = Address.p2pkh(networkType, fromPublicKey)
      val toGroup                            = chainIndex.to
      val (toPrivateKey @ _, toPublicKey, _) = genesisKeys(toGroup.value)
      val toAddress                          = Address.p2pkh(networkType, toPublicKey)
      val destination                        = Destination(toAddress, ALF.oneAlf)

      info("Sending some coins to a destination, creating 3 UTXOs in total for the same public key")
      val destinations = AVector(destination, destination)
      val buildTransaction = serverUtils
        .buildTransaction(
          blockFlow,
          BuildTransaction(fromPublicKey, destinations)
        )
        .rightValue

      val txTemplate = signAndAddToMemPool(
        buildTransaction.txId,
        buildTransaction.unsignedTx,
        chainIndex,
        fromPrivateKey,
        blockFlow
      )

      val senderBalanceWithGas   = genesisBalance - ALF.alf(2)
      val receiverInitialBalance = genesisBalance

      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(senderBalanceWithGas - txTemplate.gasFeeUnsafe, 0, 1)

      val block0 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 0, 0)
      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(toAddress)) isE
        Balance(receiverInitialBalance, 0, 1)

      val block1 = emptyBlock(blockFlow, ChainIndex(chainIndex.from, chainIndex.from))
      addAndCheck(blockFlow, block1)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 1, 0)
      serverUtils.getBalance(blockFlow, GetBalance(fromAddress)) isE
        Balance(senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe, 0, 1)
      serverUtils.getBalance(blockFlow, GetBalance(toAddress)) isE
        Balance(receiverInitialBalance + ALF.alf(2), 0, 3)

      info("Sweep coins from the 3 UTXOs for the same public key to another address")
      val senderBalanceBeforeSweep = receiverInitialBalance + ALF.alf(2)
      val sweepAllToAddress        = generateAddress(chainIndex, networkType)
      val buildSweepAllTransaction = serverUtils
        .buildSweepAllTransaction(
          blockFlow,
          BuildSweepAllTransaction(toPublicKey, sweepAllToAddress)
        )
        .rightValue

      val sweepAllChainIndex = ChainIndex(chainIndex.to, chainIndex.to)
      val sweepAllTxTemplate = signAndAddToMemPool(
        buildSweepAllTransaction.txId,
        buildSweepAllTransaction.unsignedTx,
        sweepAllChainIndex,
        fromPrivateKey,
        blockFlow
      )

      serverUtils.getBalance(blockFlow, GetBalance(sweepAllToAddress)) isE
        Balance(senderBalanceBeforeSweep - sweepAllTxTemplate.gasFeeUnsafe, 0, 1)

      val block2 = mineFromMemPool(blockFlow, sweepAllChainIndex)
      addAndCheck(blockFlow, block2)
      serverUtils.getBalance(blockFlow, GetBalance(sweepAllToAddress)) isE
        Balance(senderBalanceBeforeSweep - block2.transactions.head.gasFeeUnsafe, 0, 1)
    }
  }

  private def generateDestination(chainIndex: ChainIndex, networkType: NetworkType)(implicit
      groupConfig: GroupConfig
  ): Destination = {
    val address = generateAddress(chainIndex, networkType)
    val amount  = ALF.oneAlf
    Destination(address, amount)
  }

  private def generateAddress(chainIndex: ChainIndex, networkType: NetworkType)(implicit
      groupConfig: GroupConfig
  ): Address = {
    val (_, toPublicKey) = chainIndex.to.generateKey
    Address.p2pkh(networkType, toPublicKey)
  }

  private def signAndAddToMemPool(
      txId: Hash,
      unsignedTx: String,
      chainIndex: ChainIndex,
      fromPrivateKey: PrivateKey,
      blockFlow: BlockFlow
  )(implicit
      serverUtils: ServerUtils
  ): TransactionTemplate = {
    val signature = SignatureSchema.sign(txId.bytes, fromPrivateKey)
    val txTemplate =
      serverUtils
        .createTxTemplate(SubmitTransaction(unsignedTx, signature))
        .rightValue

    serverUtils.getTransactionStatus(blockFlow, txId, chainIndex) isE NotFound

    blockFlow.getMemPool(chainIndex).addToTxPool(chainIndex, AVector(txTemplate), TimeStamp.now())
    serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE MemPooled

    txTemplate
  }
}
