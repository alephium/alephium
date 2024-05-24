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
import org.scalacheck.Gen

import org.alephium.api.{model => api}
import org.alephium.api.ApiError
import org.alephium.api.model.{Transaction => _, TransactionTemplate => _, _}
import org.alephium.crypto.BIP340Schnorr
import org.alephium.flow.FlowFixture
import org.alephium.flow.core.{AMMContract, BlockFlow}
import org.alephium.flow.gasestimation._
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol._
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model.{AssetOutput => _, ContractOutput => _, _}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, TokenIssuance, UnlockScript}
import org.alephium.ralph.Compiler
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util._

// scalastyle:off file.size.limit number.of.methods
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
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey.bytes, None, AVector(destination))
      )
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
          BuildTransaction(fromPublicKey.bytes, None, destinations)
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
          BuildTransaction(fromPublicKey.bytes, None, destinations)
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

  it should "support Schnorr address" in new Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val chainIndex                        = ChainIndex.unsafe(0, 0)
    val (genesisPriKey, genesisPubKey, _) = genesisKeys(0)

    val (priKey, pubKey) = BIP340Schnorr.generatePriPub()
    val schnorrAddress   = SchnorrAddress(pubKey)

    val block0 = transfer(
      blockFlow,
      genesisPriKey,
      schnorrAddress.lockupScript,
      AVector.empty[(TokenId, U256)],
      ALPH.alph(2)
    )
    addAndCheck(blockFlow, block0)

    // In case, the first transfer is a cross-group transaction
    val confirmBlock = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, confirmBlock)

    implicit val serverUtils = new ServerUtils
    val destination          = Destination(Address.p2pkh(genesisPubKey), Amount(ALPH.oneAlph))
    val buildTransaction = serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(
          fromPublicKey = pubKey.bytes,
          fromPublicKeyType = Some(BuildTxCommon.BIP340Schnorr),
          AVector(destination)
        )
      )
      .rightValue
    val unsignedTransaction =
      serverUtils.decodeUnsignedTransaction(buildTransaction.unsignedTx).rightValue

    val signature = BIP340Schnorr.sign(unsignedTransaction.id.bytes, priKey)
    val txTemplate =
      serverUtils
        .createTxTemplate(
          SubmitTransaction(buildTransaction.unsignedTx, Signature.unsafe(signature.bytes))
        )
        .rightValue
    blockFlow.getGrandPool().add(txTemplate.chainIndex, AVector(txTemplate), TimeStamp.now())

    val block1 = mineFromMemPool(blockFlow, txTemplate.chainIndex)
    block1.nonCoinbase.map(_.id).contains(txTemplate.id) is true
    addAndCheck(blockFlow, block1)
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
          BuildTransaction(fromPublicKey.bytes, None, destinations)
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
          BuildTransaction(fromPublicKey.bytes, None, destinations)
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

  it should "sweep only small UTXOs" in new FlowFixtureWithApi with GetTxFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils = new ServerUtils

    val (_, fromPublicKey, _) = genesisKeys(0)

    val result0 = serverUtils
      .buildSweepAddressTransactions(
        blockFlow,
        BuildSweepAddressTransactions(fromPublicKey, Address.p2pkh(fromPublicKey), None)
      )
      .rightValue
    result0.unsignedTxs.length is 1

    val result1 = serverUtils
      .buildSweepAddressTransactions(
        blockFlow,
        BuildSweepAddressTransactions(
          fromPublicKey,
          Address.p2pkh(fromPublicKey),
          Some(Amount(U256.One))
        )
      )
      .rightValue
    result1.unsignedTxs.length is 0
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
        BuildTransaction(fromPublicKey.bytes, None, destinations)
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
        BuildTransaction(fromPublicKey.bytes, None, selfDestinations)
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
        gasOpt = Some(GasBox.unsafe(5000001)),
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None
      )
      .leftValue
      .detail is "Provided gas GasBox(5000001) too large, maximal GasBox(5000000)"
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
        BuildTransaction(fromPublicKey.bytes, None, emptyDestinations)
      )
      .leftValue
      .detail is "Zero transaction outputs"

    info("Too many outputs")
    val tooManyDestinations = AVector.fill(ALPH.MaxTxOutputNum + 1)(generateDestination(chainIndex))
    serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey.bytes, None, tooManyDestinations)
      )
      .leftValue
      .detail is "Too many transaction outputs, maximal value: 256"
  }

  it should "check the minimal amount deposit for contract creation" in new Fixture {
    val serverUtils = new ServerUtils
    serverUtils.getInitialAttoAlphAmount(None, HardFork.Leman) isE minimalAlphInContractPreRhone
    serverUtils.getInitialAttoAlphAmount(
      Some(minimalAlphInContractPreRhone),
      HardFork.Leman
    ) isE minimalAlphInContractPreRhone
    serverUtils
      .getInitialAttoAlphAmount(Some(minimalAlphInContractPreRhone - 1), HardFork.Leman)
      .leftValue
      .detail is "Expect 1 ALPH deposit to deploy a new contract"

    serverUtils.getInitialAttoAlphAmount(None, HardFork.Rhone) isE minimalAlphInContract
    serverUtils.getInitialAttoAlphAmount(
      Some(minimalAlphInContract),
      HardFork.Rhone
    ) isE minimalAlphInContract
    serverUtils
      .getInitialAttoAlphAmount(Some(minimalAlphInContract - 1), HardFork.Rhone)
      .leftValue
      .detail is "Expect 0.1 ALPH deposit to deploy a new contract"
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
        BuildTransaction(fromPublicKey.bytes, None, destinations)
      )
      .leftValue

    buildTransaction.detail is "Different groups for transaction outputs"
  }

  it should "return mempool statuses" in new Fixture with Generators {

    override val configValues = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils = new ServerUtils()

    val emptyMempool = serverUtils.listMempoolTransactions(blockFlow).rightValue
    emptyMempool is AVector.empty[MempoolTransactions]

    val chainIndex                         = chainIndexGen.sample.get
    val fromGroup                          = chainIndex.from
    val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
    val destination                        = generateDestination(chainIndex)

    val buildTransaction = serverUtils
      .buildTransaction(
        blockFlow,
        BuildTransaction(fromPublicKey.bytes, None, AVector(destination))
      )
      .rightValue

    val txTemplate = signAndAddToMemPool(
      buildTransaction.txId,
      buildTransaction.unsignedTx,
      chainIndex,
      fromPrivateKey
    )

    val txs = serverUtils.listMempoolTransactions(blockFlow).rightValue

    txs is AVector(
      MempoolTransactions(
        chainIndex.from.value,
        chainIndex.to.value,
        AVector(api.TransactionTemplate.fromProtocol(txTemplate))
      )
    )
  }

  "ServerUtils.buildMultiInputsTransaction" should "transfer a single input" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex.from.value)
    val amount                = Amount(ALPH.oneAlph)
    val destination           = Destination(generateAddress(chainIndex), amount)

    val source = BuildMultiAddressesTransaction.Source(
      fromPublicKey.bytes,
      AVector(destination)
    )

    val buildTransaction = serverUtils
      .buildMultiInputsTransaction(
        blockFlow,
        BuildMultiAddressesTransaction(AVector(source))
      )
      .rightValue

    val unsignedTransaction =
      serverUtils.decodeUnsignedTransaction(buildTransaction.unsignedTx).rightValue

    unsignedTransaction.inputs.length is 1
    unsignedTransaction.fixedOutputs.length is 2
  }

  it should "transfer a multiple inputs" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val nbOfInputs             = 10
    val chainIndex             = ChainIndex.unsafe(0, 0)
    val (fromPrivateKey, _, _) = genesisKeys(chainIndex.from.value)
    val amount                 = ALPH.alph(10)
    val destination            = Destination(generateAddress(chainIndex), Amount(ALPH.oneAlph))

    val inputPubKeys = AVector.fill(10)(chainIndex.to.generateKey._2)

    inputPubKeys.foreach { pubKey =>
      val block = transfer(blockFlow, fromPrivateKey, pubKey, amount)
      addAndCheck(blockFlow, block)
    }

    val sources = inputPubKeys.map { pubKey =>
      BuildMultiAddressesTransaction.Source(
        pubKey.bytes,
        AVector(destination)
      )
    }

    val buildTransaction = serverUtils
      .buildMultiInputsTransaction(
        blockFlow,
        BuildMultiAddressesTransaction(sources)
      )
      .rightValue

    val unsignedTransaction =
      serverUtils.decodeUnsignedTransaction(buildTransaction.unsignedTx).rightValue

    unsignedTransaction.inputs.length is nbOfInputs
    unsignedTransaction.fixedOutputs.length is 1 + nbOfInputs
  }

  it should "fail with non unique inputs" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex.from.value)
    val destination           = generateDestination(chainIndex)

    val source = BuildMultiAddressesTransaction.Source(
      fromPublicKey.bytes,
      AVector(destination)
    )

    serverUtils
      .buildMultiInputsTransaction(
        blockFlow,
        BuildMultiAddressesTransaction(AVector(source, source))
      )
      .leftValue
      .detail is "Some addresses defined multiple time"
  }

  "ServerUtils.mergeAndprepareOutputInfos" should "with empty list" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils
    serverUtils.mergeAndprepareOutputInfos(AVector.empty).rightValue is AVector
      .empty[UnsignedTransaction.TxOutputInfo]
  }

  it should "merge simple destinations" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex = ChainIndex.unsafe(0, 0)

    val destination = Destination(generateAddress(chainIndex), Amount(ALPH.oneAlph))

    forAll(Gen.choose(1, 20)) { i =>
      val outputs = serverUtils.mergeAndprepareOutputInfos(AVector.fill(i)(destination)).rightValue
      outputs.length is 1
      outputs(0).attoAlphAmount is ALPH.alph(i.toLong)
      outputs(0).tokens is AVector.empty[(TokenId, U256)]
    }

    val destination2 = Destination(generateAddress(chainIndex), Amount(ALPH.alph(2)))

    forAll(Gen.choose(1, 20)) { i =>
      val outputs = serverUtils
        .mergeAndprepareOutputInfos(
          AVector.fill(i)(AVector(destination, destination2)).flatMap(identity)
        )
        .rightValue
      outputs.length is 2
      outputs(0).attoAlphAmount is ALPH.alph(i.toLong)
      outputs(0).tokens is AVector.empty[(TokenId, U256)]
      outputs(1).attoAlphAmount is ALPH.alph(2 * i.toLong)
      outputs(1).tokens is AVector.empty[(TokenId, U256)]
    }
  }

  it should "merge tokens" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex = ChainIndex.unsafe(0, 0)

    val tokenId1 = TokenId.random
    val tokenId2 = TokenId.random

    val tokens = AVector(Token(tokenId1, U256.One), Token(tokenId2, U256.Two))

    val destination = Destination(generateAddress(chainIndex), Amount(ALPH.oneAlph), Some(tokens))

    forAll(Gen.choose(1, 20)) { i =>
      val outputs = serverUtils.mergeAndprepareOutputInfos(AVector.fill(i)(destination)).rightValue
      outputs.length is 1
      outputs(0).attoAlphAmount is ALPH.alph(i.toLong)
      outputs(0).tokens is AVector(
        (tokenId1, U256.unsafe(i)),
        (tokenId2, U256.unsafe(2 * i.toLong))
      )
    }

    val destination2 = Destination(generateAddress(chainIndex), Amount(ALPH.alph(2)), Some(tokens))

    forAll(Gen.choose(1, 20)) { i =>
      val outputs = serverUtils
        .mergeAndprepareOutputInfos(
          AVector.fill(i)(AVector(destination, destination2)).flatMap(identity)
        )
        .rightValue
      outputs.length is 2
      outputs(0).attoAlphAmount is ALPH.alph(i.toLong)
      outputs(0).tokens is AVector(
        (tokenId1, U256.unsafe(i)),
        (tokenId2, U256.unsafe(2 * i.toLong))
      )
      outputs(1).attoAlphAmount is ALPH.alph(2 * i.toLong)
      outputs(1).tokens is AVector(
        (tokenId1, U256.unsafe(i)),
        (tokenId2, U256.unsafe(2 * i.toLong))
      )
    }
  }

  it should "separate destinations with lockTime or message" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex = ChainIndex.unsafe(0, 0)

    val destAddress = generateAddress(chainIndex)
    val destination = Destination(destAddress, Amount(ALPH.oneAlph))
    val destinationLockTime =
      Destination(destAddress, Amount(ALPH.oneAlph), lockTime = Some(TimeStamp.now()))
    val destinationMessage =
      Destination(destAddress, Amount(ALPH.oneAlph), message = Some(ByteString.empty))
    val destinationBoth = Destination(
      destAddress,
      Amount(ALPH.oneAlph),
      lockTime = Some(TimeStamp.now()),
      message = Some(ByteString.empty)
    )

    val destinations = AVector(
      destination,
      destination,
      destinationLockTime,
      destinationMessage,
      destinationBoth
    )

    val outputs = serverUtils.mergeAndprepareOutputInfos(destinations).rightValue
    outputs.length is 4
    outputs(0).attoAlphAmount is ALPH.alph(2)

    outputs(1).attoAlphAmount is ALPH.alph(1)
    outputs(1).lockTime.isDefined is true
    outputs(1).additionalDataOpt.isEmpty is true

    outputs(2).attoAlphAmount is ALPH.alph(1)
    outputs(2).lockTime.isEmpty is true
    outputs(2).additionalDataOpt.isDefined is true

    outputs(3).attoAlphAmount is ALPH.alph(1)
    outputs(3).lockTime.isDefined is true
    outputs(3).additionalDataOpt.isDefined is true
  }

  it should "work with only `complex` destinations" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex = ChainIndex.unsafe(0, 0)

    val destAddress = generateAddress(chainIndex)
    val destinationLockTime =
      Destination(destAddress, Amount(ALPH.oneAlph), lockTime = Some(TimeStamp.now()))
    val destinationMessage =
      Destination(destAddress, Amount(ALPH.oneAlph), message = Some(ByteString.empty))
    val destinationBoth = Destination(
      destAddress,
      Amount(ALPH.oneAlph),
      lockTime = Some(TimeStamp.now()),
      message = Some(ByteString.empty)
    )

    val destinations = AVector(
      destinationLockTime,
      destinationMessage,
      destinationBoth
    )

    val outputs = serverUtils.mergeAndprepareOutputInfos(destinations).rightValue
    outputs.length is 3

    outputs(0).attoAlphAmount is ALPH.alph(1)
    outputs(0).lockTime.isDefined is true
    outputs(0).additionalDataOpt.isEmpty is true

    outputs(1).attoAlphAmount is ALPH.alph(1)
    outputs(1).lockTime.isEmpty is true
    outputs(1).additionalDataOpt.isDefined is true

    outputs(2).attoAlphAmount is ALPH.alph(1)
    outputs(2).lockTime.isDefined is true
    outputs(2).additionalDataOpt.isDefined is true
  }

  trait CallContractFixture extends Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

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
         |  pub fn getContractId() -> ByteVec {
         |    return selfContractId!()
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
         |  pub fn getContractId() -> ByteVec {
         |    return selfContractId!()
         |  }
         |  pub fn getName() -> ByteVec {
         |    return b`Class` ++ b` ` ++ b`Foo`
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

  "ServerUtils.callContract" should "call contract" in new CallContractFixture {
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
    val callContractResult0 =
      serverUtils.callContract(blockFlow, params0).asInstanceOf[CallContractSucceeded]
    callContractResult0.returns is AVector[Val](ValU256(2))
    callContractResult0.gasUsed is 23206
    callContractResult0.txOutputs.length is 2
    val contractAttoAlphAmount0 = minimalAlphInContract + ALPH.nanoAlph(2)
    callContractResult0.txOutputs(0).attoAlphAmount.value is contractAttoAlphAmount0

    callContractResult0.contracts.length is 2
    val barState0 = callContractResult0.contracts(0)
    barState0.immFields is AVector.empty[Val]
    barState0.mutFields is AVector[Val](ValU256(2))
    barState0.address is barAddress
    barState0.asset is AssetState(minimalAlphInContract, Some(AVector.empty))
    val fooState0 = callContractResult0.contracts(1)
    barState0.immFields is AVector.empty[Val]
    fooState0.mutFields is AVector[Val](ValU256(2))
    fooState0.address is fooAddress
    fooState0.asset is AssetState(contractAttoAlphAmount0, Some(AVector.empty))

    info("call contract against the old world state")
    val params1 = params0.copy(worldStateBlockHash = Some(createContractBlock.hash))
    val callContractResult1 =
      serverUtils.callContract(blockFlow, params1).asInstanceOf[CallContractSucceeded]
    callContractResult1.returns is AVector[Val](ValU256(1))
    callContractResult1.gasUsed is 23206
    callContractResult1.txOutputs.length is 2
    val contractAttoAlphAmount1 = minimalAlphInContract + ALPH.oneNanoAlph
    callContractResult1.txOutputs(0).attoAlphAmount.value is contractAttoAlphAmount1

    callContractResult1.contracts.length is 2
    val barState1 = callContractResult1.contracts(0)
    barState1.immFields is AVector.empty[Val]
    barState1.mutFields is AVector[Val](ValU256(1))
    barState1.address is barAddress
    barState1.asset is AssetState(minimalAlphInContract, Some(AVector.empty))
    val fooState1 = callContractResult1.contracts(1)
    barState1.immFields is AVector.empty[Val]
    fooState1.mutFields is AVector[Val](ValU256(1))
    fooState1.address is fooAddress
    fooState1.asset is AssetState(contractAttoAlphAmount1, Some(AVector.empty))

    info("call getName method successfully")
    val params2 = params0.copy(methodIndex = 2)
    val callContractResult2 =
      serverUtils.callContract(blockFlow, params2).asInstanceOf[CallContractSucceeded]

    callContractResult2.returns is AVector[Val](ValByteVec(ByteString("Class Foo".getBytes())))
    callContractResult2.gasUsed is 5590
  }

  it should "multiple call contract" in new CallContractFixture {
    val groupIndex         = chainIndex.from.value
    val call0              = CallContract(group = groupIndex, address = barAddress, methodIndex = 1)
    val call1              = CallContract(group = groupIndex, address = fooAddress, methodIndex = 1)
    val invalidMethodIndex = 10
    val call2 =
      CallContract(group = groupIndex, address = fooAddress, methodIndex = invalidMethodIndex)
    val multipleCallContract = MultipleCallContract(AVector(call0, call1, call2))
    val multipleCallContractResult =
      serverUtils.multipleCallContract(blockFlow, multipleCallContract).rightValue
    multipleCallContractResult.results.length is 3

    val result0 = multipleCallContractResult.results(0).asInstanceOf[CallContractSucceeded]
    result0.txOutputs.isEmpty is true
    result0.events.isEmpty is true
    result0.returns is AVector[Val](ValByteVec(barId.bytes))

    val result1 = multipleCallContractResult.results(1).asInstanceOf[CallContractSucceeded]
    result1.txOutputs.isEmpty is true
    result1.events.isEmpty is true
    result1.returns is AVector[Val](ValByteVec(fooId.bytes))

    val result2 = multipleCallContractResult.results(2).asInstanceOf[CallContractFailed]
    result2.error is s"VM execution error: Invalid method index $invalidMethodIndex, method length: 3"
  }

  it should "returns error if the number of contract calls exceeds the maximum limit" in new CallContractFixture {
    override val serverUtils = new ServerUtils() {
      override val maxCallsInMultipleCall = 3
    }
    val groupIndex = chainIndex.from.value
    val call       = CallContract(group = groupIndex, address = barAddress, methodIndex = 1)
    val multipleCallContract = MultipleCallContract(AVector.fill(4)(call))
    serverUtils
      .multipleCallContract(blockFlow, multipleCallContract)
      .leftValue
      .detail is "The number of contract calls exceeds the maximum limit(3)"
  }

  it should "returns error if caller is in different group than contract" in new CallContractFixture {
    val params0 = CallContract(
      group = 1,
      address = fooAddress,
      methodIndex = 0,
      inputAssets = Some(AVector(inputAsset)),
      existingContracts = Some(AVector(barAddress))
    )
    val callContractResult0 =
      serverUtils.callContract(blockFlow, params0).asInstanceOf[CallContractFailed]
    callContractResult0.error is s"Group mismatch: provided group is 1; group for ${fooAddress.toBase58} is 0"
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

  it should "test destroying self" in new Fixture {
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

    val fooContract        = Compiler.compileContract(foo).rightValue
    val fooContractId      = ContractId.random
    val fooContractAddress = Address.contract(fooContractId)

    val testContractParams = TestContract(
      group = Some(groupIndex.value),
      address = Some(fooContractAddress),
      bytecode = fooContract,
      initialAsset = Some(AssetState(ALPH.alph(10))),
      existingContracts = None,
      inputAssets = None
    ).toComplete().rightValue

    val testFlow    = BlockFlow.emptyUnsafe(config)
    val serverUtils = new ServerUtils()
    val result =
      serverUtils.runTestContract(testFlow, testContractParams).rightValue

    result.contracts.isEmpty is true
    result.txInputs.length is 1
    result.txInputs(0).lockupScript is fooContractAddress.lockupScript
    result.txOutputs.length is 1
    result.txOutputs(0).attoAlphAmount.value is ALPH.alph(10)
    result.txOutputs(0).address is assetAddress
  }

  it should "test with caller address" in new Fixture {
    val child =
      s"""
         |Contract Child(parentId: ByteVec, parentAddress: Address) {
         |  pub fn checkParent() -> () {
         |    assert!(callerContractId!() == parentId, 0)
         |    assert!(callerAddress!() == parentAddress, 1)
         |  }
         |}
         |""".stripMargin
    val childContract = Compiler.compileContract(child).rightValue
    val childAddress  = Address.contract(ContractId.random)

    val parentContractId   = ContractId.random
    val parentAddress      = Address.contract(parentContractId)
    val wrongParentId      = ContractId.random
    val wrongParentAddress = Address.contract(wrongParentId)

    val serverUtils = new ServerUtils()

    def buildTestParam(callerAddressOpt: Option[Address.Contract]): TestContract.Complete = {
      TestContract(
        bytecode = childContract,
        address = Some(childAddress),
        callerAddress = callerAddressOpt,
        initialImmFields = Option(
          AVector[Val](
            ValByteVec(parentContractId.bytes),
            ValAddress(parentAddress)
          )
        )
      )
        .toComplete()
        .rightValue
    }
    val testContractParams0 = buildTestParam(Some(parentAddress))
    val testResult0         = serverUtils.runTestContract(blockFlow, testContractParams0)
    testResult0.isRight is true

    val testContractParams1 = buildTestParam(Some(wrongParentAddress))
    val testResult1         = serverUtils.runTestContract(blockFlow, testContractParams1)
    testResult1.leftValue.detail is s"VM execution error: Assertion Failed in Contract @ ${childAddress.toBase58}, Error Code: 0"

    val testContractParams2 = buildTestParam(None)
    val testResult2         = serverUtils.runTestContract(blockFlow, testContractParams2)
    testResult2.leftValue.detail is "VM execution error: ExpectAContract"
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
    lazy val randomAddress = Address.contract(ContractId.random).toBase58

    override def fooCaller: String =
      s"""
         |Contract FooCaller(fooId: ByteVec) {
         |  pub fn destroyFoo() -> () {
         |    let foo = Foo(fooId)
         |    foo.destroy(@${randomAddress})
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
      .detail is s"VM execution error: Pay to contract address $randomAddress is not allowed when this contract address is not in the call stack"
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
      s"> Contract @ ${Address.contract(testContract.contractId).toBase58} - Hello, Alephium!\n" ++
      "VM execution error: Assertion Failed in Contract @ tgx7VNFoP9DJiFMFgXXtafQZkUvyEdDHT9ryamHJYrjq, Error Code: 0"
  }

  ignore should "test blockHash function for Ralph" in new TestContractFixture {
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
      DebugMessage(Address.contract(testContract.contractId), "Hello, 416c65706869756d!")
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
    result0.gasUsed is 17469
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
      Amount(500000000000000000L),
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
    result1.gasUsed is 18578
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
      Amount(500000000000000000L),
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
    result0.gasUsed is 21978
    result0.contracts.length is 1
    val contractState = result0.contracts.head
    contractState.id is ContractId.zero
    contractState.immFields is AVector[Val](ValByteVec(tokenId.bytes))
    contractState.mutFields is AVector[Val](ValU256(ALPH.alph(20)), ValU256(50))
    contractState.asset is AssetState.from(ALPH.alph(20), AVector(Token(tokenId, 50)))
    result0.txInputs is AVector[Address](contractAddress)
    result0.txOutputs.length is 3
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
      Amount(dustUtxoAmount),
      buyer,
      AVector(Token(tokenId, 150)),
      TimeStamp.zero,
      ByteString.empty
    )
    result0.txOutputs(2) is AssetOutput(
      result0.txOutputs(2).hint,
      emptyKey(2),
      Amount(ALPH.nanoAlph(90500000000L) - dustUtxoAmount),
      buyer,
      AVector.empty,
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
    result1.gasUsed is 18549
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
      Amount(ALPH.nanoAlph(110500000000L)),
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
    result0.gasUsed is 17478
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
      Amount(ALPH.nanoAlph(105500000000L)),
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
    result1.gasUsed is 23010
    result1.contracts.length is 2
    val contractState1 = result1.contracts.head
    contractState1.id is ContractId.zero
    contractState1.immFields is AVector[Val](ValByteVec(tokenId.bytes))
    contractState1.mutFields is AVector[Val](ValU256(ALPH.alph(10)), ValU256(100))
    contractState1.asset is AssetState.from(ALPH.alph(10), AVector(Token(tokenId, 100)))
    result1.txInputs is AVector[Address](contractAddress)
    result1.txOutputs.length is 3
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
      Amount(dustUtxoAmount),
      lp,
      AVector(Token(tokenId, 100)),
      TimeStamp.zero,
      ByteString.empty
    )
    result1.txOutputs(2) is AssetOutput(
      result1.txOutputs(2).hint,
      emptyKey(2),
      Amount(ALPH.nanoAlph(95500000000L) - dustUtxoAmount),
      lp,
      AVector.empty,
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

  it should "test the contract by the specified block timestamp" in new Fixture {
    val blockTimeStamp = TimeStamp.now().plusMinutesUnsafe(5)
    val contract =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> U256 {
         |    return blockTimeStamp!()
         |  }
         |}
         |""".stripMargin

    val code = Compiler.compileContract(contract).rightValue
    val testContract0 =
      TestContract(bytecode = code, blockTimeStamp = Some(blockTimeStamp)).toComplete().rightValue
    val serverUtils = new ServerUtils()
    val testResult0 = serverUtils.runTestContract(blockFlow, testContract0).rightValue
    testResult0.returns is AVector[Val](ValU256(U256.unsafe(blockTimeStamp.millis)))

    val testContract1 = TestContract(bytecode = code).toComplete().rightValue
    val testResult1   = serverUtils.runTestContract(blockFlow, testContract1).rightValue
    testResult1.returns isnot AVector[Val](ValU256(U256.unsafe(blockTimeStamp.millis)))
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
      val bytecode     = "010000010008d38d0b36360c17000dce01300c7b"
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
    val (contracts, scripts, _) = Compiler.compileProject(rawCode).rightValue
    val query                   = Compile.Project(rawCode)
    val result                  = serverUtils.compileProject(query).rightValue

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

  it should "compile contract and return the std id field" in new Fixture {
    def code(contractAnnotation: String, interfaceAnnotation: String) =
      s"""
         |$contractAnnotation
         |Contract Bar(@unused a: U256) implements Foo {
         |  pub fn foo() -> () {}
         |}
         |
         |$interfaceAnnotation
         |Interface Foo {
         |  pub fn foo() -> ()
         |}
         |""".stripMargin

    val serverUtils = new ServerUtils()
    val result0     = serverUtils.compileContract(Compile.Contract(code("", ""))).rightValue
    result0.fields is CompileResult.FieldsSig(AVector("a"), AVector("U256"), AVector(false))
    result0.stdInterfaceId is None

    val result1 =
      serverUtils.compileContract(Compile.Contract(code("", "@std(id = #0001)"))).rightValue
    result1.fields is CompileResult.FieldsSig(
      AVector("a", "__stdInterfaceId"),
      AVector("U256", "ByteVec"),
      AVector(false, false)
    )
    result1.stdInterfaceId is Some("0001")

    val result2 = serverUtils
      .compileContract(Compile.Contract(code("@std(enabled = true)", "@std(id = #0001)")))
      .rightValue
    result2.fields is CompileResult.FieldsSig(
      AVector("a", "__stdInterfaceId"),
      AVector("U256", "ByteVec"),
      AVector(false, false)
    )
    result2.stdInterfaceId is Some("0001")

    val result3 = serverUtils
      .compileContract(Compile.Contract(code("@std(enabled = false)", "@std(id = #0001)")))
      .rightValue
    result3.fields is CompileResult.FieldsSig(AVector("a"), AVector("U256"), AVector(false))
    result3.stdInterfaceId is None

    val result4 = serverUtils
      .compileContract(Compile.Contract(code("@std(enabled = true)", "")))
      .rightValue
    result4.fields is CompileResult.FieldsSig(AVector("a"), AVector("U256"), AVector(false))
    result4.stdInterfaceId is None

    val result5 = serverUtils
      .compileContract(Compile.Contract(code("@std(enabled = false)", "")))
      .rightValue
    result5.fields is CompileResult.FieldsSig(AVector("a"), AVector("U256"), AVector(false))
    result5.stdInterfaceId is None
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
    val contract              = Compiler.compileContract(rawCode).rightValue
    val (_, fromPublicKey, _) = genesisKeys(0)
    val fromAddress           = Address.p2pkh(fromPublicKey)
    val (_, toPublicKey, _)   = genesisKeys(1)
    val toAddress             = Address.p2pkh(toPublicKey)

    {
      info("Without token issuance")
      val codeRaw                        = Hex.toHexString(serialize(contract))
      val initialFields: AVector[vm.Val] = AVector(vm.Val.U256.unsafe(0))
      val stateRaw                       = Hex.toHexString(serialize(initialFields))

      val expected =
        s"""
           |TxScript Main {
           |  createContract!{@$fromAddress -> ALPH: 10}(#$codeRaw, #$stateRaw, #00)
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
          None
        ) is expected
    }

    {
      info("Issue token and transfer to an address")
      val codeRaw                        = Hex.toHexString(serialize(contract))
      val initialFields: AVector[vm.Val] = AVector(vm.Val.U256.unsafe(0))
      val stateRaw                       = Hex.toHexString(serialize(initialFields))

      val expected =
        s"""
           |TxScript Main {
           |  createContractWithToken!{@$fromAddress -> ALPH: 10}(#$codeRaw, #$stateRaw, #00, 50, @$toAddress)
           |  transferToken!{@$fromAddress -> ALPH: dustAmount!()}(@$fromAddress, @$toAddress, ALPH, dustAmount!())
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
          Some((U256.unsafe(50), Some(toAddress)))
        ) is expected
    }

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
          Some((U256.unsafe(50), None))
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
          Some((U256.unsafe(50), None))
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
        Some(AVector.fill(2 * maxTokenPerAssetUtxo)(Token(TokenId.random, 1)))
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
    testResult.txOutputs.length is 5
    testResult.txOutputs(0).address is caller
    testResult.txOutputs(0).tokens.length is maxTokenPerAssetUtxo
    testResult.txOutputs(0).tokens is tokensSorted.slice(0, maxTokenPerAssetUtxo)
    testResult.txOutputs(1).address is caller
    testResult.txOutputs(1).tokens.length is maxTokenPerAssetUtxo
    testResult.txOutputs(1).tokens is tokensSorted
      .slice(maxTokenPerAssetUtxo, 2 * maxTokenPerAssetUtxo)
    testResult.txOutputs(2).address is caller
    testResult.txOutputs(2).tokens.length is 1
    testResult.txOutputs(2).tokens is tokensSorted.slice(
      2 * maxTokenPerAssetUtxo,
      tokensSorted.length
    )
    testResult.txOutputs(4).address is Address.contract(testContract.contractId)
  }

  trait ScriptTxFixture extends Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils = new ServerUtils

    val chainIndex                        = ChainIndex.unsafe(0, 0)
    val (testPriKey, testPubKey)          = chainIndex.from.generateKey
    val testAddress                       = Address.p2pkh(testPubKey)
    val (genesisPriKey, genesisPubKey, _) = genesisKeys(0)
    val genesisAddress                    = Address.p2pkh(genesisPubKey)

    val contract =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val code = Compiler.compileContract(contract).toOption.get

    lazy val deployContractTxResult = serverUtils
      .buildDeployContractTx(
        blockFlow,
        BuildDeployContractTx(
          Hex.unsafe(testPubKey.toHexString),
          bytecode = serialize(code) ++ ByteString(0, 0),
          initialAttoAlphAmount = Some(Amount(ALPH.oneAlph))
        )
      )
      .rightValue

    def deployContract() = {
      val deployContractTx =
        deserialize[UnsignedTransaction](Hex.unsafe(deployContractTxResult.unsignedTx)).rightValue
      deployContractTx.fixedOutputs.length is 1
      val output = deployContractTx.fixedOutputs.head
      output.amount is ALPH.oneAlph.subUnsafe(
        deployContractTx.gasPrice * deployContractTx.gasAmount
      )

      signAndAddToMemPool(
        deployContractTxResult.txId,
        deployContractTxResult.unsignedTx,
        chainIndex,
        testPriKey
      )
    }

    def confirmNewBlock(blockFlow: BlockFlow, chainIndex: ChainIndex) = {
      val block = mineFromMemPool(blockFlow, chainIndex)
      block.nonCoinbase.foreach(_.scriptExecutionOk is true)
      addAndCheck(blockFlow, block)
    }
  }

  trait DustAmountFixture extends Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))
    implicit val serverUtils  = new ServerUtils

    val chainIndex      = ChainIndex.unsafe(0, 0)
    val (_, testPubKey) = chainIndex.from.generateKey
    val testAddress     = Address.p2pkh(testPubKey)

    val script =
      s"""
         |TxScript Foo {
         |  emit Debug(`Hey, I am Foo`)
         |}
         |""".stripMargin
    val code = Compiler.compileTxScript(script).toOption.get

    val gasAmount = GasBox.unsafe(30000)
    val gasPrice  = nonCoinbaseMinGasPrice
    val gasFee    = gasPrice * gasAmount

    def attoAlphAmount: Option[Amount]

    val alphPerUTXO = dustUtxoAmount + gasFee + attoAlphAmount.getOrElse(Amount.Zero).value - 1
    val block1      = transfer(blockFlow, genesisKeys(1)._1, testPubKey, alphPerUTXO)
    addAndCheck(blockFlow, block1)
    checkAddressBalance(testAddress, alphPerUTXO, utxoNum = 1)

    val block2 = transfer(blockFlow, genesisKeys(1)._1, testPubKey, alphPerUTXO)
    addAndCheck(blockFlow, block2)
    checkAddressBalance(testAddress, alphPerUTXO * 2, utxoNum = 2)

    def executeTxScript() = {
      serverUtils
        .buildExecuteScriptTx(
          blockFlow,
          BuildExecuteScriptTx(
            fromPublicKey = Hex.unsafe(testPubKey.toHexString),
            bytecode = serialize(code),
            gasAmount = Some(gasAmount),
            gasPrice = Some(gasPrice),
            attoAlphAmount = attoAlphAmount
          )
        )
        .rightValue
    }
  }

  trait GasFeeFixture extends Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils = new ServerUtils

    val chainIndex                        = ChainIndex.unsafe(0, 0)
    val (testPriKey, testPubKey)          = chainIndex.from.generateKey
    val testAddress                       = Address.p2pkh(testPubKey)
    val (genesisPriKey, genesisPubKey, _) = genesisKeys(0)
    val genesisAddress                    = Address.p2pkh(genesisPubKey)

    def confirmNewBlock(blockFlow: BlockFlow, chainIndex: ChainIndex) = {
      val block = mineFromMemPool(blockFlow, chainIndex)
      block.nonCoinbase.foreach(_.scriptExecutionOk is true)
      addAndCheck(blockFlow, block)
    }

    def executeScript(
        rawScript: String,
        keyPairOpt: Option[(PrivateKey, PublicKey)] = None
    ) = {
      val script = Compiler.compileTxScript(rawScript).toOption.get
      val block  = payableCall(blockFlow, chainIndex, script, keyPairOpt = keyPairOpt)
      addAndCheck(blockFlow, block)
      block
    }

    def deployContract(
        contract: String,
        initialAttoAlphAmount: Amount = Amount(ALPH.alph(3))
    ): Address.Contract = {
      val code = Compiler.compileContract(contract).toOption.get

      val deployContractTxResult = serverUtils
        .buildDeployContractTx(
          blockFlow,
          BuildDeployContractTx(
            Hex.unsafe(testPubKey.toHexString),
            bytecode = serialize(code) ++ ByteString(0, 0),
            initialAttoAlphAmount = Some(initialAttoAlphAmount)
          )
        )
        .rightValue

      val deployContractTx =
        deserialize[UnsignedTransaction](Hex.unsafe(deployContractTxResult.unsignedTx)).rightValue
      deployContractTx.fixedOutputs.length is 1

      val testAddressAlphBalance = getAlphBalance(blockFlow, LockupScript.p2pkh(testPubKey))
      signAndAddToMemPool(
        deployContractTxResult.txId,
        deployContractTxResult.unsignedTx,
        chainIndex,
        testPriKey
      )

      confirmNewBlock(blockFlow, ChainIndex.unsafe(1, 1))
      confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 0))
      serverUtils.getTransaction(blockFlow, deployContractTxResult.txId, None, None).rightValue

      // Check that gas is paid correctly
      getAlphBalance(blockFlow, LockupScript.p2pkh(testPubKey)) is testAddressAlphBalance.subUnsafe(
        deployContractTx.gasPrice * deployContractTx.gasAmount + initialAttoAlphAmount.value
      )
      deployContractTxResult.contractAddress

    }

    val testAddressBalance = ALPH.alph(1000)
    val block              = transfer(blockFlow, genesisKeys(1)._1, testPubKey, testAddressBalance)
    addAndCheck(blockFlow, block)
    checkAddressBalance(testAddress, testAddressBalance)

    def scriptCaller = genesisAddress

    def fooContract: String =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo() -> () {
         |    payGasFee!(selfAddress!(), txGasFee!())
         |  }
         |}
         |""".stripMargin

    def fooContractConditional: String =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo(contractPay: Bool) -> () {
         |    if (contractPay) {
         |      payGasFee!(selfAddress!(), txGasFee!())
         |    }
         |  }
         |}
         |""".stripMargin
  }

  it should "should throw exception for payGasFee instr before Rhone hardfork" in new GasFeeFixture {
    implicit override lazy val networkConfig: NetworkSetting = config.network.copy(
      rhoneHardForkTimestamp = TimeStamp.unsafe(Long.MaxValue)
    )

    val contractAddress = deployContract(fooContract)

    def script =
      s"""
         |TxScript Main {
         |  Foo(#${contractAddress.toBase58}).foo()
         |}
         |
         |$fooContract
         |""".stripMargin

    val exception = intercept[AssertionError](executeScript(script))
    exception.getMessage() is "Right(TxScriptExeFailed(InactiveInstr(PayGasFee)))"
  }

  it should "not charge caller gas fee when contract is paying gas" in new GasFeeFixture {
    val contractAddress = deployContract(fooContract)

    def script =
      s"""
         |TxScript Main {
         |  Foo(#${contractAddress.toBase58}).foo()
         |}
         |
         |$fooContract
         |""".stripMargin

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))
    checkAddressBalance(contractAddress, ALPH.alph(3))

    val scriptBlock    = executeScript(script)
    val scriptTxGasFee = scriptBlock.nonCoinbase.head.gasFeeUnsafe

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))
    checkAddressBalance(contractAddress, ALPH.alph(3).subUnsafe(scriptTxGasFee))
  }

  it should "charge caller gas fee when contract is not paying gas fee" in new GasFeeFixture {
    def contract: String =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo() -> () {
         |    transferTokenFromSelf!(callerAddress!(), ALPH, 1 alph)
         |  }
         |}
         |""".stripMargin

    val contractAddress = deployContract(contract)

    def script =
      s"""
         |TxScript Main {
         |  Foo(#${contractAddress.toBase58}).foo()
         |}
         |
         |$contract
         |""".stripMargin

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))
    checkAddressBalance(contractAddress, ALPH.alph(3))

    val scriptBlock    = executeScript(script)
    val scriptTxGasFee = scriptBlock.nonCoinbase.head.gasFeeUnsafe

    checkAddressBalance(
      scriptCaller,
      ALPH.alph(1000000).addUnsafe(ALPH.oneAlph).subUnsafe(scriptTxGasFee)
    )
    checkAddressBalance(contractAddress, ALPH.alph(2))
  }

  it should "charge caller gas fee when paying full gas fee in the TxScript" in new GasFeeFixture {
    def script =
      s"""
         |TxScript Main {
         |  payGasFee!(callerAddress!(), txGasFee!())
         |}
         |
         |""".stripMargin

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))

    val scriptBlock    = executeScript(script)
    val scriptTxGasFee = scriptBlock.nonCoinbase.head.gasFeeUnsafe

    checkAddressBalance(
      scriptCaller,
      ALPH.alph(1000000).subUnsafe(scriptTxGasFee)
    )
  }

  it should "charge caller gas fee when paying partial gas fee in the TxScript" in new GasFeeFixture {
    val halfGasFee = nonCoinbaseMinGasFee.divUnsafe(2)
    def script =
      s"""
         |TxScript Main {
         |  payGasFee!(callerAddress!(), ${halfGasFee})
         |}
         |
         |""".stripMargin

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))

    val scriptBlock    = executeScript(script)
    val scriptTxGasFee = scriptBlock.nonCoinbase.head.gasFeeUnsafe

    checkAddressBalance(
      scriptCaller,
      ALPH.alph(1000000).subUnsafe(scriptTxGasFee)
    )
  }

  it should "charge caller gas fee when paying gas fee using multiple payGasFee function the TxScript" in new GasFeeFixture {
    val halfGasFee = nonCoinbaseMinGasFee.divUnsafe(2)
    def script =
      s"""
         |TxScript Main {
         |  payGasFee!(callerAddress!(), ${halfGasFee})
         |  payGasFee!(callerAddress!(), ${halfGasFee})
         |  payGasFee!(callerAddress!(), ${halfGasFee})
         |}
         |
         |""".stripMargin

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))

    val scriptBlock    = executeScript(script)
    val scriptTxGasFee = scriptBlock.nonCoinbase.head.gasFeeUnsafe

    checkAddressBalance(
      scriptCaller,
      ALPH.alph(1000000).subUnsafe(scriptTxGasFee)
    )
  }

  it should "not charge caller gas fee when contract is paying gas conditionally" in new GasFeeFixture {
    val contractAddress = deployContract(fooContractConditional)

    def script =
      s"""
         |TxScript Main {
         |  Foo(#${contractAddress.toBase58}).foo(true)
         |}
         |
         |$fooContractConditional
         |""".stripMargin

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))
    checkAddressBalance(contractAddress, ALPH.alph(3))

    val scriptBlock    = executeScript(script)
    val scriptTxGasFee = scriptBlock.nonCoinbase.head.gasFeeUnsafe

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))
    checkAddressBalance(contractAddress, ALPH.alph(3).subUnsafe(scriptTxGasFee))
  }

  it should "charge caller gas fee when contract is not paying gas conditionally" in new GasFeeFixture {
    val contractAddress = deployContract(fooContractConditional)

    def script =
      s"""
         |TxScript Main {
         |  Foo(#${contractAddress.toBase58}).foo(false)
         |}
         |
         |$fooContractConditional
         |""".stripMargin

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))
    checkAddressBalance(contractAddress, ALPH.alph(3))

    val scriptBlock    = executeScript(script)
    val scriptTxGasFee = scriptBlock.nonCoinbase.head.gasFeeUnsafe

    checkAddressBalance(scriptCaller, ALPH.alph(1000000).subUnsafe(scriptTxGasFee))
    checkAddressBalance(contractAddress, ALPH.alph(3))
  }

  it should "charge caller gas fee when contract doen't have enough to pay gas fee" in new GasFeeFixture {
    def contract: String =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo(contractPay: Bool) -> () {
         |    if (contractPay) {
         |      payGasFee!(selfAddress!(), 0)
         |    }
         |  }
         |}
         |""".stripMargin

    val contractAddress = deployContract(contract, Amount(ALPH.alph(1)))

    def script =
      s"""
         |TxScript Main {
         |  Foo(#${contractAddress.toBase58}).foo(true)
         |}
         |
         |$contract
         |""".stripMargin

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))
    checkAddressBalance(contractAddress, ALPH.alph(1))

    val scriptBlock    = executeScript(script)
    val scriptTxGasFee = scriptBlock.nonCoinbase.head.gasFeeUnsafe

    checkAddressBalance(
      scriptCaller,
      ALPH.alph(1000000) subUnsafe scriptTxGasFee
    )
    checkAddressBalance(contractAddress, ALPH.alph(1))
  }

  it should "charge caller partial gas fee when contract can also pay partial gas fee" in new GasFeeFixture {
    val partialGasFee = nonCoinbaseMinGasFee.divUnsafe(2)

    def contract: String =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo(contractPay: Bool) -> () {
         |    transferTokenFromSelf!(callerAddress!(), ALPH, 1 alph)
         |    if (contractPay) {
         |      payGasFee!(selfAddress!(), 2)
         |    }
         |  }
         |}
         |""".stripMargin

    val contractAddress = deployContract(contract, Amount(ALPH.alph(2).addUnsafe(partialGasFee)))

    def script =
      s"""
         |TxScript Main {
         |  payGasFee!(callerAddress!(), 100)
         |  Foo(#${contractAddress.toBase58}).foo(true)
         |}
         |
         |$contract
         |""".stripMargin

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))
    checkAddressBalance(contractAddress, ALPH.alph(2).addUnsafe(partialGasFee))

    val scriptBlock    = executeScript(script)
    val scriptTxGasFee = scriptBlock.nonCoinbase.head.gasFeeUnsafe

    checkAddressBalance(
      scriptCaller,
      ALPH.alph(1000000).addUnsafe(ALPH.oneAlph).subUnsafe(scriptTxGasFee.subUnsafe(U256.Two))
    )
    checkAddressBalance(contractAddress, ALPH.alph(1).addUnsafe(partialGasFee).subUnsafe(2))
  }

  it should "charge the contract that has enough balance for gas" in new GasFeeFixture {
    val partialGasFee = nonCoinbaseMinGasFee.divUnsafe(2)

    def barContract: String =
      s"""
         |Contract Bar() {
         |  @using(assetsInContract = true)
         |  pub fn bar() -> () {
         |    payGasFee!(selfAddress!(), ${partialGasFee})
         |  }
         |}
         |""".stripMargin

    val barContractAddress =
      deployContract(barContract, Amount(ALPH.alph(1).addUnsafe(partialGasFee)))

    override def fooContract: String =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo() -> () {
         |    transferTokenFromSelf!(callerAddress!(), ALPH, 1 alph)
         |    payGasFee!(selfAddress!(), 0)
         |    Bar(#${barContractAddress.toBase58}).bar()
         |  }
         |}
         |
         |$barContract
         |""".stripMargin

    val fooContractAddress = deployContract(fooContract, Amount(ALPH.alph(2)))

    def script =
      s"""
         |TxScript Main {
         |  Foo(#${fooContractAddress.toBase58}).foo()
         |}
         |
         |$fooContract
         |""".stripMargin

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))
    checkAddressBalance(fooContractAddress, ALPH.alph(2))
    checkAddressBalance(barContractAddress, ALPH.alph(1).addUnsafe(partialGasFee))

    val scriptBlock    = executeScript(script)
    val scriptTxGasFee = scriptBlock.nonCoinbase.head.gasFeeUnsafe

    checkAddressBalance(
      scriptCaller,
      ALPH.alph(1000000).addUnsafe(ALPH.alph(1)).subUnsafe(scriptTxGasFee.subUnsafe(partialGasFee))
    )
    checkAddressBalance(fooContractAddress, ALPH.alph(1))
    checkAddressBalance(barContractAddress, ALPH.alph(1))
  }

  it should "split gas fee between contract and caller depending on approved token amount" in new GasFeeFixture {
    def contract: String =
      s"""
         |Contract Foo() {
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn foo() -> () {
         |    payGasFee!(selfAddress!(), txGasFee!() / 2)
         |    payGasFee!(callerAddress!(), txGasFee!() / 2)
         |  }
         |}
         |""".stripMargin

    val contractAddress = deployContract(contract)

    def script =
      s"""
         |TxScript Main {
         |  Foo(#${contractAddress.toBase58}).foo{callerAddress!() -> ALPH: txGasFee!() / 2}()
         |}
         |
         |$contract
         |""".stripMargin

    checkAddressBalance(scriptCaller, ALPH.alph(1000000))
    checkAddressBalance(contractAddress, ALPH.alph(3))

    val scriptBlock    = executeScript(script)
    val scriptTxGasFee = scriptBlock.nonCoinbase.head.gasFeeUnsafe

    checkAddressBalance(scriptCaller, ALPH.alph(1000000).subUnsafe(scriptTxGasFee / 2))
    checkAddressBalance(contractAddress, ALPH.alph(3).subUnsafe(scriptTxGasFee / 2))
  }

  it should "fail if caller doesn't have enough to pay for gas even if contract pays for it" in new GasFeeFixture {
    val contractAddress = deployContract(fooContract)

    def script =
      s"""
         |TxScript Main {
         |  Foo(#${contractAddress.toBase58}).foo()
         |}
         |
         |$fooContract
         |""".stripMargin

    {
      info("Caller doesn't have enough to pay for gas")
      val (testPriKey, testPubKey) = chainIndex.from.generateKey
      val testAddress              = Address.p2pkh(testPubKey)

      val block1 = transfer(blockFlow, genesisPriKey, testPubKey, dustUtxoAmount)
      addAndCheck(blockFlow, block1)
      checkAddressBalance(testAddress, dustUtxoAmount)

      val exception =
        intercept[AssertionError](executeScript(script, Some((testPriKey, testPubKey))))
      exception.getMessage() is "Right(InvalidRemainingBalancesForFailedScriptTx)"
    }

    {
      info("Caller has no inputs")
      val (testPriKey, testPubKey) = chainIndex.from.generateKey

      checkAddressBalance(contractAddress, ALPH.alph(3))
      val exception =
        intercept[AssertionError](executeScript(script, Some((testPriKey, testPubKey))))
      exception.getMessage() is "Right(InvalidInputGroupIndex)"
    }
  }

  it should "considers dustAmount for change output when selecting UTXOs, without amounts" in new DustAmountFixture {
    override def attoAlphAmount: Option[Amount] = None
    executeTxScript()
  }

  it should "considers dustAmount for change output when selecting UTXOs, with amounts" in new DustAmountFixture {
    override def attoAlphAmount: Option[Amount] = Some(Amount(U256.unsafe(10)))
    executeTxScript()
  }

  it should "execute scripts for cross-group confirmed inputs" in new ScriptTxFixture {
    val block = transfer(blockFlow, genesisKeys(1)._1, testPubKey, ALPH.alph(2))
    addAndCheck(blockFlow, block)
    checkAddressBalance(testAddress, ALPH.alph(2))
    deployContract()
    blockFlow.getGrandPool().get(deployContractTxResult.txId).isEmpty is false
    confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 0))
    blockFlow.getGrandPool().get(deployContractTxResult.txId).isEmpty is false
    confirmNewBlock(blockFlow, ChainIndex.unsafe(1, 1))
    blockFlow.getGrandPool().get(deployContractTxResult.txId).isEmpty is false
    confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 0))
    blockFlow.getGrandPool().get(deployContractTxResult.txId).isEmpty is true
  }

  it should "execute scripts for cross-group mempool inputs" in new ScriptTxFixture {
    val block   = transfer(blockFlow, genesisKeys(1)._1, testPubKey, ALPH.alph(2))
    val blockTx = block.nonCoinbase.head.toTemplate
    block.chainIndex is ChainIndex.unsafe(1, 0)
    blockFlow
      .getGrandPool()
      .add(block.chainIndex, blockTx, TimeStamp.now())
    checkAddressBalance(testAddress, ALPH.alph(2))
    deployContract()
    blockFlow.getGrandPool().get(blockTx.id).isEmpty is false
    confirmNewBlock(blockFlow, ChainIndex.unsafe(1, 0))
    blockFlow.getGrandPool().get(blockTx.id).isEmpty is false
    // TODO: improve the calculation of bestDeps to get rid of the following line
    confirmNewBlock(blockFlow, ChainIndex.unsafe(1, 1))
    blockFlow.getGrandPool().get(blockTx.id).isEmpty is true

    blockFlow.getGrandPool().get(deployContractTxResult.txId).isEmpty is false
    confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 0))
    blockFlow.getGrandPool().get(deployContractTxResult.txId).isEmpty is true
  }

  trait ContractDeploymentFixture extends Fixture {
    val chainIndex                 = ChainIndex.unsafe(0, 0)
    val lockupScript               = getGenesisLockupScript(chainIndex)
    val (privateKey, publicKey, _) = genesisKeys(chainIndex.from.value)
    val (_, toPublicKey)           = chainIndex.from.generateKey
    val code =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin
    val contract = Compiler.compileContract(code).rightValue

    implicit val serverUtils = new ServerUtils()
    def buildDeployContractTx(query: BuildDeployContractTx): BuildDeployContractTxResult = {
      val result = serverUtils.buildDeployContractTx(blockFlow, query).rightValue
      signAndAddToMemPool(result.txId, result.unsignedTx, chainIndex, privateKey)
      val block = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      result
    }

    def checkBalance(
        lockupScript: LockupScript,
        expectedAlphBalance: U256,
        tokenId: TokenId,
        expectedTokenBalance: Option[U256]
    ) = {
      val (alphAmount, _, tokens, _, _) =
        blockFlow.getBalance(lockupScript, defaultUtxoLimit, true).rightValue
      expectedAlphBalance is alphAmount
      tokens.find(_._1 == tokenId).map(_._2) is expectedTokenBalance
    }
  }

  it should "deploy contract with preapproved assets" in new ContractDeploymentFixture {
    def createToken(amount: U256): ContractId = {
      val issuanceInfo = Some(TokenIssuance.Info(vm.Val.U256(amount), Some(lockupScript)))
      val script =
        contractCreation(
          contract,
          AVector.empty,
          AVector.empty,
          lockupScript,
          minimalAlphInContract,
          issuanceInfo
        )
      val block = payableCall(blockFlow, chainIndex, script)
      addAndCheck(blockFlow, block)
      ContractId.from(block.transactions.head.id, 0, chainIndex.from)
    }

    val tokenId = TokenId.from(createToken(10))
    val (_, _, tokens0, _, _) =
      blockFlow.getBalance(lockupScript, defaultUtxoLimit, true).rightValue
    tokens0.find(_._1 == tokenId).map(_._2) is Some(U256.unsafe(10))

    val query = BuildDeployContractTx(
      fromPublicKey = publicKey.bytes,
      bytecode = serialize(contract) ++ ByteString(0, 0),
      initialAttoAlphAmount = Some(Amount(ALPH.alph(2))),
      initialTokenAmounts = Some(AVector(Token(tokenId, U256.unsafe(4))))
    )

    val result = buildDeployContractTx(query)

    checkBalance(
      LockupScript.P2C(result.contractAddress.contractId),
      ALPH.alph(2),
      tokenId,
      Some(U256.unsafe(4))
    )
  }

  it should "deploy contract with token issuance" in new ContractDeploymentFixture {
    val query = BuildDeployContractTx(
      fromPublicKey = publicKey.bytes,
      bytecode = serialize(contract) ++ ByteString(0, 0),
      initialAttoAlphAmount = Some(Amount(ALPH.alph(2))),
      issueTokenAmount = Some(Amount(U256.unsafe(10))),
      issueTokenTo = Some(Address.p2pkh(toPublicKey))
    )

    val result  = buildDeployContractTx(query)
    val tokenId = TokenId.from(result.contractAddress.contractId)

    checkBalance(
      LockupScript.P2C(result.contractAddress.contractId),
      ALPH.alph(2),
      tokenId,
      None
    )

    checkBalance(
      LockupScript.p2pkh(toPublicKey),
      dustUtxoAmount,
      tokenId,
      Some(U256.unsafe(10))
    )
  }

  it should "fail when `issueTokenTo` is specified but `issueTokenAmount` is not" in new ContractDeploymentFixture {
    val query = BuildDeployContractTx(
      fromPublicKey = publicKey.bytes,
      bytecode = serialize(contract) ++ ByteString(0, 0),
      initialAttoAlphAmount = Some(Amount(ALPH.alph(2))),
      issueTokenAmount = None,
      issueTokenTo = Some(Address.p2pkh(publicKey))
    )

    serverUtils.buildDeployContractTx(blockFlow, query) is Left(
      ApiError.BadRequest(
        "`issueTokenTo` is specified, but `issueTokenAmount` is not specified"
      )
    )
  }

  it should "get ghost uncles" in new Fixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block0     = emptyBlock(blockFlow, chainIndex)
    val block1     = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block0, block1)
    val block2 = mineBlockTemplate(blockFlow, chainIndex)
    addAndCheck(blockFlow, block2)
    blockFlow.getMaxHeightByWeight(chainIndex).rightValue is 2

    val ghostUncleHash  = blockFlow.getHashes(chainIndex, 1).rightValue.last
    val ghostUncleBlock = blockFlow.getBlock(ghostUncleHash).rightValue
    val serverUtils     = new ServerUtils()
    serverUtils.getBlock(blockFlow, block2.hash).rightValue.ghostUncles is
      AVector(
        GhostUncleBlockEntry(ghostUncleHash, Address.Asset(ghostUncleBlock.minerLockupScript))
      )
  }

  it should "get mainchain block by ghost uncle hash" in new Fixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block0     = emptyBlock(blockFlow, chainIndex)
    val block1     = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block0, block1)
    blockFlow.getMaxHeightByWeight(chainIndex).rightValue is 1

    val serverUtils    = new ServerUtils()
    val ghostUncleHash = blockFlow.getHashes(chainIndex, 1).rightValue.last
    val blockNum       = Random.between(0, ALPH.MaxGhostUncleAge)
    (0 until blockNum).foreach { _ =>
      val block = emptyBlock(blockFlow, chainIndex)
      block.ghostUncleHashes.rightValue.isEmpty is true
      addAndCheck(blockFlow, block)
    }
    serverUtils.getMainChainBlockByGhostUncle(blockFlow, ghostUncleHash).leftValue.detail is
      s"The mainchain block that references the ghost uncle block ${ghostUncleHash.toHexString} not found"

    val block = mineBlockTemplate(blockFlow, chainIndex)
    block.ghostUncleHashes.rightValue is AVector(ghostUncleHash)
    addAndCheck(blockFlow, block)
    val blockHeight = blockNum + 2
    serverUtils.getMainChainBlockByGhostUncle(blockFlow, ghostUncleHash).rightValue is
      BlockEntry.from(block, blockHeight).rightValue

    val invalidBlockHash = randomBlockHash(chainIndex)
    serverUtils.getMainChainBlockByGhostUncle(blockFlow, invalidBlockHash).leftValue.detail is
      s"The block ${invalidBlockHash.toHexString} does not exist, please check if your full node synced"
    serverUtils.getMainChainBlockByGhostUncle(blockFlow, block.hash).leftValue.detail is
      s"The block ${block.hash.toHexString} is not a ghost uncle block, you should use a ghost uncle block hash to call this endpoint"
  }

  it should "return error if the block does not exist" in new Fixture {
    val chainIndex       = ChainIndex.unsafe(0, 0)
    val serverUtils      = new ServerUtils()
    val invalidBlockHash = randomBlockHash(chainIndex)
    val block            = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)
    serverUtils.getBlock(blockFlow, block.hash).rightValue is BlockEntry.from(block, 1).rightValue
    serverUtils.getBlock(blockFlow, invalidBlockHash).leftValue.detail is
      s"The block ${invalidBlockHash.toHexString} does not exist, please check if your full node synced"

    serverUtils.getBlockHeader(blockFlow, block.hash).rightValue is BlockHeaderEntry.from(
      block.header,
      1
    )
    serverUtils.getBlockHeader(blockFlow, invalidBlockHash).leftValue.detail is
      s"The block ${invalidBlockHash.toHexString} does not exist, please check if your full node synced"

    serverUtils.isBlockInMainChain(blockFlow, block.hash).rightValue is true
    serverUtils.isBlockInMainChain(blockFlow, invalidBlockHash).leftValue.detail is
      s"The block ${invalidBlockHash.toHexString} does not exist, please check if your full node synced"
  }

  @scala.annotation.tailrec
  private def randomBlockHash(
      chainIndex: ChainIndex
  )(implicit groupConfig: GroupConfig): BlockHash = {
    val blockHash = BlockHash.random
    if (ChainIndex.from(blockHash) == chainIndex) blockHash else randomBlockHash(chainIndex)
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

  private def checkAddressBalance(address: Address, amount: U256, utxoNum: Int = 1)(implicit
      serverUtils: ServerUtils,
      blockFlow: BlockFlow
  ) = {
    serverUtils.getBalance(blockFlow, address, true) isE Balance.from(
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
