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
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp, U256}

class ServerUtilsSpec extends AlephiumSpec {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  trait Fixture extends FlowFixture {
    implicit def flowImplicit: BlockFlow = blockFlow
  }

  it should "check tx status for intra group txs" in new Fixture {

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
        fromPrivateKey
      )

      val senderBalanceWithGas = genesisBalance - destination1.amount - destination2.amount

      checkAddressBalance(fromAddress, senderBalanceWithGas - txTemplate.gasFeeUnsafe)
      checkDestinationBalance(destination1)
      checkDestinationBalance(destination2)

      val block0 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 1, 1)
      checkAddressBalance(fromAddress, senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe)
      checkDestinationBalance(destination1)
      checkDestinationBalance(destination2)

      val block1 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block1)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 2, 2, 2)
      checkAddressBalance(fromAddress, senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe)
      checkDestinationBalance(destination1)
      checkDestinationBalance(destination2)
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
      implicit val blockFlow                 = isolatedBlockFlow()
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
        fromPrivateKey
      )

      val senderBalanceWithGas = genesisBalance - destination1.amount - destination2.amount

      checkAddressBalance(fromAddress, senderBalanceWithGas - txTemplate.gasFeeUnsafe)
      checkAddressBalance(destination1.address, U256.unsafe(0), 0)
      checkAddressBalance(destination2.address, U256.unsafe(0), 0)

      val block0 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 0, 0)
      checkAddressBalance(fromAddress, senderBalanceWithGas - txTemplate.gasFeeUnsafe)
      checkAddressBalance(destination1.address, U256.unsafe(0), 0)
      checkAddressBalance(destination2.address, U256.unsafe(0), 0)

      val block1 = emptyBlock(blockFlow, ChainIndex(chainIndex.from, chainIndex.from))
      addAndCheck(blockFlow, block1)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 1, 0)
      checkAddressBalance(fromAddress, senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe)
      checkDestinationBalance(destination1)
      checkDestinationBalance(destination2)

      val block2 = emptyBlock(blockFlow, ChainIndex(chainIndex.to, chainIndex.to))
      addAndCheck(blockFlow, block2)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 1, 1)
      checkAddressBalance(fromAddress, senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe)
      checkDestinationBalance(destination1)
      checkDestinationBalance(destination2)
    }
  }

  it should "check sweep all tx status for intra group txs" in new Fixture {
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
        fromPrivateKey
      )

      checkAddressBalance(fromAddress, genesisBalance - txTemplate.gasFeeUnsafe, 3)

      val block0 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 1, 1)
      checkAddressBalance(fromAddress, genesisBalance - block0.transactions.head.gasFeeUnsafe, 3)

      info("Sweep coins from the 3 UTXOs of this public key to another address")
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
        fromPrivateKey
      )

      checkAddressBalance(
        sweepAllToAddress,
        senderBalanceBeforeSweep - sweepAllTxTemplate.gasFeeUnsafe
      )

      val block1 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block1)
      serverUtils.getTransactionStatus(blockFlow, sweepAllTxTemplate.id, chainIndex) isE
        Confirmed(block1.hash, 0, 1, 1, 1)
      checkAddressBalance(
        sweepAllToAddress,
        senderBalanceBeforeSweep - sweepAllTxTemplate.gasFeeUnsafe
      )
      checkAddressBalance(fromAddress, U256.unsafe(0), 0)
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
      implicit val blockFlow                 = isolatedBlockFlow()
      val chainIndex                         = ChainIndex.unsafe(from, to)
      val fromGroup                          = chainIndex.from
      val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
      val fromAddress                        = Address.p2pkh(networkType, fromPublicKey)
      val toGroup                            = chainIndex.to
      val (toPrivateKey, toPublicKey, _)     = genesisKeys(toGroup.value)
      val toAddress                          = Address.p2pkh(networkType, toPublicKey)
      val destination                        = Destination(toAddress, ALF.oneAlf)

      info("Sending some coins to an address, resulting 3 UTXOs for its corresponding public key")
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
        fromPrivateKey
      )

      val senderBalanceWithGas   = genesisBalance - ALF.alf(2)
      val receiverInitialBalance = genesisBalance

      checkAddressBalance(fromAddress, senderBalanceWithGas - txTemplate.gasFeeUnsafe)

      val block0 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 0, 0)
      checkAddressBalance(fromAddress, senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe)
      checkAddressBalance(toAddress, receiverInitialBalance)

      val block1 = emptyBlock(blockFlow, ChainIndex(chainIndex.from, chainIndex.from))
      addAndCheck(blockFlow, block1)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 1, 0)
      checkAddressBalance(fromAddress, senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe)
      checkAddressBalance(toAddress, receiverInitialBalance + ALF.alf(2), 3)

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
        toPrivateKey
      )

      checkAddressBalance(
        sweepAllToAddress,
        senderBalanceBeforeSweep - sweepAllTxTemplate.gasFeeUnsafe
      )

      val block2 = mineFromMemPool(blockFlow, sweepAllChainIndex)
      addAndCheck(blockFlow, block2)
      checkAddressBalance(
        sweepAllToAddress,
        senderBalanceBeforeSweep - block2.transactions.head.gasFeeUnsafe
      )
      checkAddressBalance(toAddress, 0, 0)
    }
  }

  "ServerUtils.decodeUnsignedTransaction" should "decode unsigned transaction" in new FlowFixture {
    val networkType = networkSetting.networkType
    val serverUtils = new ServerUtils(networkType)

    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex.from.value)
    val destination1          = generateDestination(chainIndex, networkType)
    val destination2          = generateDestination(chainIndex, networkType)
    val destinations          = AVector(destination1, destination2)

    val unsignedTx = serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        destinations,
        gasOpt = None,
        defaultGasPrice
      )
      .rightValue

    val buildTransaction = serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey, destinations)
      )
      .rightValue

    val decodedUnsignedTx =
      serverUtils.decodeUnsignedTransaction(buildTransaction.unsignedTx).rightValue

    decodedUnsignedTx is unsignedTx
  }

  "ServerUtils.buildTransaction" should "fail when there is no output" in new FlowFixture {
    val networkType = networkSetting.networkType
    val serverUtils = new ServerUtils(networkType)

    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex.from.value)
    val destinations          = AVector.empty[Destination]

    val buildTransaction = serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey, destinations)
      )
      .leftValue

    buildTransaction.detail is "Zero transaction outputs"
  }

  it should "fail when outputs belong to different groups" in new FlowFixture {
    val networkType = networkSetting.networkType
    val serverUtils = new ServerUtils(networkType)

    val chainIndex1           = ChainIndex.unsafe(0, 0)
    val chainIndex2           = ChainIndex.unsafe(0, 1)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex1.from.value)
    val destination1          = generateDestination(chainIndex1, networkType)
    val destination2          = generateDestination(chainIndex2, networkType)
    val destinations          = AVector(destination1, destination2)

    val buildTransaction = serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey, destinations)
      )
      .leftValue

    buildTransaction.detail is "Different groups for transaction outputs"
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
  ): Address.Asset = {
    val (_, toPublicKey) = chainIndex.to.generateKey
    Address.p2pkh(networkType, toPublicKey)
  }

  private def signAndAddToMemPool(
      txId: Hash,
      unsignedTx: String,
      chainIndex: ChainIndex,
      fromPrivateKey: PrivateKey
  )(implicit
      serverUtils: ServerUtils,
      blockFlow: BlockFlow
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

  private def checkAddressBalance(address: Address.Asset, amount: U256, utxoNum: Int = 1)(implicit
      serverUtils: ServerUtils,
      blockFlow: BlockFlow
  ) = {
    serverUtils.getBalance(blockFlow, GetBalance(address)) isE Balance(
      amount,
      U256.unsafe(0),
      utxoNum
    )
  }

  private def checkDestinationBalance(destination: Destination, utxoNum: Int = 1)(implicit
      serverUtils: ServerUtils,
      blockFlow: BlockFlow
  ) = {
    checkAddressBalance(destination.address, destination.amount, utxoNum)
  }
}
