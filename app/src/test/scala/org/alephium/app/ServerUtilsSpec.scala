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
import org.alephium.api.model.{TransactionTemplate => _, _}
import org.alephium.flow.FlowFixture
import org.alephium.flow.core.{AMMContract, BlockFlow}
import org.alephium.flow.gasestimation._
import org.alephium.protocol._
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{AssetOutput => _, ContractOutput => _, _}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript}
import org.alephium.protocol.vm.lang.Compiler
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

    def emptyKey(index: Int): Hash = TxOutputRef.key(Hash.zero, index)
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
        genesisBalance - destination1.alphAmount.value - destination2.alphAmount.value

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
        genesisBalance - destination1.alphAmount.value - destination2.alphAmount.value

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

  it should "check sweep address tx status for intra group txs" in new Fixture {
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
      sweepAddressTxTemplate.gasFeeUnsafe is defaultGasPrice * GasEstimation.sweepAddress(11, 1)

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
      val fromLockupScript    = LockupScript.p2pkh(fromPublicKey)
      val outputLockupScripts = fromLockupScript +: destinations.map(_.address.lockupScript)
      val defaultGas =
        GasEstimation.estimateWithP2PKHInputs(outputRefs.length, outputLockupScripts.length)
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
        defaultGasPrice
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
        defaultGasPrice
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
        defaultGasPrice
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
        defaultGasPrice
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
        GasPrice(minimalGasPrice.value - 1)
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
        GasPrice(ALPH.MaxALPHValue)
      )
      .leftValue
      .detail is "Gas price GasPrice(1000000000000000000000000000) too large, maximal GasPrice(999999999999999999999999999)"
  }

  it should "not create transaction with overflowing ALPH amount" in new MultipleUtxos {
    val alphAmountOverflowDestinations = AVector(
      destination1,
      destination2.copy(alphAmount = Amount(ALPH.MaxALPHValue))
    )
    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = None,
        alphAmountOverflowDestinations,
        gasOpt = Some(minimalGas),
        defaultGasPrice
      )
      .leftValue
      .detail is "ALPH amount overflow"
  }

  it should "not create transaction when with token amount overflow" in new MultipleUtxos {
    val tokenId = Hash.hash("token1")
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
        defaultGasPrice
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
        defaultGasPrice
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

  it should "check the minimal amount deposit for contrac creation" in new Fixture {
    val serverUtils = new ServerUtils
    serverUtils.getInitialAlphAmount(None) isE minimalAlphInContract
    serverUtils.getInitialAlphAmount(Some(Amount(minimalAlphInContract))) isE minimalAlphInContract
    serverUtils
      .getInitialAlphAmount(Some(Amount(minimalAlphInContract - 1)))
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
        AVector(api.TransactionTemplate.fromProtocol(txTemplate))
      )
    )
  }

  trait TestContractFixture extends Fixture {
    val tokenId         = Hash.random
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
         |TxContract FooV1() {
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin
    val fooV1         = Compiler.compileContract(fooV1Code).rightValue
    val fooV1Bytecode = Hex.toHexString(serialize(fooV1))

    val fooV0Code =
      s"""
         |TxContract FooV0() {
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
      TestContract(bytecode = fooV0, testMethodIndex = Some(1)).toComplete().rightValue
    testContract1.code.hash isnot testContract1.originalCodeHash
    result1.codeHash is fooV1.hash
    result1.contracts(0).codeHash is fooV1.hash
  }

  it should "test AMM contract: add liquidity" in new TestContractFixture {
    val testContract0 = TestContract.Complete(
      code = AMMContract.swapCode,
      originalCodeHash = AMMContract.swapCode.hash,
      initialFields = AVector[Val](ValByteVec(tokenId.bytes), ValU256(ALPH.alph(10)), ValU256(100)),
      initialAsset = AssetState.from(ALPH.alph(10), tokens = AVector(Token(tokenId, 100))),
      testMethodIndex = 0,
      testArgs = AVector[Val](ValAddress(lp), ValU256(ALPH.alph(100)), ValU256(100)),
      inputAssets = AVector(
        TestContract.InputAsset(
          lp,
          AssetState.from(ALPH.alph(101), AVector(Token(tokenId, 100)))
        )
      )
    )

    result0.returns.isEmpty is true
    result0.gasUsed is 17495
    result0.contracts.length is 1
    val contractState = result0.contracts.head
    contractState.id is ContractId.zero
    contractState.fields is
      AVector[Val](ValByteVec(tokenId.bytes), ValU256(ALPH.alph(110)), ValU256(200))
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
      initialFields =
        AVector[Val](ValByteVec(testContract0.contractId.bytes), ValByteVec(tokenId.bytes)),
      initialAsset = AssetState(ALPH.alph(1)),
      testMethodIndex = 0,
      testArgs = AVector[Val](ValAddress(lp), ValU256(ALPH.alph(100)), ValU256(100)),
      existingContracts = result0.contracts,
      inputAssets = AVector(
        TestContract.InputAsset(
          lp,
          AssetState.from(ALPH.alph(101), AVector(Token(tokenId, 100)))
        )
      )
    )
    result1.returns.isEmpty is true
    result1.gasUsed is 18599
    result1.contracts.length is 2
    val contractState1 = result1.contracts.head
    contractState1.id is ContractId.zero
    contractState1.fields is
      AVector[Val](ValByteVec(tokenId.bytes), ValU256(ALPH.alph(210)), ValU256(300))
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
      initialFields = AVector[Val](ValByteVec(tokenId.bytes), ValU256(ALPH.alph(10)), ValU256(100)),
      initialAsset = AssetState.from(ALPH.alph(10), tokens = AVector(Token(tokenId, 100))),
      testMethodIndex = 1,
      testArgs = AVector[Val](ValAddress(buyer), ValU256(ALPH.alph(10))),
      inputAssets = AVector(
        TestContract.InputAsset(
          lp,
          AssetState.from(ALPH.alph(101), AVector(Token(tokenId, 100)))
        )
      )
    )

    result0.returns.isEmpty is true
    result0.gasUsed is 17504
    result0.contracts.length is 1
    val contractState = result0.contracts.head
    contractState.id is ContractId.zero
    contractState.fields is
      AVector[Val](ValByteVec(tokenId.bytes), ValU256(ALPH.alph(20)), ValU256(50))
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
      initialFields =
        AVector[Val](ValByteVec(testContract0.contractId.bytes), ValByteVec(tokenId.bytes)),
      initialAsset = AssetState(ALPH.alph(1)),
      testMethodIndex = 2,
      testArgs = AVector[Val](ValAddress(buyer), ValU256(50)),
      existingContracts = result0.contracts,
      inputAssets = AVector(
        TestContract.InputAsset(
          lp,
          AssetState.from(ALPH.alph(101), AVector(Token(tokenId, 50)))
        )
      )
    )
    result1.returns.isEmpty is true
    result1.gasUsed is 18569
    result1.contracts.length is 2
    val contractState1 = result1.contracts.head
    contractState1.id is ContractId.zero
    contractState1.fields is
      AVector[Val](ValByteVec(tokenId.bytes), ValU256(ALPH.alph(10)), ValU256(100))
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
      initialFields = AVector[Val](ValByteVec(tokenId.bytes), ValU256(ALPH.alph(10)), ValU256(100)),
      initialAsset = AssetState.from(ALPH.alph(10), tokens = AVector(Token(tokenId, 100))),
      testMethodIndex = 2,
      testArgs = AVector[Val](ValAddress(buyer), ValU256(100)),
      inputAssets = AVector(
        TestContract.InputAsset(
          lp,
          AssetState.from(ALPH.alph(101), AVector(Token(tokenId, 100)))
        )
      )
    )

    result0.returns.isEmpty is true
    result0.gasUsed is 17504
    result0.contracts.length is 1
    val contractState = result0.contracts.head
    contractState.id is ContractId.zero
    contractState.fields is
      AVector[Val](ValByteVec(tokenId.bytes), ValU256(ALPH.alph(5)), ValU256(200))
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
      initialFields =
        AVector[Val](ValByteVec(testContract0.contractId.bytes), ValByteVec(tokenId.bytes)),
      initialAsset = AssetState(ALPH.alph(1)),
      testMethodIndex = 1,
      testArgs = AVector[Val](ValAddress(buyer), ValU256(ALPH.alph(5))),
      existingContracts = result0.contracts,
      inputAssets = AVector(
        TestContract.InputAsset(
          lp,
          AssetState(ALPH.alph(101))
        )
      )
    )
    result1.returns.isEmpty is true
    result1.gasUsed is 18530
    result1.contracts.length is 2
    val contractState1 = result1.contracts.head
    contractState1.id is ContractId.zero
    contractState1.fields is
      AVector[Val](ValByteVec(tokenId.bytes), ValU256(ALPH.alph(10)), ValU256(100))
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
         |TxContract ArrayTest(mut array: [U256; 2]) {
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
      initialFields = Some(AVector[Val](ValArray(AVector(ValU256(U256.Zero), ValU256(U256.One))))),
      testArgs = Some(AVector[Val](ValArray(AVector(ValU256(U256.Zero), ValU256(U256.One)))))
    ).toComplete().rightValue

    val serverUtils   = new ServerUtils()
    val compileResult = serverUtils.compileContract(Compile.Contract(contract)).rightValue
    compileResult.fields.types is AVector("[U256;2]")
    val func = compileResult.functions.head
    func.argTypes is AVector("[U256;2]")
    func.returnTypes is AVector("[U256;2]")

    val testFlow      = BlockFlow.emptyUnsafe(config)
    val result        = serverUtils.runTestContract(testFlow, testContract).rightValue
    val contractState = result.contracts(0)
    result.contracts.length is 1
    contractState.fields is AVector[Val](ValU256(U256.One), ValU256(U256.Zero))
    result.returns is AVector[Val](ValU256(U256.One), ValU256(U256.Zero))
    compileResult.codeHash is code.hash
    result.codeHash is contractState.codeHash
    contractState.codeHash is compileResult.codeHash // We should return the original code hash even when the method is private
  }

  it should "test with preassigned block hash and tx id" in new Fixture {
    val contract =
      s"""
         |TxContract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val code = Compiler.compileContract(contract).toOption.get

    val testContract = TestContract(
      blockHash = Some(BlockHash.random),
      txId = Some(Hash.random),
      bytecode = code,
      initialFields = Some(AVector[Val](ValArray(AVector(ValU256(U256.Zero), ValU256(U256.One))))),
      testArgs = Some(AVector[Val](ValArray(AVector(ValU256(U256.Zero), ValU256(U256.One)))))
    )
    val testContractComplete = testContract.toComplete().rightValue
    testContractComplete.blockHash is testContract.blockHash.get
    testContractComplete.txId is testContract.txId.get
  }

  it should "compile contract" in new Fixture {
    val serverUtils = new ServerUtils()
    val rawCode =
      s"""
         |TxContract Foo(y: U256) {
         |  pub fn foo() -> () {
         |    assert!(1 != y)
         |  }
         |}
         |""".stripMargin
    val code   = Compiler.compileContract(rawCode).rightValue
    val query  = Compile.Contract(rawCode)
    val result = serverUtils.compileContract(query).rightValue

    val compiledCode = result.bytecode
    compiledCode is Hex.toHexString(serialize(code))
    compiledCode is {
      val bytecode     = "0100000000040da000304d"
      val methodLength = Hex.toHexString(IndexedSeq((bytecode.length / 2).toByte))
      s"0101$methodLength" + bytecode
    }
  }

  it should "compile script" in new Fixture {
    val expectedByteCode = "01010000000004{0}{1}304d"
    val serverUtils      = new ServerUtils()

    {
      val rawCode =
        s"""
           |@use(approvedAssets = false)
           |TxScript Main(x: U256, y: U256) {
           |  assert!(x != y)
           |}
           |""".stripMargin

      val query  = Compile.Script(rawCode)
      val result = serverUtils.compileScript(query).rightValue
      result.bytecodeTemplate is expectedByteCode
    }

    {
      val rawCode =
        s"""
           |@use(approvedAssets = false)
           |TxScript Main {
           |  assert!(1 != 2)
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

    serverUtils.getTransactionStatus(blockFlow, txId, chainIndex) isE TxNotFound

    blockFlow.getMemPool(chainIndex).addToTxPool(chainIndex, AVector(txTemplate), TimeStamp.now())
    serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE MemPooled

    txTemplate
  }

  private def checkAddressBalance(address: Address.Asset, amount: U256, utxoNum: Int = 1)(implicit
      serverUtils: ServerUtils,
      blockFlow: BlockFlow
  ) = {
    serverUtils.getBalance(blockFlow, GetBalance(address)) isE Balance.from(
      Amount(amount),
      Amount.Zero,
      utxoNum
    )
  }

  private def checkDestinationBalance(destination: Destination, utxoNum: Int = 1)(implicit
      serverUtils: ServerUtils,
      blockFlow: BlockFlow
  ) = {
    checkAddressBalance(destination.address, destination.alphAmount.value, utxoNum)
  }
}
