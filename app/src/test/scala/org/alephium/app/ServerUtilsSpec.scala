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

import scala.util.Random

import akka.util.ByteString

import org.alephium.api.{model => api}
import org.alephium.api.ApiError
import org.alephium.api.model.{Transaction => _, TransactionTemplate => _, _}
import org.alephium.flow.FlowFixture
import org.alephium.flow.core.{AMMContract, BlockFlow}
import org.alephium.flow.gasestimation._
import org.alephium.protocol._
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model.{AssetOutput => _, ContractOutput => _, _}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, UnlockScript}
import org.alephium.ralph.Compiler
import org.alephium.serde.serialize
import org.alephium.util._

// scalastyle:off file.size.limit
class ServerUtilsSpec extends AlephiumSpec {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  val defaultUtxosLimit: Int                         = ALPH.MaxTxInputNum * 2

  trait ApiConfigFixture extends SocketUtil {
    val peerPort             = generatePort()
    val address              = new InetSocketAddress("127.0.0.1", peerPort)
    val blockflowFetchMaxAge = Duration.zero
    implicit val apiConfig: ApiConfig = ApiConfig(
      networkInterface = address.getAddress,
      blockflowFetchMaxAge = blockflowFetchMaxAge,
      askTimeout = Duration.ofMinutesUnsafe(1),
      None,
      ALPH.oneAlph,
      defaultUtxosLimit
    )
  }

  trait Fixture extends FlowFixture with ApiConfigFixture {
    implicit def flowImplicit: BlockFlow = blockFlow

    def emptyKey(index: Int): Hash = TxOutputRef.key(TransactionId.zero, index).value
  }

  trait GetTxFixture {
    def brokerConfig: BrokerConfig
    def serverUtils: ServerUtils

    def checkTx(blockFlow: BlockFlow, tx: Transaction, chainIndex: ChainIndex) = {
      val result = api.Transaction.fromProtocol(tx)
      serverUtils.getTransaction(
        blockFlow,
        tx.id,
        Some(chainIndex.from),
        Some(chainIndex.to)
      ) isE result
      serverUtils.getTransaction(blockFlow, tx.id, Some(chainIndex.from), None) isE result
      serverUtils.getTransaction(blockFlow, tx.id, None, Some(chainIndex.to)) isE result
      serverUtils.getTransaction(blockFlow, tx.id, None, None) isE result
      val invalidChainIndex = brokerConfig.chainIndexes.filter(_.from != chainIndex.from).head
      serverUtils
        .getTransaction(blockFlow, tx.id, Some(invalidChainIndex.from), None)
        .leftValue
        .detail is s"Transaction ${tx.id.toHexString} not found"
    }
  }

  trait FlowFixtureWithApi extends FlowFixture with ApiConfigFixture

  it should "send message with tx" in new Fixture {
    implicit val serverUtils = new ServerUtils

    val (_, fromPublicKey, _) = genesisKeys(0)
    val message               = Hex.unsafe("FFFF")
    val destination           = generateDestination(ChainIndex.unsafe(0, 1), message)
    val buildTransaction = serverUtils
      .buildTransaction(blockFlow, BuildTransaction(fromPublicKey, AVector(destination)))
      .rightValue
    val unsignedTransaction =
      serverUtils.decodeUnsignedTransaction(buildTransaction.unsignedTx).rightValue

    unsignedTransaction.fixedOutputs.head.additionalData is message
  }

