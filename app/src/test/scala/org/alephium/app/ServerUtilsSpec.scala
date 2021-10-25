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

import java.net.InetSocketAddress

import org.alephium.api.ApiError
import org.alephium.api.model._
import org.alephium.flow.FlowFixture
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.core.UtxoUtils
import org.alephium.protocol.{ALPH, Generators, Hash, PrivateKey, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util.{AlephiumSpec, AVector, Duration, SocketUtil, TimeStamp, U256}

class ServerUtilsSpec extends AlephiumSpec {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  trait ApiConfigFixture extends SocketUtil {
    val peerPort             = generatePort()
    val address              = new InetSocketAddress("127.0.0.1", peerPort)
    val blockflowFetchMaxAge = Duration.zero
    implicit val apiConfig: ApiConfig = ApiConfig(
      networkInterface = address.getAddress,
      blockflowFetchMaxAge = blockflowFetchMaxAge,
      askTimeout = Duration.ofMinutesUnsafe(1),
      None,
      ALPH.oneAlph
    )
  }

  trait Fixture extends FlowFixture with ApiConfigFixture {
    implicit def flowImplicit: BlockFlow = blockFlow
  }

  trait FlowFixtureWithApi extends FlowFixture with ApiConfigFixture

  it should "check tx status for intra group txs" in new Fixture {

    override val configValues = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils = new ServerUtils

    for {
      targetGroup <- 0 until groups0
    } {
      val chainIndex                         = ChainIndex.unsafe(targetGroup, targetGroup)
      val fromGroup                          = chainIndex.from
      val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
      val fromAddress                        = Address.p2pkh(fromPublicKey)
      val destination1                       = generateDestination(chainIndex)
      val destination2                       = generateDestination(chainIndex)

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

      val senderBalanceWithGas =
        genesisBalance - destination1.amount.value - destination2.amount.value

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

  it should "check tx status for inter group txs" in new FlowFixtureWithApi {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils = new ServerUtils

    for {
      from <- 0 until groups0
      to   <- 0 until groups0
      if from != to
    } {
      implicit val blockFlow                 = isolatedBlockFlow()
      val chainIndex                         = ChainIndex.unsafe(from, to)
      val fromGroup                          = chainIndex.from
      val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
      val fromAddress                        = Address.p2pkh(fromPublicKey)
      val destination1                       = generateDestination(chainIndex)
      val destination2                       = generateDestination(chainIndex)

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

      val senderBalanceWithGas =
        genesisBalance - destination1.amount.value - destination2.amount.value

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

    implicit val serverUtils = new ServerUtils

    for {
      targetGroup <- 0 until groups0
    } {
      val chainIndex                         = ChainIndex.unsafe(targetGroup, targetGroup)
      val fromGroup                          = chainIndex.from
      val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
      val fromAddress                        = Address.p2pkh(fromPublicKey)
      val selfDestination                    = Destination(fromAddress, Amount(ALPH.oneAlph), None)

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
      val sweepAllToAddress        = generateAddress(chainIndex)
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

  it should "check sweep all tx status for inter group txs" in new FlowFixtureWithApi {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils = new ServerUtils

    for {
      from <- 0 until groups0
      to   <- 0 until groups0
      if from != to
    } {
      implicit val blockFlow                 = isolatedBlockFlow()
      val chainIndex                         = ChainIndex.unsafe(from, to)
      val fromGroup                          = chainIndex.from
      val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
      val fromAddress                        = Address.p2pkh(fromPublicKey)
      val toGroup                            = chainIndex.to
      val (toPrivateKey, toPublicKey, _)     = genesisKeys(toGroup.value)
      val toAddress                          = Address.p2pkh(toPublicKey)
      val destination                        = Destination(toAddress, Amount(ALPH.oneAlph))

      info("Sending some coins to an address, resulting 10 UTXOs for its corresponding public key")
      val destinations = AVector.fill(10)(destination)
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

      val senderBalanceWithGas   = genesisBalance - ALPH.alph(10)
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
      checkAddressBalance(toAddress, receiverInitialBalance + ALPH.alph(10), 11)

      info("Sweep coins from the 3 UTXOs for the same public key to another address")
      val senderBalanceBeforeSweep = receiverInitialBalance + ALPH.alph(10)
      val sweepAllToAddress        = generateAddress(chainIndex)
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

      // Spend 10 UTXOs and generate 1 output
      sweepAllTxTemplate.unsigned.fixedOutputs.length is 1
      sweepAllTxTemplate.unsigned.gasAmount > minimalGas is true
      sweepAllTxTemplate.gasFeeUnsafe is defaultGasPrice * UtxoUtils.estimateSweepAllTxGas(11)

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

  "ServerUtils.decodeUnsignedTransaction" should "decode unsigned transaction" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex.from.value)
    val destination1          = generateDestination(chainIndex)
    val destination2          = generateDestination(chainIndex)
    val destinations          = AVector(destination1, destination2)

    val unsignedTx = serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = None,
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

  trait MultipleUtxos extends FlowFixtureWithApi {
    implicit val serverUtils = new ServerUtils

    implicit val bf                        = blockFlow
    val chainIndex                         = ChainIndex.unsafe(0, 0)
    val (fromPrivateKey, fromPublicKey, _) = genesisKeys(chainIndex.from.value)
    val fromAddress                        = Address.p2pkh(fromPublicKey)
    val selfDestination                    = Destination(fromAddress, Amount(ALPH.cent(50)))

    info("Sending some coins to itself, creating 2 UTXOs in total for the same public key")
    val selfDestinations = AVector(selfDestination)
    val buildTransaction = serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey, selfDestinations)
      )
      .rightValue
    val txTemplate = signAndAddToMemPool(
      buildTransaction.txId,
      buildTransaction.unsignedTx,
      chainIndex,
      fromPrivateKey
    )
    val fromAddressBalance = genesisBalance - txTemplate.gasFeeUnsafe
    checkAddressBalance(fromAddress, fromAddressBalance, 2)

    val utxos =
      serverUtils.getUTXOsIncludePool(blockFlow, fromAddress, Some(Int.MaxValue)).rightValue.utxos
    val destination1 = generateDestination(chainIndex)
    val destination2 = generateDestination(chainIndex)
    val destinations = AVector(destination1, destination2)

  }

  "ServerUtils.prepareUnsignedTransaction" should "create transaction with provided UTXOs" in new MultipleUtxos {
    val outputRefs = utxos.map { utxo =>
      OutputRef(utxo.ref.hint, utxo.ref.key)
    }

    noException should be thrownBy {
      serverUtils
        .prepareUnsignedTransaction(
          blockFlow,
          fromPublicKey,
          outputRefsOpt = Some(outputRefs),
          destinations,
          gasOpt = Some(minimalGas),
          defaultGasPrice
        )
        .rightValue
    }
  }

  it should "use default gas if Gas is not provided" in new MultipleUtxos {
    val outputRefs = utxos.map { utxo =>
      OutputRef(utxo.ref.hint, utxo.ref.key)
    }

    // ALPH.oneAlph is transferred to each destination
    val unsignedTx = serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = Some(outputRefs),
        destinations,
        gasOpt = None,
        defaultGasPrice
      )
      .rightValue

    val fromAddressBalanceAfterTransfer = {
      val defaultGas    = UtxoUtils.estimateGas(outputRefs.length, destinations.length + 1)
      val defaultGasFee = defaultGasPrice * defaultGas
      fromAddressBalance - ALPH.oneAlph.mulUnsafe(2) - defaultGasFee
    }

    unsignedTx.fixedOutputs.map(_.amount).toSeq should contain theSameElementsAs Seq(
      ALPH.oneAlph,
      ALPH.oneAlph,
      fromAddressBalanceAfterTransfer
    )
  }

  it should "validate unsigned transaction" in new Fixture with TxInputGenerators {

    val tooMuchGasFee = UnsignedTransaction(
      defaultTxVersion,
      NetworkId.AlephiumDevNet,
      None,
      minimalGas,
      GasPrice(ALPH.oneAlph),
      AVector(txInputGen.sample.get),
      AVector.empty
    )

    ServerUtils.validateUnsignedTransaction(tooMuchGasFee) is Left(
      ApiError.BadRequest(
        "Too much gas fee, cap at 1000000000000000000, got 20000000000000000000000"
      )
    )

    val noInputs = UnsignedTransaction(
      defaultTxVersion,
      NetworkId.AlephiumDevNet,
      None,
      minimalGas,
      defaultGasPrice,
      AVector.empty,
      AVector.empty
    )

    ServerUtils.validateUnsignedTransaction(noInputs) is Left(
      ApiError.BadRequest(
        "Invalid transaction: empty inputs"
      )
    )
  }

  it should "not create transaction with provided UTXOs, if Alph amount isn't enough" in new MultipleUtxos {
    val outputRefs = utxos.collect {
      case utxo if utxo.amount.value.equals(ALPH.cent(50)) =>
        OutputRef(utxo.ref.hint, utxo.ref.key)
    }

    outputRefs.length is 1

    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = Some(outputRefs),
        destinations,
        gasOpt = Some(minimalGas),
        defaultGasPrice
      )
      .leftValue
      .detail is "Not enough balance"
  }

  it should "not create transaction with empty provided UTXOs" in new MultipleUtxos {
    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = Some(AVector.empty),
        destinations,
        gasOpt = Some(minimalGas),
        defaultGasPrice
      )
      .leftValue
      .detail is "Empty UTXOs"
  }

  it should "not create transaction without enough gas" in new MultipleUtxos {
    val outputRefs = utxos.map { utxo =>
      OutputRef(utxo.ref.hint, utxo.ref.key)
    }

    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = Some(outputRefs),
        destinations,
        gasOpt = Some(GasBox.unsafe(100)),
        defaultGasPrice
      )
      .leftValue
      .detail is "Invalid gas GasBox(100), minimal GasBox(20000)"
  }

  it should "not create transaction when not all utxos are of asset type" in new MultipleUtxos {
    val outputRefs = utxos.map { utxo =>
      OutputRef(utxo.ref.hint & 10, utxo.ref.key)
    }

    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = Some(outputRefs),
        destinations,
        gasOpt = Some(minimalGas),
        defaultGasPrice
      )
      .leftValue
      .detail is "Selected UTXOs must be of asset type"
  }

  "ServerUtils.buildTransaction" should "fail when there is no output" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

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

  it should "fail when outputs belong to different groups" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex1           = ChainIndex.unsafe(0, 0)
    val chainIndex2           = ChainIndex.unsafe(0, 1)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex1.from.value)
    val destination1          = generateDestination(chainIndex1)
    val destination2          = generateDestination(chainIndex2)
    val destinations          = AVector(destination1, destination2)

    val buildTransaction = serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey, destinations)
      )
      .leftValue

    buildTransaction.detail is "Different groups for transaction outputs"
  }

  it should "return mempool statuses" in new Fixture with Generators {

    override val configValues = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils = new ServerUtils()

    val emptyMempool = serverUtils.listUnconfirmedTransactions(blockFlow)

    emptyMempool.rightValue is AVector.empty[UnconfirmedTransactions]

    val chainIndex                         = chainIndexGen.sample.get
    val fromGroup                          = chainIndex.from
    val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
    val destination                        = generateDestination(chainIndex)

    val buildTransaction = serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey, AVector(destination))
      )
      .rightValue

    val txTemplate = signAndAddToMemPool(
      buildTransaction.txId,
      buildTransaction.unsignedTx,
      chainIndex,
      fromPrivateKey
    )

    val txs = serverUtils.listUnconfirmedTransactions(blockFlow).rightValue

    txs is AVector(
      UnconfirmedTransactions(
        chainIndex.from.value,
        chainIndex.to.value,
        AVector(Tx.fromTemplate(txTemplate))
      )
    )
  }

  private def generateDestination(
      chainIndex: ChainIndex,
      tokens: (TokenId, U256)*
  )(implicit
      groupConfig: GroupConfig
  ): Destination = {
    val address = generateAddress(chainIndex)
    val amount  = Amount(ALPH.oneAlph)
    Destination(address, amount, Some(AVector.from(tokens).map(Token.apply.tupled)))
  }

  private def generateAddress(chainIndex: ChainIndex)(implicit
      groupConfig: GroupConfig
  ): Address.Asset = {
    val (_, toPublicKey) = chainIndex.to.generateKey
    Address.p2pkh(toPublicKey)
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
    serverUtils.getBalance(blockFlow, GetBalance(address, None)) isE Balance.from(
      Amount(amount),
      Amount.Zero,
      utxoNum
    )
  }

  private def checkDestinationBalance(destination: Destination, utxoNum: Int = 1)(implicit
      serverUtils: ServerUtils,
      blockFlow: BlockFlow
  ) = {
    checkAddressBalance(destination.address, destination.amount.value, utxoNum)
  }
}