  it should "check tx status for intra group txs" in new Fixture with GetTxFixture {

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
        genesisBalance - destination1.attoAlphAmount.value - destination2.attoAlphAmount.value

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

      checkTx(blockFlow, block0.nonCoinbase.head, chainIndex)

      val block1 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block1)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 2, 2, 2)
      checkAddressBalance(fromAddress, senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe)
      checkDestinationBalance(destination1)
      checkDestinationBalance(destination2)
    }
  }

  it should "check tx status for inter group txs" in new FlowFixtureWithApi with GetTxFixture {
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
        genesisBalance - destination1.attoAlphAmount.value - destination2.attoAlphAmount.value

      checkAddressBalance(fromAddress, senderBalanceWithGas - txTemplate.gasFeeUnsafe)
      checkAddressBalance(destination1.address, ALPH.oneAlph, 1)
      checkAddressBalance(destination2.address, ALPH.oneAlph, 1)

      val block0 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 0, 0)
      checkAddressBalance(fromAddress, senderBalanceWithGas - txTemplate.gasFeeUnsafe)
      checkAddressBalance(destination1.address, ALPH.oneAlph, 1)
      checkAddressBalance(destination2.address, ALPH.oneAlph, 1)

      checkTx(blockFlow, block0.nonCoinbase.head, chainIndex)

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

  it should "check sweep address tx status for intra group txs" in new Fixture with GetTxFixture {
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

      checkTx(blockFlow, block0.nonCoinbase.head, chainIndex)

      info("Sweep coins from the 3 UTXOs of this public key to another address")
      val senderBalanceBeforeSweep = genesisBalance - block0.transactions.head.gasFeeUnsafe
      val sweepAddressDestination  = generateAddress(chainIndex)
      val buildSweepAddressTransactionsRes = serverUtils
        .buildSweepAddressTransactions(
          blockFlow,
          BuildSweepAddressTransactions(fromPublicKey, sweepAddressDestination)
        )
        .rightValue
      val sweepAddressTransaction = buildSweepAddressTransactionsRes.unsignedTxs.head

      val sweepAddressTxTemplate = signAndAddToMemPool(
        sweepAddressTransaction.txId,
        sweepAddressTransaction.unsignedTx,
        chainIndex,
        fromPrivateKey
      )

      checkAddressBalance(
        sweepAddressDestination,
        senderBalanceBeforeSweep - sweepAddressTxTemplate.gasFeeUnsafe
      )

      val block1 = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block1)
      serverUtils.getTransactionStatus(blockFlow, sweepAddressTxTemplate.id, chainIndex) isE
        Confirmed(block1.hash, 0, 1, 1, 1)
      checkAddressBalance(
        sweepAddressDestination,
        senderBalanceBeforeSweep - sweepAddressTxTemplate.gasFeeUnsafe
      )
      checkAddressBalance(fromAddress, U256.unsafe(0), 0)
    }
  }

  it should "check sweep all tx status for inter group txs" in new FlowFixtureWithApi
    with GetTxFixture {
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
      checkAddressBalance(toAddress, receiverInitialBalance.addUnsafe(ALPH.alph(10)), 11)

      checkTx(blockFlow, block0.nonCoinbase.head, chainIndex)

      val block1 = emptyBlock(blockFlow, ChainIndex(chainIndex.from, chainIndex.from))
      addAndCheck(blockFlow, block1)
      serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE
        Confirmed(block0.hash, 0, 1, 1, 0)
      checkAddressBalance(fromAddress, senderBalanceWithGas - block0.transactions.head.gasFeeUnsafe)
      checkAddressBalance(toAddress, receiverInitialBalance + ALPH.alph(10), 11)

      info("Sweep coins from the 3 UTXOs for the same public key to another address")
      val senderBalanceBeforeSweep = receiverInitialBalance + ALPH.alph(10)
      val sweepAddressDestination  = generateAddress(chainIndex)
      val buildSweepAddressTransactionsRes = serverUtils
        .buildSweepAddressTransactions(
          blockFlow,
          BuildSweepAddressTransactions(toPublicKey, sweepAddressDestination)
        )
        .rightValue
      val sweepAddressTransaction = buildSweepAddressTransactionsRes.unsignedTxs.head

      val sweepAddressChainIndex = ChainIndex(chainIndex.to, chainIndex.to)
      val sweepAddressTxTemplate = signAndAddToMemPool(
        sweepAddressTransaction.txId,
        sweepAddressTransaction.unsignedTx,
        sweepAddressChainIndex,
        toPrivateKey
      )

      // Spend 10 UTXOs and generate 1 output
      sweepAddressTxTemplate.unsigned.fixedOutputs.length is 1
      sweepAddressTxTemplate.unsigned.gasAmount > minimalGas is true
      sweepAddressTxTemplate.gasFeeUnsafe is nonCoinbaseMinGasPrice *
        GasEstimation.sweepAddress(11, 1)

      checkAddressBalance(
        sweepAddressDestination,
        senderBalanceBeforeSweep - sweepAddressTxTemplate.gasFeeUnsafe
      )

      val block2 = mineFromMemPool(blockFlow, sweepAddressChainIndex)
      addAndCheck(blockFlow, block2)
      checkAddressBalance(
        sweepAddressDestination,
        senderBalanceBeforeSweep - block2.transactions.head.gasFeeUnsafe
      )
      checkAddressBalance(toAddress, 0, 0)
    }
  }

  trait PrepareTxWithTargetBlockHash extends FlowFixtureWithApi {
    val serverUtils           = new ServerUtils
    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex.from.value)
    val block0                = transfer(blockFlow, chainIndex)
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, chainIndex)
    addAndCheck(blockFlow, block1)

    val destinations = AVector(generateDestination(chainIndex))

    def generateTx(targetBlockHash: Option[BlockHash]): UnsignedTransaction

    val tx0 = generateTx(Some(block0.hash))
    val tx1 = generateTx(Some(block1.hash))
    val tx2 = generateTx(None)
    tx0.inputs.head is block0.transactions.head.unsigned.inputs.head
    tx1.inputs.head is block1.transactions.head.unsigned.inputs.head
    tx2.inputs.head isnot tx1.inputs.head
  }

  it should "use target block hash to prepare transfer tx" in new PrepareTxWithTargetBlockHash {
    def generateTx(targetBlockHash: Option[BlockHash]) = {
      serverUtils
        .prepareUnsignedTransaction(
          blockFlow,
          LockupScript.p2pkh(fromPublicKey),
          UnlockScript.p2pkh(fromPublicKey),
          None,
          destinations,
          None,
          nonCoinbaseMinGasPrice,
          targetBlockHash
        )
        .rightValue
    }
  }

  it should "use target block hash for sweep tx" in new PrepareTxWithTargetBlockHash {
    def generateTx(targetBlockHash: Option[BlockHash]): UnsignedTransaction = {
      val txs = serverUtils
        .prepareSweepAddressTransaction(
          blockFlow,
          fromPublicKey,
          destinations.head.address,
          None,
          None,
          nonCoinbaseMinGasPrice,
          targetBlockHash
        )
        .rightValue
      txs.length is 1
      txs.head
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
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None
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
      serverUtils.getUTXOsIncludePool(blockFlow, fromAddress).rightValue.utxos
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
          nonCoinbaseMinGasPrice,
          targetBlockHashOpt = None
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
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None
      )
      .rightValue

    val fromAddressBalanceAfterTransfer = {
      val fromLockupScript    = LockupScript.p2pkh(fromPublicKey)
      val outputLockupScripts = fromLockupScript +: destinations.map(_.address.lockupScript)
      val defaultGas =
        GasEstimation.estimateWithP2PKHInputs(outputRefs.length, outputLockupScripts.length)
      val defaultGasFee = nonCoinbaseMinGasPrice * defaultGas
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
      DefaultTxVersion,
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
      DefaultTxVersion,
      NetworkId.AlephiumDevNet,
      None,
      minimalGas,
      nonCoinbaseMinGasPrice,
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
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None
      )
      .leftValue
      .detail is "Not enough balance"
  }

  it should "not create transaction with provided UTXOs, if they are not from the same group" in new MultipleUtxos {
    utxos.length is 2

    val outputRefs = AVector(
      OutputRef(3, utxos(1).ref.key),
      OutputRef(1, utxos(0).ref.key)
    )

    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = Some(outputRefs),
        destinations,
        gasOpt = Some(minimalGas),
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None
      )
      .leftValue
      .detail is "Selected UTXOs are not from the same group"
  }

  it should "not create transaction with empty provided UTXOs" in new MultipleUtxos {
    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = Some(AVector.empty),
        destinations,
        gasOpt = Some(minimalGas),
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None
      )
      .leftValue
      .detail is "Empty UTXOs"
  }

  it should "not create transaction with invalid gas amount" in new MultipleUtxos {
    val outputRefs = utxos.map { utxo =>
      OutputRef(utxo.ref.hint, utxo.ref.key)
    }

    info("Gas amount too small")
    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = Some(outputRefs),
        destinations,
        gasOpt = Some(GasBox.unsafe(100)),
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None
      )
      .leftValue
      .detail is "Provided gas GasBox(100) too small, minimal GasBox(20000)"

    info("Gas amount too large")
    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = Some(outputRefs),
        destinations,
        gasOpt = Some(GasBox.unsafe(625001)),
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None
      )
      .leftValue
      .detail is "Provided gas GasBox(625001) too large, maximal GasBox(625000)"
  }

  it should "not create transaction with invalid gas price" in new MultipleUtxos {
    val outputRefs = utxos.map { utxo =>
      OutputRef(utxo.ref.hint, utxo.ref.key)
    }

    info("Gas price too small")
    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = Some(outputRefs),
        destinations,
        gasOpt = Some(minimalGas),
        GasPrice(coinbaseGasPrice.value - 1),
        targetBlockHashOpt = None
      )
      .leftValue
      .detail is "Gas price GasPrice(999999999) too small, minimal GasPrice(1000000000)"

    info("Gas price too large")
    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = Some(outputRefs),
        destinations,
        gasOpt = Some(minimalGas),
        GasPrice(ALPH.MaxALPHValue),
        targetBlockHashOpt = None
      )
      .leftValue
      .detail is "Gas price GasPrice(1000000000000000000000000000) too large, maximal GasPrice(999999999999999999999999999)"
  }

  it should "not create transaction with overflowing ALPH amount" in new MultipleUtxos {
    val attoAlphAmountOverflowDestinations = AVector(
      destination1,
      destination2.copy(attoAlphAmount = Amount(ALPH.MaxALPHValue))
    )
    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = None,
        attoAlphAmountOverflowDestinations,
        gasOpt = Some(minimalGas),
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None
      )
      .leftValue
      .detail is "ALPH amount overflow"
  }

  it should "not create transaction when with token amount overflow" in new MultipleUtxos {
    val tokenId = TokenId.hash("token1")
    val tokenAmountOverflowDestinations = AVector(
      destination1.copy(tokens = Some(AVector(Token(tokenId, U256.MaxValue)))),
      destination2.copy(tokens = Some(AVector(Token(tokenId, U256.One))))
    )
    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = None,
        tokenAmountOverflowDestinations,
        gasOpt = Some(minimalGas),
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None
      )
      .leftValue
      .detail is s"Amount overflow for token $tokenId"
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
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None
      )
      .leftValue
      .detail is "Selected UTXOs must be of asset type"
  }

  "ServerUtils.buildTransaction" should "fail with invalid number of outputs" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex.from.value)
    val emptyDestinations     = AVector.empty[Destination]

    info("Output number is zero")
    serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey, emptyDestinations)
      )
      .leftValue
      .detail is "Zero transaction outputs"

    info("Too many outputs")
    val tooManyDestinations = AVector.fill(ALPH.MaxTxOutputNum + 1)(generateDestination(chainIndex))
    serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey, tooManyDestinations)
      )
      .leftValue
      .detail is "Too many transaction outputs, maximal value: 256"
  }

  it should "check the minimal amount deposit for contract creation" in new Fixture {
    val serverUtils = new ServerUtils
    serverUtils.getInitialAttoAlphAmount(None) isE minimalAlphInContract
    serverUtils.getInitialAttoAlphAmount(
      Some(minimalAlphInContract)
    ) isE minimalAlphInContract
    serverUtils
      .getInitialAttoAlphAmount(Some(minimalAlphInContract - 1))
      .leftValue
      .detail is "Expect 1 ALPH deposit to deploy a new contract"
  }

  it should "fail when outputs belong to different groups" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex1           = ChainIndex.unsafe(0, 1)
    val chainIndex2           = ChainIndex.unsafe(0, 2)
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

    val emptyMempool = serverUtils.listUnconfirmedTransactions(blockFlow).rightValue
    emptyMempool is AVector.empty[UnconfirmedTransactions]

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
        AVector(api.TransactionTemplate.fromProtocol(txTemplate))
      )
    )
  }

  trait CallContractFixture extends Fixture {
    val chainIndex    = ChainIndex.unsafe(0, 0)
    val lockupScript  = getGenesisLockupScript(chainIndex)
    val callerAddress = Address.Asset(lockupScript)
    val inputAsset    = TestInputAsset(callerAddress, AssetState(ALPH.oneAlph))
    val serverUtils   = new ServerUtils()

    def executeScript(script: vm.StatefulScript) = {
      val block = payableCall(blockFlow, chainIndex, script)
      addAndCheck(blockFlow, block)
      block
    }

    def createContract(code: String, mutFields: AVector[vm.Val]): (Block, ContractId) = {
      val contract = Compiler.compileContract(code).rightValue
      val script =
        contractCreation(contract, AVector.empty, mutFields, lockupScript, minimalAlphInContract)
      val block      = executeScript(script)
      val contractId = ContractId.from(block.transactions.head.id, 0, chainIndex.from)
      (block, contractId)
    }

    val barCode =
      s"""
         |Contract Bar(mut value: U256) {
         |  @using(updateFields = true)
         |  pub fn addOne() -> () {
         |    value = value + 1
         |  }
         |}
         |""".stripMargin

    val (_, barId) = createContract(barCode, AVector[vm.Val](vm.Val.U256(U256.Zero)))
    val barAddress = Address.contract(barId)
    val fooCode =
      s"""
         |Contract Foo(mut value: U256) {
         |  @using(preapprovedAssets = true, assetsInContract = true, updateFields = true)
         |  pub fn addOne() -> U256 {
         |    transferTokenToSelf!(@$callerAddress, ALPH, ${ALPH.oneNanoAlph})
         |    value = value + 1
         |    let bar = Bar(#${barId.toHexString})
         |    bar.addOne()
         |    return value
         |  }
         |}
         |
         |$barCode
         |""".stripMargin

    val (createContractBlock, fooId) =
      createContract(fooCode, AVector[vm.Val](vm.Val.U256(U256.Zero)))
    val fooAddress = Address.contract(fooId)
    val callScriptCode =
      s"""
         |@using(preapprovedAssets = true)
         |TxScript Main {
         |  let foo = Foo(#${fooId.toHexString})
         |  foo.addOne{@$callerAddress -> ALPH: 1 alph}()
         |}
         |
         |$fooCode
         |""".stripMargin
    val callScript = Compiler.compileTxScript(callScriptCode).rightValue

    def checkContractStates(contractId: ContractId, value: U256, attoAlphAmount: U256) = {
      val worldState    = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
      val contractState = worldState.getContractState(contractId).rightValue
      contractState.mutFields is AVector[vm.Val](vm.Val.U256(value))
      val contractOutput = worldState.getContractAsset(contractState.contractOutputRef).rightValue
      contractOutput.amount is attoAlphAmount
    }
  }

  it should "call contract" in new CallContractFixture {
    executeScript(callScript)
    checkContractStates(barId, U256.unsafe(1), minimalAlphInContract)
    checkContractStates(fooId, U256.unsafe(1), minimalAlphInContract + ALPH.oneNanoAlph)

    info("call contract against the latest world state")
    val params0 = CallContract(
      group = chainIndex.from.value,
      address = fooAddress,
      methodIndex = 0,
      inputAssets = Some(AVector(inputAsset)),
      existingContracts = Some(AVector(barAddress))
    )
    val callContractResult0 = serverUtils.callContract(blockFlow, params0).rightValue
    callContractResult0.returns is AVector[Val](ValU256(2))
    callContractResult0.gasUsed is 23198
    callContractResult0.txOutputs.length is 2
    val contractAttoAlphAmount0 = minimalAlphInContract + ALPH.nanoAlph(2)
    callContractResult0.txOutputs(0).attoAlphAmount.value is contractAttoAlphAmount0

    callContractResult0.contracts.length is 2
    val barState0 = callContractResult0.contracts(0)
    barState0.immFields is AVector.empty[Val]
    barState0.mutFields is AVector[Val](ValU256(2))
    barState0.address is barAddress
    barState0.asset is AssetState(ALPH.oneAlph, Some(AVector.empty))
    val fooState0 = callContractResult0.contracts(1)
    barState0.immFields is AVector.empty[Val]
    fooState0.mutFields is AVector[Val](ValU256(2))
    fooState0.address is fooAddress
    fooState0.asset is AssetState(contractAttoAlphAmount0, Some(AVector.empty))

    info("call contract against the old world state")
    val params1             = params0.copy(worldStateBlockHash = Some(createContractBlock.hash))
    val callContractResult1 = serverUtils.callContract(blockFlow, params1).rightValue
    callContractResult1.returns is AVector[Val](ValU256(1))
    callContractResult1.gasUsed is 23198
    callContractResult1.txOutputs.length is 2
    val contractAttoAlphAmount1 = minimalAlphInContract + ALPH.oneNanoAlph
    callContractResult1.txOutputs(0).attoAlphAmount.value is contractAttoAlphAmount1

    callContractResult1.contracts.length is 2
    val barState1 = callContractResult1.contracts(0)
    barState1.immFields is AVector.empty[Val]
    barState1.mutFields is AVector[Val](ValU256(1))
    barState1.address is barAddress
    barState1.asset is AssetState(ALPH.oneAlph, Some(AVector.empty))
    val fooState1 = callContractResult1.contracts(1)
    barState1.immFields is AVector.empty[Val]
    fooState1.mutFields is AVector[Val](ValU256(1))
    fooState1.address is fooAddress
    fooState1.asset is AssetState(contractAttoAlphAmount1, Some(AVector.empty))
  }

  "the test contract endpoint" should "handle create and destroy contracts properly" in new Fixture {
    val groupIndex   = brokerConfig.chainIndexes.sample().from
    val (_, pubKey)  = SignatureSchema.generatePriPub()
    val assetAddress = Address.Asset(LockupScript.p2pkh(pubKey))
    val foo =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn destroy() -> () {
         |    destroySelf!(@$assetAddress)
         |  }
         |}
         |""".stripMargin

    val fooContract         = Compiler.compileContract(foo).rightValue
    val fooByteCode         = Hex.toHexString(serialize(fooContract))
    val createContractPath  = "00"
    val destroyContractPath = "11"
    val bar =
      s"""
         |Contract Bar() {
         |  @using(assetsInContract = true)
         |  pub fn bar() -> () {
         |    createSubContract!{selfAddress!() -> ALPH: 1 alph}(#$createContractPath, #$fooByteCode, #00, #00)
         |    Foo(subContractId!(#$destroyContractPath)).destroy()
         |  }
         |}
         |
         |$foo
         |""".stripMargin

    val barContract   = Compiler.compileContract(bar).rightValue
    val barContractId = ContractId.random
    val destroyedFooContractId =
      barContractId.subContractId(Hex.unsafe(destroyContractPath), groupIndex)
    val existingContract = ContractState(
      Address.contract(destroyedFooContractId),
      fooContract,
      fooContract.hash,
      None,
      AVector.empty[Val],
      AVector.empty[Val],
      AssetState(ALPH.oneAlph)
    )
    val testContractParams = TestContract(
      group = Some(groupIndex.value),
      address = Some(Address.contract(barContractId)),
      bytecode = barContract,
      initialAsset = Some(AssetState(ALPH.alph(10))),
      existingContracts = Some(AVector(existingContract)),
      inputAssets = Some(AVector(TestInputAsset(assetAddress, AssetState(ALPH.oneAlph))))
    ).toComplete().rightValue

    val testFlow    = BlockFlow.emptyUnsafe(config)
    val serverUtils = new ServerUtils()
    val createdFooContractId =
      barContractId.subContractId(
        Hex.unsafe(createContractPath),
        ChainIndex.unsafe(testContractParams.group, testContractParams.group).from
      )

    val result =
      serverUtils.runTestContract(testFlow, testContractParams).rightValue
    result.contracts.length is 2
    result.contracts(0).address is Address.contract(createdFooContractId)
    result.contracts(1).address is Address.contract(barContractId)
    val assetOutput = result.txOutputs(1)
    assetOutput.address is assetAddress
    assetOutput.attoAlphAmount is Amount(
      ALPH.alph(2).subUnsafe(nonCoinbaseMinGasPrice * maximalGasPerTx)
    )
  }

  trait DestroyFixture extends Fixture {
    val (_, pubKey)  = SignatureSchema.generatePriPub()
    val assetAddress = Address.Asset(LockupScript.p2pkh(pubKey))

    val fooContractId = ContractId.random
    val foo =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn destroy(address: Address) -> () {
         |    destroySelf!(address)
         |  }
         |}
         |""".stripMargin
    val fooContract = Compiler.compileContract(foo).rightValue

    val fooCallerContractId = ContractId.random
    def fooCaller: String
    val fooCallerContract = Compiler.compileContract(fooCaller).rightValue

    val bar =
      s"""
         |Contract Bar() {
         |  pub fn bar() -> () {
         |    FooCaller(#${fooCallerContractId.toHexString}).destroyFoo()
         |  }
         |}
         |
         |$fooCaller
         |""".stripMargin

    val barContract   = Compiler.compileContract(bar).rightValue
    val barContractId = ContractId.random
    val existingContracts = AVector(
      ContractState(
        Address.contract(fooCallerContractId),
        fooCallerContract,
        fooCallerContract.hash,
        None,
        AVector(ValByteVec(fooContractId.bytes)),
        AVector.empty,
        AssetState(ALPH.oneAlph)
      ),
      ContractState(
        Address.contract(fooContractId),
        fooContract,
        fooContract.hash,
        None,
        AVector.empty,
        AVector.empty,
        AssetState(ALPH.oneAlph)
      )
    )
    val testContractParams = TestContract(
      address = Some(Address.contract(barContractId)),
      bytecode = barContract,
      initialAsset = Some(AssetState(ALPH.alph(10))),
      existingContracts = Some(existingContracts),
      inputAssets = Some(AVector(TestInputAsset(assetAddress, AssetState(ALPH.oneAlph))))
    )

    val testFlow    = BlockFlow.emptyUnsafe(config)
    val serverUtils = new ServerUtils()
  }

  it should "successfully destroy contracts and transfer fund to calling address" in new DestroyFixture {
    override def fooCaller: String =
      s"""
         |Contract FooCaller(fooId: ByteVec) {
         |  @using(assetsInContract = true)
         |  pub fn destroyFoo() -> () {
         |    let foo = Foo(fooId)
         |    foo.destroy(selfAddress!())
         |  }
         |}
         |
         |$foo
         |""".stripMargin

    val result = serverUtils
      .runTestContract(
        testFlow,
        testContractParams.toComplete().rightValue
      )
      .rightValue
    result.contracts.length is 2
    result.contracts(0).address is Address.contract(fooCallerContractId)
    result.contracts(1).address is Address.contract(barContractId)
    val assetOutput = result.txOutputs(1)
    assetOutput.address is assetAddress
    val totalGas = nonCoinbaseMinGasPrice * maximalGasPerTx
    assetOutput.attoAlphAmount is Amount(ALPH.oneAlph.subUnsafe(totalGas))
    val contractOutput = result.txOutputs(0)
    contractOutput.address is Address.contract(fooCallerContractId)
    contractOutput.attoAlphAmount.value is ALPH.alph(2)
  }

  it should "fail to destroy contracts and transfer fund to non-calling address" in new DestroyFixture {
    override def fooCaller: String =
      s"""
         |Contract FooCaller(fooId: ByteVec) {
         |  pub fn destroyFoo() -> () {
         |    let foo = Foo(fooId)
         |    foo.destroy(@${Address.contract(ContractId.random).toBase58})
         |  }
         |}
         |
         |$foo
         |""".stripMargin

    serverUtils
      .runTestContract(
        testFlow,
        testContractParams.toComplete().rightValue
      )
      .leftValue
      .detail is "VM execution error: PayToContractAddressNotInCallerTrace"
  }

  it should "show debug message when contract execution failed" in new Fixture {
    val contract =
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    emit Debug(`Hello, Alephium!`)
         |    assert!(false, 0)
         |  }
         |}
         |""".stripMargin
    val code = Compiler.compileContract(contract).rightValue

    val serverUtils  = new ServerUtils()
    val testContract = TestContract(bytecode = code).toComplete().rightValue
    val testError    = serverUtils.runTestContract(blockFlow, testContract).leftValue.detail
    testError is
      s"DEBUG - ${Address.contract(testContract.contractId).toBase58} - Hello, Alephium!\n" ++
      "VM execution error: AssertionFailedWithErrorCode(tgx7VNFoP9DJiFMFgXXtafQZkUvyEdDHT9ryamHJYrjq,0)"
  }

  it should "test blockHash function for Ralph" in new TestContractFixture {
    val blockHash = BlockHash.random
    val contract =
      s"""
         |Contract Foo() {
         |  fn foo() -> (ByteVec) {
         |    assert!(blockHash!() == #${blockHash.toHexString}, 0)
         |    return blockHash!()
         |  }
         |}
         |""".stripMargin

    val code = Compiler.compileContract(contract).rightValue

    val testContract0 = TestContract(bytecode = code).toComplete().rightValue
    val testResult0   = serverUtils.runTestContract(blockFlow, testContract0).leftValue
    testResult0.detail is s"VM execution error: AssertionFailedWithErrorCode(tgx7VNFoP9DJiFMFgXXtafQZkUvyEdDHT9ryamHJYrjq,0)"

    val testContract1 =
      TestContract(bytecode = code, blockHash = Some(blockHash)).toComplete().rightValue
    val testResult1 = serverUtils.runTestContract(blockFlow, testContract1).rightValue
    testResult1.returns.head is api.ValByteVec(blockHash.bytes)
  }

  it should "extract debug message from contract event" in new Fixture {
    val serverUtils     = new ServerUtils()
    val contractAddress = Address.contract(ContractId.random)
    def buildEvent(fields: Val*): ContractEventByTxId = {
      ContractEventByTxId(BlockHash.random, contractAddress, 0, AVector.from(fields))
    }

    serverUtils
      .extractDebugMessage(buildEvent())
      .leftValue
      .detail is "Invalid debug message"

    serverUtils
      .extractDebugMessage(buildEvent(ValBool(true)))
      .leftValue
      .detail is "Invalid debug message"

    serverUtils
      .extractDebugMessage(buildEvent(ValByteVec(ByteString.fromString("Hello, Alephium!")))) isE
      DebugMessage(contractAddress, "Hello, Alephium!")

    serverUtils
      .extractDebugMessage(
        buildEvent(ValByteVec(ByteString.fromString("Hello, Alephium!")), ValBool(true))
      )
      .leftValue
      .detail is "Invalid debug message"
  }

  it should "test debug function for Ralph" in new Fixture {
    val contract: String =
      s"""
         |Contract Foo(name: ByteVec) {
         |  pub fn foo() -> () {
         |    emit Debug(`Hello, $${name}!`)
         |  }
         |}
         |""".stripMargin
    val code = Compiler.compileContract(contract).rightValue

    val testContract = TestContract(
      bytecode = code,
      initialImmFields = Some(AVector(ValByteVec(ByteString.fromString("Alephium"))))
    ).toComplete().rightValue
    val serverUtils = new ServerUtils()
    val testResult  = serverUtils.runTestContract(blockFlow, testContract).rightValue
    testResult.events.isEmpty is true
    testResult.debugMessages is AVector(
      DebugMessage(Address.contract(testContract.contractId), "Hello, Alephium!")
    )
  }

  it should "test contract asset only function" in new Fixture {
    val contract =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo() -> () {
         |    assert!(tokenRemaining!(selfAddress!(), ALPH) == 1 alph, 0)
         |  }
         |}
         |""".stripMargin
    val code         = Compiler.compileContract(contract).rightValue
    val testContract = TestContract(bytecode = code).toComplete().rightValue
    val serverUtils  = new ServerUtils()
    val testResult   = serverUtils.runTestContract(blockFlow, testContract).rightValue
    testResult.txInputs.isEmpty is true
    testResult.txOutputs.isEmpty is true
  }

  trait TestContractFixture extends Fixture {
    val tokenId         = TokenId.random
    val (_, pubKey)     = SignatureSchema.generatePriPub()
    val lp              = Address.Asset(LockupScript.p2pkh(pubKey))
    val buyer           = lp
    val contractAddress = Address.contract(ContractId.zero)

    def testContract0: TestContract.Complete

    val serverUtils  = new ServerUtils()
    val testFlow     = BlockFlow.emptyUnsafe(config)
    lazy val result0 = serverUtils.runTestContract(testFlow, testContract0).rightValue

    val testContractId1 = ContractId.random
    def testContract1: TestContract.Complete
    lazy val result1 = serverUtils.runTestContract(testFlow, testContract1).rightValue
  }

  it should "return upgraded contract code hash" in new TestContractFixture {
    val fooV1Code =
      s"""
         |Contract FooV1() {
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin
    val fooV1         = Compiler.compileContract(fooV1Code).rightValue
    val fooV1Bytecode = Hex.toHexString(serialize(fooV1))

    val fooV0Code =
      s"""
         |Contract FooV0() {
         |  pub fn upgrade0() -> () {
         |    migrate!(#$fooV1Bytecode)
         |  }
         |  fn upgrade1() -> () {
         |    migrate!(#$fooV1Bytecode)
         |  }
         |}
         |""".stripMargin
    val fooV0 = Compiler.compileContract(fooV0Code).rightValue

    val testContract0 = TestContract.Complete(
      code = fooV0,
      originalCodeHash = fooV0.hash,
      testMethodIndex = 0
    )
    testContract0.code.hash is testContract0.originalCodeHash
    result0.codeHash is fooV1.hash
    result0.contracts(0).codeHash is fooV1.hash

    val testContract1 =
      TestContract(bytecode = fooV0, methodIndex = Some(1)).toComplete().rightValue
    testContract1.code.hash isnot testContract1.originalCodeHash
    result1.codeHash is fooV1.hash
    result1.contracts(0).codeHash is fooV1.hash
  }

  it should "test AMM contract: add liquidity" in new TestContractFixture {
    val testContract0 = TestContract.Complete(
      code = AMMContract.swapCode,
      originalCodeHash = AMMContract.swapCode.hash,
      initialImmFields = AVector[Val](ValByteVec(tokenId.bytes)),
      initialMutFields = AVector[Val](ValU256(ALPH.alph(10)), ValU256(100)),
      initialAsset = AssetState.from(ALPH.alph(10), tokens = AVector(Token(tokenId, 100))),
      testMethodIndex = 0,
      testArgs = AVector[Val](ValAddress(lp), ValU256(ALPH.alph(100)), ValU256(100)),
      inputAssets = AVector(
        TestInputAsset(
          lp,
          AssetState.from(ALPH.alph(101), AVector(Token(tokenId, 100)))
        )
      )
    )

    result0.returns.isEmpty is true
    result0.gasUsed is 17467
    result0.contracts.length is 1
    val contractState = result0.contracts.head
    contractState.id is ContractId.zero
    contractState.immFields is AVector[Val](ValByteVec(tokenId.bytes))
    contractState.mutFields is AVector[Val](ValU256(ALPH.alph(110)), ValU256(200))
    contractState.asset is AssetState.from(ALPH.alph(110), AVector(Token(tokenId, 200)))
    result0.txInputs is AVector[Address](contractAddress)
    result0.txOutputs.length is 2
    result0.txOutputs(0) is ContractOutput(
      result0.txOutputs(0).hint,
      emptyKey(0),
      Amount(ALPH.alph(110)),
      contractAddress,
      AVector(Token(tokenId, 200))
    )
    result0.txOutputs(1) is AssetOutput(
      result0.txOutputs(1).hint,
      emptyKey(1),
      Amount(937500000000000000L),
      lp,
      AVector.empty,
      TimeStamp.zero,
      ByteString.empty
    )
    result0.events.length is 1
    result0.events(0).eventIndex is 0
    result0.events(0).fields is AVector[Val](
      ValAddress(lp),
      ValU256(ALPH.alph(100)),
      ValU256(100)
    )

    val testContract1 = TestContract.Complete(
      contractId = testContractId1,
      code = AMMContract.swapProxyCode,
      originalCodeHash = AMMContract.swapProxyCode.hash,
      initialImmFields = AVector[Val](
        ValByteVec(testContract0.contractId.bytes),
        ValByteVec(tokenId.bytes)
      ),
      initialAsset = AssetState(ALPH.alph(1)),
      testMethodIndex = 0,
      testArgs = AVector[Val](ValAddress(lp), ValU256(ALPH.alph(100)), ValU256(100)),
      existingContracts = result0.contracts,
      inputAssets = AVector(
        TestInputAsset(
          lp,
          AssetState.from(ALPH.alph(101), AVector(Token(tokenId, 100)))
        )
      )
    )
    result1.returns.isEmpty is true
    result1.gasUsed is 18575
    result1.contracts.length is 2
    val contractState1 = result1.contracts.head
    contractState1.id is ContractId.zero
    contractState1.immFields is AVector[Val](ValByteVec(tokenId.bytes))
    contractState1.mutFields is AVector[Val](ValU256(ALPH.alph(210)), ValU256(300))
    contractState1.asset is AssetState.from(ALPH.alph(210), AVector(Token(tokenId, 300)))
    result1.txInputs is AVector[Address](contractAddress)
    result1.txOutputs.length is 2
    result1.txOutputs(0) is ContractOutput(
      result1.txOutputs(0).hint,
      emptyKey(0),
      Amount(ALPH.alph(210)),
      contractAddress,
      AVector(Token(tokenId, 300))
    )
    result1.txOutputs(1) is AssetOutput(
      result1.txOutputs(1).hint,
      emptyKey(1),
      Amount(937500000000000000L),
      lp,
      AVector.empty,
      TimeStamp.zero,
      ByteString.empty
    )
  }

  it should "test AMM contract: swap token" in new TestContractFixture {
    val testContract0 = TestContract.Complete(
      code = AMMContract.swapCode,
      originalCodeHash = AMMContract.swapCode.hash,
      initialImmFields = AVector[Val](ValByteVec(tokenId.bytes)),
      initialMutFields = AVector[Val](ValU256(ALPH.alph(10)), ValU256(100)),
      initialAsset = AssetState.from(ALPH.alph(10), tokens = AVector(Token(tokenId, 100))),
      testMethodIndex = 1,
      testArgs = AVector[Val](ValAddress(buyer), ValU256(ALPH.alph(10))),
      inputAssets = AVector(
        TestInputAsset(
          lp,
          AssetState.from(ALPH.alph(101), AVector(Token(tokenId, 100)))
        )
      )
    )

    result0.returns.isEmpty is true
    result0.gasUsed is 17476
    result0.contracts.length is 1
    val contractState = result0.contracts.head
    contractState.id is ContractId.zero
    contractState.immFields is AVector[Val](ValByteVec(tokenId.bytes))
    contractState.mutFields is AVector[Val](ValU256(ALPH.alph(20)), ValU256(50))
    contractState.asset is AssetState.from(ALPH.alph(20), AVector(Token(tokenId, 50)))
    result0.txInputs is AVector[Address](contractAddress)
    result0.txOutputs.length is 2
    result0.txOutputs(0) is ContractOutput(
      result0.txOutputs(0).hint,
      emptyKey(0),
      Amount(ALPH.alph(20)),
      contractAddress,
      AVector(Token(tokenId, 50))
    )
    result0.txOutputs(1) is AssetOutput(
      result0.txOutputs(1).hint,
      emptyKey(1),
      Amount(ALPH.nanoAlph(90937500000L)),
      buyer,
      AVector(Token(tokenId, 150)),
      TimeStamp.zero,
      ByteString.empty
    )
    result0.events.length is 1
    result0.events(0).eventIndex is 1
    result0.events(0).fields is AVector[Val](ValAddress(buyer), ValU256(ALPH.alph(10)))

    val testContract1 = TestContract.Complete(
      contractId = testContractId1,
      code = AMMContract.swapProxyCode,
      originalCodeHash = AMMContract.swapProxyCode.hash,
      initialImmFields = AVector[Val](
        ValByteVec(testContract0.contractId.bytes),
        ValByteVec(tokenId.bytes)
      ),
      initialAsset = AssetState(ALPH.alph(1)),
      testMethodIndex = 2,
      testArgs = AVector[Val](ValAddress(buyer), ValU256(50)),
      existingContracts = result0.contracts,
      inputAssets = AVector(
        TestInputAsset(
          lp,
          AssetState.from(ALPH.alph(101), AVector(Token(tokenId, 50)))
        )
      )
    )
    result1.returns.isEmpty is true
    result1.gasUsed is 18546
    result1.contracts.length is 2
    val contractState1 = result1.contracts.head
    contractState1.id is ContractId.zero
    contractState1.immFields is AVector[Val](ValByteVec(tokenId.bytes))
    contractState1.mutFields is AVector[Val](ValU256(ALPH.alph(10)), ValU256(100))
    contractState1.asset is AssetState.from(ALPH.alph(10), AVector(Token(tokenId, 100)))
    result1.txInputs is AVector[Address](contractAddress)
    result1.txOutputs.length is 2
    result1.txOutputs(0) is ContractOutput(
      result1.txOutputs(0).hint,
      emptyKey(0),
      Amount(ALPH.alph(10)),
      contractAddress,
      AVector(Token(tokenId, 100))
    )
    result1.txOutputs(1) is AssetOutput(
      result1.txOutputs(1).hint,
      emptyKey(1),
      Amount(ALPH.nanoAlph(110937500000L)),
      lp,
      AVector.empty,
      TimeStamp.zero,
      ByteString.empty
    )
  }

  it should "test AMM contract: swap Alph" in new TestContractFixture {
    val testContract0 = TestContract.Complete(
      code = AMMContract.swapCode,
      originalCodeHash = AMMContract.swapCode.hash,
      initialImmFields = AVector[Val](ValByteVec(tokenId.bytes)),
      initialMutFields = AVector[Val](ValU256(ALPH.alph(10)), ValU256(100)),
      initialAsset = AssetState.from(ALPH.alph(10), tokens = AVector(Token(tokenId, 100))),
      testMethodIndex = 2,
      testArgs = AVector[Val](ValAddress(buyer), ValU256(100)),
      inputAssets = AVector(
        TestInputAsset(
          lp,
          AssetState.from(ALPH.alph(101), AVector(Token(tokenId, 100)))
        )
      )
    )

    result0.returns.isEmpty is true
    result0.gasUsed is 17476
    result0.contracts.length is 1
    val contractState = result0.contracts.head
    contractState.id is ContractId.zero
    contractState.immFields is AVector[Val](ValByteVec(tokenId.bytes))
    contractState.mutFields is AVector[Val](ValU256(ALPH.alph(5)), ValU256(200))
    contractState.asset is AssetState.from(ALPH.alph(5), AVector(Token(tokenId, 200)))
    result0.txInputs is AVector[Address](contractAddress)
    result0.txOutputs.length is 2
    result0.txOutputs(0) is ContractOutput(
      result0.txOutputs(0).hint,
      emptyKey(0),
      Amount(ALPH.alph(5)),
      contractAddress,
      AVector(Token(tokenId, 200))
    )
    result0.txOutputs(1) is AssetOutput(
      result0.txOutputs(1).hint,
      emptyKey(1),
      Amount(ALPH.nanoAlph(105937500000L)),
      buyer,
      AVector.empty,
      TimeStamp.zero,
      ByteString.empty
    )
    result0.events.length is 1
    result0.events(0).eventIndex is 2
    result0.events(0).fields is AVector[Val](ValAddress(buyer), ValU256(100))

    val testContract1 = TestContract.Complete(
      contractId = testContractId1,
      code = AMMContract.swapProxyCode,
      originalCodeHash = AMMContract.swapProxyCode.hash,
      initialImmFields = AVector[Val](
        ValByteVec(testContract0.contractId.bytes),
        ValByteVec(tokenId.bytes)
      ),
      initialAsset = AssetState(ALPH.alph(1)),
      testMethodIndex = 1,
      testArgs = AVector[Val](ValAddress(buyer), ValU256(ALPH.alph(5))),
      existingContracts = result0.contracts,
      inputAssets = AVector(
        TestInputAsset(
          lp,
          AssetState(ALPH.alph(101))
        )
      )
    )
    result1.returns.isEmpty is true
    result1.gasUsed is 18507
    result1.contracts.length is 2
    val contractState1 = result1.contracts.head
    contractState1.id is ContractId.zero
    contractState1.immFields is AVector[Val](ValByteVec(tokenId.bytes))
    contractState1.mutFields is AVector[Val](ValU256(ALPH.alph(10)), ValU256(100))
    contractState1.asset is AssetState.from(ALPH.alph(10), AVector(Token(tokenId, 100)))
    result1.txInputs is AVector[Address](contractAddress)
    result1.txOutputs.length is 2
    result1.txOutputs(0) is ContractOutput(
      result1.txOutputs(0).hint,
      emptyKey(0),
      Amount(ALPH.alph(10)),
      contractAddress,
      AVector(Token(tokenId, 100))
    )
    result1.txOutputs(1) is AssetOutput(
      result1.txOutputs(1).hint,
      emptyKey(1),
      Amount(ALPH.nanoAlph(95937500000L)),
      lp,
      AVector(Token(tokenId, 100)),
      TimeStamp.zero,
      ByteString.empty
    )
  }

  it should "test array parameters in contract" in new Fixture {
    val isPublic = if (Random.nextBoolean()) "pub" else ""
    val contract =
      s"""
         |Contract ArrayTest(mut array: [U256; 2]) {
         |  @using(updateFields = true)
         |  ${isPublic} fn swap(input: [U256; 2]) -> ([U256; 2]) {
         |    array[0] = input[1]
         |    array[1] = input[0]
         |    return array
         |  }
         |}
         |""".stripMargin
    val code = Compiler.compileContract(contract).toOption.get

    val testContract = TestContract(
      bytecode = code,
      initialMutFields =
        Some(AVector[Val](ValArray(AVector(ValU256(U256.Zero), ValU256(U256.One))))),
      args = Some(AVector[Val](ValArray(AVector(ValU256(U256.Zero), ValU256(U256.One)))))
    ).toComplete().rightValue

    val serverUtils   = new ServerUtils()
    val compileResult = serverUtils.compileContract(Compile.Contract(contract)).rightValue
    compileResult.fields.types is AVector("[U256;2]")
    val func = compileResult.functions.head
    func.paramNames is AVector("input")
    func.paramTypes is AVector("[U256;2]")
    func.paramIsMutable is AVector(false)
    func.returnTypes is AVector("[U256;2]")

    val testFlow      = BlockFlow.emptyUnsafe(config)
    val result        = serverUtils.runTestContract(testFlow, testContract).rightValue
    val contractState = result.contracts(0)
    result.contracts.length is 1
    contractState.immFields is AVector.empty[Val]
    contractState.mutFields is AVector[Val](ValU256(U256.One), ValU256(U256.Zero))
    result.returns is AVector[Val](ValU256(U256.One), ValU256(U256.Zero))
    compileResult.codeHash is code.hash
    result.codeHash is contractState.codeHash
    contractState.codeHash is compileResult.codeHash // We should return the original code hash even when the method is private
  }

  it should "test with preassigned block hash and tx id" in new Fixture {
    val contract =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val code = Compiler.compileContract(contract).toOption.get

    val testContract = TestContract(
      blockHash = Some(BlockHash.random),
      txId = Some(TransactionId.random),
      bytecode = code
    )
    val testContractComplete = testContract.toComplete().rightValue
    testContractComplete.blockHash is testContract.blockHash.get
    testContractComplete.txId is testContract.txId.get
  }

  it should "compile contract" in new Fixture {
    val serverUtils = new ServerUtils()
    val rawCode =
      s"""
         |Contract Foo(x: U256, y: U256) {
         |  pub fn foo() -> () {
         |    let a = 0
         |    assert!(1 != y, 0)
         |  }
         |}
         |""".stripMargin
    val code   = Compiler.compileContract(rawCode).rightValue
    val query  = Compile.Contract(rawCode)
    val result = serverUtils.compileContract(query).rightValue

    val compiledCode = result.bytecode
    compiledCode is Hex.toHexString(serialize(code))
    compiledCode is {
      val bytecode     = "0100000100070c17000dce01300c7b"
      val methodLength = Hex.toHexString(IndexedSeq((bytecode.length / 2).toByte))
      s"0201$methodLength" + bytecode
    }
    result.warnings is AVector(
      "Found unused variables in Foo: foo.a",
      "Found unused fields in Foo: x"
    )

    info("Turn off warnings")
    val compilerOptions = CompilerOptions(
      ignoreUnusedVariablesWarnings = Some(true),
      ignoreUnusedFieldsWarnings = Some(true),
      ignoreUpdateFieldsCheckWarnings = Some(true)
    )
    val newResult =
      serverUtils.compileContract(query.copy(compilerOptions = Some(compilerOptions))).rightValue
    newResult.warnings.isEmpty is true
  }

  it should "compile project" in new Fixture {
    val serverUtils = new ServerUtils()
    val rawCode =
      s"""
         |Interface Foo {
         |  pub fn foo() -> ()
         |}
         |Contract Bar() implements Foo {
         |  pub fn foo() -> () {
         |    checkCaller!(true, 0)
         |  }
         |}
         |TxScript Main(id: ByteVec) {
         |  Bar(id).foo()
         |}
         |""".stripMargin
    val (contracts, scripts) = Compiler.compileProject(rawCode).rightValue
    val query                = Compile.Project(rawCode)
    val result               = serverUtils.compileProject(query).rightValue

    result.contracts.length is 1
    contracts.length is 1
    val contractCode = result.contracts(0).bytecode
    contractCode is Hex.toHexString(serialize(contracts(0).code))

    result.scripts.length is 1
    scripts.length is 1
    val scriptCode = result.scripts(0).bytecodeTemplate
    scriptCode is scripts(0).code.toTemplateString()
  }

  it should "compile script" in new Fixture {
    val expectedByteCode = "01010000000005{0}{1}300c7b"
    val serverUtils      = new ServerUtils()

    {
      val rawCode =
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main(x: U256, y: U256) {
           |  assert!(x != y, 0)
           |}
           |""".stripMargin

      val query  = Compile.Script(rawCode)
      val result = serverUtils.compileScript(query).rightValue
      result.bytecodeTemplate is expectedByteCode
    }

    {
      val rawCode =
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main {
           |  assert!(1 != 2, 0)
           |}
           |""".stripMargin
      val code   = Compiler.compileTxScript(rawCode).rightValue
      val query  = Compile.Script(rawCode)
      val result = serverUtils.compileScript(query).rightValue

      result.bytecodeTemplate is Hex.toHexString(serialize(code))
      result.bytecodeTemplate is expectedByteCode
        .replace("{0}", "0d") // bytecode of U256Const1
        .replace("{1}", "0e") // bytecode of U256Const2
    }

    {
      val rawCode =
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main(a: U256, b: U256) {
           |  let c = 0
           |  assert!(a != 0, 0)
           |}
           |""".stripMargin
      val query  = Compile.Script(rawCode)
      val result = serverUtils.compileScript(query).rightValue
      result.warnings is AVector(
        "Found unused variables in Main: main.c",
        "Found unused fields in Main: b"
      )

      info("Turn off warnings")
      val compilerOptions = CompilerOptions(
        ignoreUnusedVariablesWarnings = Some(true),
        ignoreUnusedFieldsWarnings = Some(true)
      )
      val newResult =
        serverUtils.compileScript(query.copy(compilerOptions = Some(compilerOptions))).rightValue
      newResult.warnings.isEmpty is true
    }
  }

  it should "create build deploy contract script" in new Fixture {
    val rawCode =
      s"""
         |Contract Foo(y: U256) {
         |  pub fn foo() -> () {
         |    assert!(1 != y, 0)
         |  }
         |}
         |""".stripMargin
    val contract          = Compiler.compileContract(rawCode).rightValue
    val (_, publicKey, _) = genesisKeys(0)
    val fromAddress       = Address.p2pkh(publicKey)

    {
      info("With approved tokens")
      val token1                         = TokenId.generate
      val token2                         = TokenId.generate
      val codeRaw                        = Hex.toHexString(serialize(contract))
      val initialFields: AVector[vm.Val] = AVector(vm.Val.U256.unsafe(0))
      val stateRaw                       = Hex.toHexString(serialize(initialFields))

      val expected =
        s"""
           |TxScript Main {
           |  createContractWithToken!{@$fromAddress -> ALPH: 10, #${token1.toHexString}: 10, #${token2.toHexString}: 20}(#$codeRaw, #$stateRaw, #00, 50)
           |}
           |""".stripMargin
      Compiler.compileTxScript(expected).isRight is true
      ServerUtils
        .buildDeployContractScriptRawWithParsedState(
          codeRaw,
          fromAddress,
          initialImmFields = initialFields,
          initialMutFields = AVector.empty,
          U256.unsafe(10),
          AVector((token1, U256.unsafe(10)), (token2, U256.unsafe(20))),
          Some(U256.unsafe(50))
        ) is expected
    }

    {
      info("Without approved tokens")
      val codeRaw                        = Hex.toHexString(serialize(contract))
      val initialFields: AVector[vm.Val] = AVector(vm.Val.U256.unsafe(0))
      val stateRaw                       = Hex.toHexString(serialize(initialFields))

      val expected =
        s"""
           |TxScript Main {
           |  createContractWithToken!{@$fromAddress -> ALPH: 10}(#$codeRaw, #$stateRaw, #00, 50)
           |}
           |""".stripMargin
      Compiler.compileTxScript(expected).isRight is true
      ServerUtils
        .buildDeployContractScriptRawWithParsedState(
          codeRaw,
          fromAddress,
          initialImmFields = initialFields,
          initialMutFields = AVector.empty,
          U256.unsafe(10),
          AVector.empty,
          Some(U256.unsafe(50))
        ) is expected
    }
  }

  it should "fail when the number of parameters is not as specified by the test method" in new Fixture {
    val contract =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val code = Compiler.compileContract(contract).toOption.get

    val testContract =
      TestContract(bytecode = code, args = Some(AVector[Val](Val.True))).toComplete().rightValue
    val serverUtils = new ServerUtils()
    serverUtils
      .runTestContract(blockFlow, testContract)
      .leftValue
      .detail is "The number of parameters is different from the number specified by the target method"
  }

  it should "test utxo splits for generated outputs" in new Fixture {
    val tokenId = TokenId.random
    val contract =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo() -> () {
         |    transferTokenFromSelf!(callerAddress!(), #${tokenId.toHexString}, 1)
         |  }
         |}
         |""".stripMargin
    val code = Compiler.compileContract(contract).toOption.get

    val caller = Address.p2pkh(PublicKey.generate)
    val inputAssets = TestInputAsset(
      caller,
      AssetState(
        ALPH.alph(3),
        Some(AVector.fill(2 * maxTokenPerUtxo)(Token(TokenId.random, 1)))
      )
    )
    val testContract = TestContract(
      blockHash = Some(BlockHash.random),
      txId = Some(TransactionId.random),
      bytecode = code,
      initialAsset = Some(AssetState(ALPH.oneAlph, Some(AVector(Token(tokenId, 10))))),
      args = Some(AVector.empty[Val]),
      inputAssets = Some(AVector(inputAssets))
    ).toComplete().rightValue

    val serverUtils  = new ServerUtils()
    val tokensSorted = (inputAssets.asset.tokens.get :+ Token(tokenId, 1)).sortBy(_.id)
    val testResult   = serverUtils.runTestContract(blockFlow, testContract).rightValue
    testResult.txOutputs.length is 4
    testResult.txOutputs(0).address is caller
    testResult.txOutputs(0).tokens.length is maxTokenPerUtxo
    testResult.txOutputs(0).tokens is tokensSorted.slice(0, maxTokenPerUtxo)
    testResult.txOutputs(1).address is caller
    testResult.txOutputs(1).tokens.length is maxTokenPerUtxo
    testResult.txOutputs(1).tokens is tokensSorted
      .slice(maxTokenPerUtxo, 2 * maxTokenPerUtxo)
    testResult.txOutputs(2).address is caller
    testResult.txOutputs(2).tokens.length is 1
    testResult.txOutputs(2).tokens is tokensSorted.slice(2 * maxTokenPerUtxo, tokensSorted.length)
    testResult.txOutputs(3).address is Address.contract(testContract.contractId)
  }

  private def generateDestination(
      chainIndex: ChainIndex,
      message: ByteString = ByteString.empty
  )(implicit
      groupConfig: GroupConfig
  ): Destination = {
    val address = generateAddress(chainIndex)
    val amount  = Amount(ALPH.oneAlph)
    Destination(address, amount, None, None, Some(message))
  }

  private def generateAddress(chainIndex: ChainIndex)(implicit
      groupConfig: GroupConfig
  ): Address.Asset = {
    val (_, toPublicKey) = chainIndex.to.generateKey
    Address.p2pkh(toPublicKey)
  }

  private def signAndAddToMemPool(
      txId: TransactionId,
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

    serverUtils.getTransactionStatus(blockFlow, txId, chainIndex) isE TxNotFound()

    blockFlow.getGrandPool().add(chainIndex, AVector(txTemplate), TimeStamp.now())
    serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE MemPooled()

    txTemplate
  }

  private def checkAddressBalance(address: Address.Asset, amount: U256, utxoNum: Int = 1)(implicit
      serverUtils: ServerUtils,
      blockFlow: BlockFlow
  ) = {
    serverUtils.getBalance(blockFlow, address) isE Balance.from(
      Amount(amount),
      Amount.Zero,
      None,
      None,
      utxoNum
    )
  }

  private def checkDestinationBalance(destination: Destination, utxoNum: Int = 1)(implicit
      serverUtils: ServerUtils,
      blockFlow: BlockFlow
  ) = {
    checkAddressBalance(destination.address, destination.attoAlphAmount.value, utxoNum)
  }
}
