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
import org.scalatest.Assertion

import org.alephium.api.{model => api}
import org.alephium.api.{ApiError, Try}
import org.alephium.api.model.{Address => _, Transaction => _, TransactionTemplate => _, _}
import org.alephium.api.model.BuildDeployContractTx.Code
import org.alephium.crypto.{BIP340Schnorr, SecP256K1, SecP256R1}
import org.alephium.flow.{FlowFixture, GhostUncleFixture}
import org.alephium.flow.core.{maxForkDepth, AMMContract, BlockFlow, ExtraUtxosInfo}
import org.alephium.flow.gasestimation._
import org.alephium.flow.setting.NetworkSetting
import org.alephium.flow.validation.TxScriptExeFailed
import org.alephium.protocol._
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model
import org.alephium.protocol.model.{
  AssetOutput => _,
  Balance => _,
  ContractOutput => ModelContractOutput,
  _
}
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.{
  GasBox,
  GasPrice,
  LockupScript,
  PublicKeyLike,
  TokenIssuance,
  UnlockScript
}
import org.alephium.ralph.{Compiler, SourceIndex}
import org.alephium.serde.{avectorSerde, deserialize, serialize}
import org.alephium.util._

// scalastyle:off file.size.limit number.of.methods
class ServerUtilsSpec extends AlephiumSpec {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  val defaultUtxosLimit: Int                         = ALPH.MaxTxInputNum * 2

  def warningString(code: String, sourceIndex: Option[SourceIndex]) = {
    code.substring(sourceIndex.get.index, sourceIndex.get.endIndex)
  }

  trait ApiConfigFixture extends SocketUtil {
    val peerPort             = generatePort()
    val address              = new InetSocketAddress("127.0.0.1", peerPort)
    val blockflowFetchMaxAge = Duration.zero

    def utxosLimitInApiConfig: Int = defaultUtxosLimit
    implicit lazy val apiConfig: ApiConfig = ApiConfig(
      networkInterface = address.getAddress,
      blockflowFetchMaxAge = blockflowFetchMaxAge,
      askTimeout = Duration.ofMinutesUnsafe(1),
      AVector.empty,
      ALPH.oneAlph,
      utxosLimitInApiConfig,
      128,
      enableHttpMetrics = true
    )
  }

  trait Fixture extends FlowFixture with ApiConfigFixture {
    implicit def flowImplicit: BlockFlow = blockFlow

    def emptyKey(index: Int): Hash = TxOutputRef.key(TransactionId.zero, index).value

    def confirmNewBlock(blockFlow: BlockFlow, chainIndex: ChainIndex) = {
      val block = mineFromMemPool(blockFlow, chainIndex)
      block.nonCoinbase.foreach(_.scriptExecutionOk is true)
      addAndCheck(blockFlow, block)
      block
    }

    lazy val hardFork = networkConfig.getHardFork(TimeStamp.now())
  }

  trait TransferFromOneToManyGroupsFixture extends FlowFixtureWithApi with GetTxFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardForkSince(HardFork.Rhone)
    val hardFork = networkConfig.getHardFork(TimeStamp.now())

    implicit val serverUtils: ServerUtils = new ServerUtils

    implicit lazy val bf: BlockFlow = blockFlow

    val (genesisPrivateKey_0, genesisPublicKey_0, _) = genesisKeys(0)
    val (genesisPrivateKey_1, genesisPublicKey_1, _) = genesisKeys(1)
    val (genesisPrivateKey_2, genesisPublicKey_2, _) = genesisKeys(2)

    // scalastyle:off method.length
    def testTransferFromOneToManyGroups(
        fromPrivateKey: PrivateKey,
        fromPublicKey: PublicKey,
        senderInputsCount: Int,
        destinations: AVector[Destination]
    )(
        expectedSenderUtxosCount: Int,
        expectedDestUtxosCount: Int,
        expectedDestBalance: U256,
        expectedTxsCount: Int
    ): Assertion = {
      val (inputs, initialSenderBalance) =
        prepareUtxos(fromPrivateKey, fromPublicKey, Some(senderInputsCount))
      val outputRefs =
        Option(inputs).filter(_.nonEmpty).map(_.map(output => OutputRef.from(output.ref)))
      val transactionResults = serverUtils
        .buildTransferFromOneToManyGroups(
          blockFlow,
          BuildTransferTx(
            fromPublicKey.bytes,
            None,
            destinations,
            outputRefs
          )
        )
        .rightValue

      val confirmedBlocks =
        transactionResults.map { txResult =>
          val chainIndex = txResult.chainIndex().get
          val template =
            signAndAddToMemPool(
              txResult.txId,
              txResult.unsignedTx,
              chainIndex,
              fromPrivateKey
            )
          if (hardFork.isDanubeEnabled()) {
            addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
          }
          val block = mineFromMemPool(blockFlow, chainIndex)
          addAndCheck(blockFlow, block)
          if (!chainIndex.isIntraGroup) {
            // To confirm the transaction so its cross-chain output can be used
            addAndCheck(
              blockFlow,
              emptyBlock(blockFlow, ChainIndex(chainIndex.from, chainIndex.from))
            )
          }
          val txStatus =
            serverUtils.getTransactionStatus(blockFlow, template.id, chainIndex).rightValue
          txStatus.isInstanceOf[Confirmed] is true
          block.transactions.foreach(checkTx(blockFlow, _, chainIndex))
          block
        }

      val txs = confirmedBlocks.flatMap(_.nonCoinbase)
      txs.length is expectedTxsCount
      txs.map(_.outputsLength - 1).sum is destinations.length
      val outputs =
        destinations.map(d =>
          TxOutputInfo(
            d.address.lockupScript,
            d.getAttoAlphAmount().value,
            AVector.empty,
            Option.empty
          )
        )

      val (actualUtxoCount, actualBalance) = getTotalUtxoCountsAndBalance(blockFlow, outputs)
      actualUtxoCount is expectedDestUtxosCount
      actualBalance is expectedDestBalance

      val txsFee = txs.map(_.gasFeeUnsafe.v).sum
      val senderHasSpent =
        U256.from(destinations.map(_.getAttoAlphAmount().value.v).sum).get + U256.from(txsFee).get
      val expectedSenderBalanceWithGas = initialSenderBalance - senderHasSpent
      checkAddressBalance(
        Address.p2pkh(fromPublicKey),
        expectedSenderBalanceWithGas,
        expectedSenderUtxosCount
      )
    }
  }
  // scalastyle:on method.length

  trait GetTxFixture {
    def brokerConfig: BrokerConfig
    def serverUtils: ServerUtils

    def check[T](
        blockFlow: BlockFlow,
        tx: Transaction,
        chainIndex: ChainIndex,
        getTx: (BlockFlow, TransactionId, Option[GroupIndex], Option[GroupIndex]) => Try[T],
        result: T
    ) = {
      getTx(blockFlow, tx.id, Some(chainIndex.from), Some(chainIndex.to)) isE result
      getTx(blockFlow, tx.id, Some(chainIndex.from), None) isE result
      getTx(blockFlow, tx.id, None, Some(chainIndex.to)) isE result
      getTx(blockFlow, tx.id, None, None) isE result
      val invalidChainIndex = brokerConfig.chainIndexes.filter(_.from != chainIndex.from).head
      val error             = getTx(blockFlow, tx.id, Some(invalidChainIndex.from), None).leftValue
      error.detail is s"Transaction ${tx.id.toHexString} not found"
    }

    def checkTx(blockFlow: BlockFlow, tx: Transaction, chainIndex: ChainIndex) = {
      check(blockFlow, tx, chainIndex, serverUtils.getTransaction, api.Transaction.fromProtocol(tx))
      check(blockFlow, tx, chainIndex, serverUtils.getRawTransaction, RawTransaction(serialize(tx)))
    }
  }

  trait FlowFixtureWithApi extends FlowFixture with ApiConfigFixture

  it should "send message with tx" in new Fixture {
    implicit val serverUtils: ServerUtils = new ServerUtils

    val (_, fromPublicKey, _) = genesisKeys(0)
    val message               = Hex.unsafe("FFFF")
    val destination           = generateDestination(ChainIndex.unsafe(0, 1), message)
    val buildTransferTransaction = serverUtils
      .buildTransferTransaction(
        blockFlow,
        BuildTransferTx(fromPublicKey.bytes, None, AVector(destination))
      )
      .rightValue
      .asInstanceOf[BuildSimpleTransferTxResult]

    val unsignedTransaction =
      serverUtils.decodeUnsignedTransaction(buildTransferTransaction.unsignedTx).rightValue

    unsignedTransaction.fixedOutputs.head.additionalData is message
  }

  it should "check tx status for intra group txs" in new Fixture with GetTxFixture {

    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils: ServerUtils = new ServerUtils

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
      val buildTransferTransaction = serverUtils
        .buildTransferTransaction(
          blockFlow,
          BuildTransferTx(fromPublicKey.bytes, None, destinations)
        )
        .rightValue
        .asInstanceOf[BuildSimpleTransferTxResult]

      val txTemplate = signAndAddToMemPool(
        buildTransferTransaction.txId,
        buildTransferTransaction.unsignedTx,
        chainIndex,
        fromPrivateKey
      )

      val senderBalanceWithGas =
        genesisBalance - destination1.getAttoAlphAmount().value - destination2
          .getAttoAlphAmount()
          .value

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
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils: ServerUtils = new ServerUtils

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
      val buildTransferTransaction = serverUtils
        .buildTransferTransaction(
          blockFlow,
          BuildTransferTx(fromPublicKey.bytes, None, destinations)
        )
        .rightValue
        .asInstanceOf[BuildSimpleTransferTxResult]

      val txTemplate = signAndAddToMemPool(
        buildTransferTransaction.txId,
        buildTransferTransaction.unsignedTx,
        chainIndex,
        fromPrivateKey
      )

      val senderBalanceWithGas =
        genesisBalance - destination1.getAttoAlphAmount().value - destination2
          .getAttoAlphAmount()
          .value

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

  "transfer-from-one-to-many-groups" should "support inputs auto-selection" in new TransferFromOneToManyGroupsFixture {
    val destinations =
      AVector(ChainIndex.unsafe(0, 1), ChainIndex.unsafe(0, 2)).map(generateDestination(_))
    testTransferFromOneToManyGroups(
      genesisPrivateKey_0,
      genesisPublicKey_0,
      senderInputsCount = 0,
      destinations
    )(
      expectedSenderUtxosCount = 1,
      expectedDestUtxosCount = 2,
      expectedDestBalance = ALPH.oneAlph * 2,
      expectedTxsCount = 2
    )
  }

  "transfer-from-one-to-many-groups" should "support providing inputs" in new TransferFromOneToManyGroupsFixture {
    val destinations =
      AVector(ChainIndex.unsafe(0, 1), ChainIndex.unsafe(0, 2)).map(generateDestination(_))
    testTransferFromOneToManyGroups(
      genesisPrivateKey_0,
      genesisPublicKey_0,
      senderInputsCount = 2,
      destinations
    )(
      expectedSenderUtxosCount = 2,
      expectedDestUtxosCount = 2,
      expectedDestBalance = ALPH.oneAlph * 2,
      expectedTxsCount = 2
    )
  }

  "transfer-from-one-to-many-groups" should "support fewer inputs than provided outputs" in new TransferFromOneToManyGroupsFixture {
    val destinations =
      AVector(ChainIndex.unsafe(0, 1), ChainIndex.unsafe(0, 2)).map(generateDestination(_))
    testTransferFromOneToManyGroups(
      genesisPrivateKey_0,
      genesisPublicKey_0,
      senderInputsCount = 1,
      destinations
    )(
      expectedSenderUtxosCount = 1,
      expectedDestUtxosCount = 2,
      expectedDestBalance = ALPH.oneAlph * 2,
      expectedTxsCount = 2
    )
  }

  "transfer-from-one-to-many-groups" should "split too many destinations into more txs" in new TransferFromOneToManyGroupsFixture {
    val destinations_1 = AVector.fill(257)(generateDestination(ChainIndex.unsafe(0, 1)))
    val destinations_2 = AVector.fill(257)(generateDestination(ChainIndex.unsafe(0, 2)))
    testTransferFromOneToManyGroups(
      genesisPrivateKey_0,
      genesisPublicKey_0,
      senderInputsCount = 2,
      destinations_1 ++ destinations_2
    )(
      expectedSenderUtxosCount = 2,
      expectedDestUtxosCount = 514,
      expectedDestBalance = ALPH.oneAlph * 514,
      expectedTxsCount = 4
    )
  }

  "transfer-from-one-to-many-groups" should "fail in case gas amount is passed by user" in new TransferFromOneToManyGroupsFixture {
    serverUtils
      .buildTransferFromOneToManyGroups(
        blockFlow,
        BuildTransferTx(
          genesisPublicKey_0.bytes,
          None,
          AVector(
            generateDestination(ChainIndex.unsafe(0, 0))
          ),
          None,
          Some(GasBox.unsafe(1)),
          Some(GasPrice(1))
        )
      )
      .leftValue
      .detail is "Explicit gas amount is not permitted, transfer-from-one-to-many-groups requires gas estimation."
  }

  "transfer-from-one-to-many-groups" should "work across all groups" in new TransferFromOneToManyGroupsFixture {
    val destinations =
      AVector(0, 1, 2).map { groupIndex =>
        Destination(
          Address.p2pkh(GroupIndex.unsafe(groupIndex).generateKey._2),
          Some(Amount(ALPH.oneAlph))
        )
      }

    testTransferFromOneToManyGroups(
      genesisPrivateKey_0,
      genesisPublicKey_0,
      senderInputsCount = 1,
      destinations
    )(
      expectedSenderUtxosCount = 1,
      expectedDestUtxosCount = 3,
      expectedDestBalance = ALPH.oneAlph * 3,
      expectedTxsCount = 3
    )

    testTransferFromOneToManyGroups(
      genesisPrivateKey_1,
      genesisPublicKey_1,
      senderInputsCount = 1,
      destinations
    )(
      expectedSenderUtxosCount = 1,
      expectedDestUtxosCount = 6,
      expectedDestBalance = ALPH.oneAlph * 6,
      expectedTxsCount = 3
    )

    testTransferFromOneToManyGroups(
      genesisPrivateKey_2,
      genesisPublicKey_2,
      senderInputsCount = 1,
      destinations
    )(
      expectedSenderUtxosCount = 1,
      expectedDestUtxosCount = 9,
      expectedDestBalance = ALPH.oneAlph * 9,
      expectedTxsCount = 3
    )
  }

  it should "support Schnorr address" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardForkSince(HardFork.Rhone)

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

    implicit val serverUtils: ServerUtils = new ServerUtils
    val destination = Destination(Address.p2pkh(genesisPubKey), Some(Amount(ALPH.oneAlph)))
    val buildTransferTransaction = serverUtils
      .buildTransferTransaction(
        blockFlow,
        BuildTransferTx(
          fromPublicKey = pubKey.bytes,
          fromPublicKeyType = Some(BuildTxCommon.BIP340Schnorr),
          AVector(destination)
        )
      )
      .rightValue
      .asInstanceOf[BuildSimpleTransferTxResult]

    val unsignedTransaction =
      serverUtils.decodeUnsignedTransaction(buildTransferTransaction.unsignedTx).rightValue

    val signature = BIP340Schnorr.sign(unsignedTransaction.id.bytes, priKey)
    val txTemplate =
      serverUtils
        .createTxTemplate(
          SubmitTransaction(buildTransferTransaction.unsignedTx, Signature.unsafe(signature.bytes))
        )
        .rightValue
    blockFlow.getGrandPool().add(txTemplate.chainIndex, AVector(txTemplate), TimeStamp.now())

    if (hardFork.isDanubeEnabled() && !txTemplate.chainIndex.isIntraGroup) {
      addAndCheck(blockFlow, emptyBlock(blockFlow, txTemplate.chainIndex))
    }
    val block1 = mineFromMemPool(blockFlow, txTemplate.chainIndex)
    block1.nonCoinbase.map(_.id).contains(txTemplate.id) is true
    addAndCheck(blockFlow, block1)
  }

  it should "check sweep address tx status for intra group txs" in new Fixture with GetTxFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils: ServerUtils = new ServerUtils

    for {
      targetGroup <- 0 until groups0
    } {
      val chainIndex                         = ChainIndex.unsafe(targetGroup, targetGroup)
      val fromGroup                          = chainIndex.from
      val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
      val fromAddress                        = Address.p2pkh(fromPublicKey)
      val selfDestination = Destination(fromAddress, Some(Amount(ALPH.oneAlph)), None)

      info("Sending some coins to itself twice, creating 3 UTXOs in total for the same public key")
      val destinations = AVector(selfDestination, selfDestination)
      val buildTransferTransaction = serverUtils
        .buildTransferTransaction(
          blockFlow,
          BuildTransferTx(fromPublicKey.bytes, None, destinations)
        )
        .rightValue
        .asInstanceOf[BuildSimpleTransferTxResult]

      val txTemplate = signAndAddToMemPool(
        buildTransferTransaction.txId,
        buildTransferTransaction.unsignedTx,
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
          BuildSweepAddressTransactions(fromPublicKey.bytes, None, sweepAddressDestination)
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
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils: ServerUtils = new ServerUtils

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
      val destination                        = Destination(toAddress, Some(Amount(ALPH.oneAlph)))

      info("Sending some coins to an address, resulting 10 UTXOs for its corresponding public key")
      val destinations = AVector.fill(10)(destination)
      val buildTransferTransaction = serverUtils
        .buildTransferTransaction(
          blockFlow,
          BuildTransferTx(fromPublicKey.bytes, None, destinations)
        )
        .rightValue
        .asInstanceOf[BuildSimpleTransferTxResult]

      val txTemplate = signAndAddToMemPool(
        buildTransferTransaction.txId,
        buildTransferTransaction.unsignedTx,
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
          BuildSweepAddressTransactions(toPublicKey.bytes, None, sweepAddressDestination)
        )
        .rightValue
      val sweepAddressTransaction = buildSweepAddressTransactionsRes.unsignedTxs.head

      val sweepAddressChainIndex = ChainIndex(chainIndex.to, chainIndex.to)
      addAndCheck(blockFlow, emptyBlock(blockFlow, sweepAddressChainIndex))
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
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils: ServerUtils = new ServerUtils

    val (_, fromPublicKey, _) = genesisKeys(0)
    val (_, toPublicKey)      = GroupIndex.unsafe(0).generateKey

    val result0 = serverUtils
      .buildSweepAddressTransactions(
        blockFlow,
        BuildSweepAddressTransactions(fromPublicKey.bytes, None, Address.p2pkh(toPublicKey), None)
      )
      .rightValue
    result0.unsignedTxs.length is 1

    val result1 = serverUtils
      .buildSweepAddressTransactions(
        blockFlow,
        BuildSweepAddressTransactions(
          fromPublicKey.bytes,
          None,
          Address.p2pkh(toPublicKey),
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
          targetBlockHash,
          ExtraUtxosInfo.empty
        )
        .rightValue
    }
  }

  it should "use target block hash for sweep tx" in new PrepareTxWithTargetBlockHash {
    def generateTx(targetBlockHash: Option[BlockHash]): UnsignedTransaction = {
      val txs = serverUtils
        .prepareSweepAddressTransaction(
          blockFlow,
          getLockPair(fromPublicKey),
          BuildSweepAddressTransactions(
            fromPublicKey.bytes,
            None,
            destinations.head.address,
            targetBlockHash = targetBlockHash
          )
        )
        .rightValue
      txs.length is 1
      txs.head
    }
  }

  it should "respect the `utxosLimit` config" in new FlowFixtureWithApi {
    override def utxosLimitInApiConfig: Int = 4

    def checkInputSize(rawTx: String, expectedInputSize: Int) = {
      val unsignedTx = deserialize[UnsignedTransaction](Hex.unsafe(rawTx)).rightValue
      unsignedTx.inputs.length is expectedInputSize
    }

    val serverUtils = new ServerUtils
    val groupIndex  = GroupIndex.unsafe(0)
    val genesisKey  = genesisKeys(groupIndex.value)._1
    val pubKey      = groupIndex.generateKey._2
    (0 until 6).foreach { _ =>
      val block = transfer(blockFlow, genesisKey, pubKey, ALPH.oneAlph)
      addAndCheck(blockFlow, block)
    }
    val lockupScript = LockupScript.p2pkh(pubKey)
    blockFlow.getUTXOs(lockupScript, Int.MaxValue, true).rightValue.length is 6

    val params0 =
      BuildSweepAddressTransactions(
        pubKey.bytes,
        None,
        Address.Asset(lockupScript),
        utxosLimit = Some(2)
      )
    val result0 = serverUtils.buildSweepAddressTransactions(blockFlow, params0).rightValue
    result0.unsignedTxs.length is 1
    checkInputSize(result0.unsignedTxs.head.unsignedTx, 2)

    val params1 =
      BuildSweepAddressTransactions(
        pubKey.bytes,
        None,
        Address.Asset(lockupScript),
        utxosLimit = None
      )
    val result1 = serverUtils.buildSweepAddressTransactions(blockFlow, params1).rightValue
    result1.unsignedTxs.length is 1
    checkInputSize(result1.unsignedTxs.head.unsignedTx, utxosLimitInApiConfig)

    val params2 =
      BuildSweepAddressTransactions(
        pubKey.bytes,
        None,
        Address.Asset(lockupScript),
        utxosLimit = Some(6)
      )
    val result2 = serverUtils.buildSweepAddressTransactions(blockFlow, params2).rightValue
    result2.unsignedTxs.length is 1
    checkInputSize(result2.unsignedTxs.head.unsignedTx, utxosLimitInApiConfig)
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
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
      )
      .rightValue

    val buildTransferTransaction = serverUtils
      .buildTransferTransaction(
        blockFlow,
        BuildTransferTx(fromPublicKey.bytes, None, destinations)
      )
      .rightValue
      .asInstanceOf[BuildSimpleTransferTxResult]

    val decodedUnsignedTx =
      serverUtils.decodeUnsignedTransaction(buildTransferTransaction.unsignedTx).rightValue

    decodedUnsignedTx is unsignedTx
  }

  trait MultipleUtxos extends FlowFixtureWithApi {
    implicit val serverUtils: ServerUtils = new ServerUtils

    implicit val bf: BlockFlow             = blockFlow
    val chainIndex                         = ChainIndex.unsafe(0, 0)
    val (fromPrivateKey, fromPublicKey, _) = genesisKeys(chainIndex.from.value)
    val fromAddress                        = Address.p2pkh(fromPublicKey)
    val selfDestination                    = Destination(fromAddress, Some(Amount(ALPH.cent(50))))

    info("Sending some coins to itself, creating 2 UTXOs in total for the same public key")
    val selfDestinations = AVector(selfDestination)
    val buildTransferTransaction = serverUtils
      .buildTransferTransaction(
        blockFlow,
        BuildTransferTx(fromPublicKey.bytes, None, selfDestinations)
      )
      .rightValue
      .asInstanceOf[BuildSimpleTransferTxResult]

    val txTemplate = signAndAddToMemPool(
      buildTransferTransaction.txId,
      buildTransferTransaction.unsignedTx,
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
          targetBlockHashOpt = None,
          ExtraUtxosInfo.empty
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
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
      )
      .rightValue

    val fromAddressBalanceAfterTransfer = {
      val fromLockupScript    = LockupScript.p2pkh(fromPublicKey)
      val outputLockupScripts = fromLockupScript +: destinations.map(_.address.lockupScript)
      val defaultGas =
        GasEstimation.estimateWithSameP2PKHInputs(outputRefs.length, outputLockupScripts.length)
      val defaultGasFee = nonCoinbaseMinGasPrice * defaultGas
      fromAddressBalance - ALPH.oneAlph.mulUnsafe(2) - defaultGasFee
    }

    unsignedTx.fixedOutputs.map(_.amount).toSeq should contain theSameElementsAs Seq(
      ALPH.oneAlph,
      ALPH.oneAlph,
      fromAddressBalanceAfterTransfer
    )
  }

  it should "validate unsigned transactions" in new Fixture with TxInputGenerators {

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
        "Gas fee exceeds the limit: maximum allowed is 1.0 ALPH, but got 20,000.0 ALPH. Please lower the gas price or adjust the alephium.api.gas-fee-cap in your user.conf file."
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
    val outputRefs = utxos.collect { utxo =>
      if (utxo.amount.value.equals(ALPH.cent(50))) {
        Some(OutputRef(utxo.ref.hint, utxo.ref.key))
      } else {
        None
      }
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
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
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
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
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
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
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
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
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
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
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
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
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
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
      )
      .leftValue
      .detail is "Gas price GasPrice(1000000000000000000000000000) too large, maximal GasPrice(999999999999999999999999999)"
  }

  it should "not create transaction with overflowing ALPH amount" in new MultipleUtxos {
    val attoAlphAmountOverflowDestinations = AVector(
      destination1,
      destination2.copy(attoAlphAmount = Some(Amount(ALPH.MaxALPHValue)))
    )
    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        fromPublicKey,
        outputRefsOpt = None,
        attoAlphAmountOverflowDestinations,
        gasOpt = Some(minimalGas),
        nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
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
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
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
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
      )
      .leftValue
      .detail is "Selected UTXOs must be of asset type"
  }

  "ServerUtils.buildTransferTransaction" should "fail with invalid number of outputs" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex.from.value)
    val emptyDestinations     = AVector.empty[Destination]

    info("Output number is zero")
    serverUtils
      .buildTransferTransaction(
        blockFlow,
        BuildTransferTx(fromPublicKey.bytes, None, emptyDestinations)
      )
      .leftValue
      .detail is "Zero transaction outputs"

    info("Too many outputs")
    val tooManyDestinations = AVector.fill(ALPH.MaxTxOutputNum + 1)(generateDestination(chainIndex))
    serverUtils
      .buildTransferTransaction(
        blockFlow,
        BuildTransferTx(fromPublicKey.bytes, None, tooManyDestinations)
      )
      .leftValue
      .detail is "Too many transaction outputs, maximal value: 256"
  }

  it should "fail when outputs belong to different groups" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex1           = ChainIndex.unsafe(0, 1)
    val chainIndex2           = ChainIndex.unsafe(0, 2)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex1.from.value)
    val destination1          = generateDestination(chainIndex1)
    val destination2          = generateDestination(chainIndex2)
    val destinations          = AVector(destination1, destination2)

    val buildTransferTransaction = serverUtils
      .buildTransferTransaction(
        blockFlow,
        BuildTransferTx(fromPublicKey.bytes, None, destinations)
      )
      .leftValue

    buildTransferTransaction.detail is "Different groups for transaction outputs"
  }

  it should "return mempool statuses" in new Fixture with Generators {

    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    implicit val serverUtils: ServerUtils = new ServerUtils()

    val emptyMempool = serverUtils.listMempoolTransactions(blockFlow).rightValue
    emptyMempool is AVector.empty[MempoolTransactions]

    val chainIndex                         = chainIndexGen.sample.get
    val fromGroup                          = chainIndex.from
    val (fromPrivateKey, fromPublicKey, _) = genesisKeys(fromGroup.value)
    val destination                        = generateDestination(chainIndex)

    val buildTransferTransaction = serverUtils
      .buildTransferTransaction(
        blockFlow,
        BuildTransferTx(fromPublicKey.bytes, None, AVector(destination))
      )
      .rightValue
      .asInstanceOf[BuildSimpleTransferTxResult]

    val txSeenAt = TimeStamp.now()
    val txTemplate = signAndAddToMemPool(
      buildTransferTransaction.txId,
      buildTransferTransaction.unsignedTx,
      chainIndex,
      fromPrivateKey,
      txSeenAt
    )

    val txs = serverUtils.listMempoolTransactions(blockFlow).rightValue

    txs is AVector(
      MempoolTransactions(
        chainIndex.from.value,
        chainIndex.to.value,
        AVector(api.TransactionTemplate.fromProtocol(txTemplate, txSeenAt))
      )
    )
  }

  "ServerUtils.buildMultiInputsTransaction" should "transfer a single input" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (_, fromPublicKey, _) = genesisKeys(chainIndex.from.value)
    val amount                = Amount(ALPH.oneAlph)
    val destination           = Destination(generateAddress(chainIndex), Some(amount))

    val source = BuildMultiAddressesTransaction.Source(
      fromPublicKey.bytes,
      AVector(destination)
    )

    val buildTransferTransaction = serverUtils
      .buildMultiInputsTransaction(
        blockFlow,
        BuildMultiAddressesTransaction(AVector(source))
      )
      .rightValue

    val unsignedTransaction =
      serverUtils.decodeUnsignedTransaction(buildTransferTransaction.unsignedTx).rightValue

    unsignedTransaction.inputs.length is 1
    unsignedTransaction.fixedOutputs.length is 2
  }

  it should "transfer a multiple inputs" in new FlowFixtureWithApi {
    val serverUtils = new ServerUtils

    val nbOfInputs             = 10
    val chainIndex             = ChainIndex.unsafe(0, 0)
    val (fromPrivateKey, _, _) = genesisKeys(chainIndex.from.value)
    val amount                 = ALPH.alph(10)
    val destination = Destination(generateAddress(chainIndex), Some(Amount(ALPH.oneAlph)))

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

    val buildTransferTransaction = serverUtils
      .buildMultiInputsTransaction(
        blockFlow,
        BuildMultiAddressesTransaction(sources)
      )
      .rightValue

    val unsignedTransaction =
      serverUtils.decodeUnsignedTransaction(buildTransferTransaction.unsignedTx).rightValue

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

    val destination = Destination(generateAddress(chainIndex), Some(Amount(ALPH.oneAlph)))

    forAll(Gen.choose(1, 20)) { i =>
      val outputs = serverUtils.mergeAndprepareOutputInfos(AVector.fill(i)(destination)).rightValue
      outputs.length is 1
      outputs(0).attoAlphAmount is ALPH.alph(i.toLong)
      outputs(0).tokens is AVector.empty[(TokenId, U256)]
    }

    val destination2 = Destination(generateAddress(chainIndex), Some(Amount(ALPH.alph(2))))

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

    val destination =
      Destination(generateAddress(chainIndex), Some(Amount(ALPH.oneAlph)), Some(tokens))

    forAll(Gen.choose(1, 20)) { i =>
      val outputs = serverUtils.mergeAndprepareOutputInfos(AVector.fill(i)(destination)).rightValue
      outputs.length is 1
      outputs(0).attoAlphAmount is ALPH.alph(i.toLong)
      outputs(0).tokens is AVector(
        (tokenId1, U256.unsafe(i)),
        (tokenId2, U256.unsafe(2 * i.toLong))
      )
    }

    val destination2 =
      Destination(generateAddress(chainIndex), Some(Amount(ALPH.alph(2))), Some(tokens))

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
    val destination = Destination(destAddress, Some(Amount(ALPH.oneAlph)))
    val destinationLockTime =
      Destination(destAddress, Some(Amount(ALPH.oneAlph)), lockTime = Some(TimeStamp.now()))
    val destinationMessage =
      Destination(destAddress, Some(Amount(ALPH.oneAlph)), message = Some(ByteString.empty))
    val destinationBoth = Destination(
      destAddress,
      Some(Amount(ALPH.oneAlph)),
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
      Destination(destAddress, Some(Amount(ALPH.oneAlph)), lockTime = Some(TimeStamp.now()))
    val destinationMessage =
      Destination(destAddress, Some(Amount(ALPH.oneAlph)), message = Some(ByteString.empty))
    val destinationBoth = Destination(
      destAddress,
      Some(Amount(ALPH.oneAlph)),
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

  trait ContractFixtureBase extends Fixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.broker.groups", 4),
      ("alephium.broker.broker-num", 1),
      ("alephium.node.indexes.tx-output-ref-index", "true")
    )

    lazy val chainIndex                        = ChainIndex.unsafe(0, 0)
    lazy val lockupScript                      = getGenesisLockupScript(chainIndex)
    implicit lazy val serverUtils: ServerUtils = new ServerUtils()

    def executeScript(
        script: vm.StatefulScript,
        keyPairOpt: Option[(PrivateKey, PublicKey)]
    ): Block = {
      val block = payableCall(blockFlow, chainIndex, script, keyPairOpt = keyPairOpt)
      addAndCheck(blockFlow, block)
      block
    }

    def executeScript(
        code: String,
        keyPairOpt: Option[(PrivateKey, PublicKey)] = None
    ): Block = {
      val script = Compiler.compileTxScript(code).rightValue
      executeScript(script, keyPairOpt)
    }

    def deployContract(
        contract: String,
        initialAttoAlphAmount: Amount,
        keyPair: (PrivateKey, PublicKey),
        issueTokenAmount: Option[Amount]
    ): Address.Contract = {
      val code = Compiler.compileContract(contract).toOption.get

      val deployContractTxResult = serverUtils
        .buildDeployContractTx(
          blockFlow,
          BuildDeployContractTx(
            Hex.unsafe(keyPair._2.toHexString),
            bytecode = serialize(Code(code, AVector.empty, AVector.empty)),
            initialAttoAlphAmount = Some(initialAttoAlphAmount),
            issueTokenAmount = issueTokenAmount
          )
        )
        .rightValue
        .asInstanceOf[BuildSimpleDeployContractTxResult]

      val deployContractTx =
        deserialize[UnsignedTransaction](Hex.unsafe(deployContractTxResult.unsignedTx)).rightValue
      deployContractTx.fixedOutputs.length is 1

      val testAddressAlphBalance = getAlphBalance(blockFlow, LockupScript.p2pkh(keyPair._2))
      signAndAddToMemPool(
        deployContractTxResult.txId,
        deployContractTxResult.unsignedTx,
        chainIndex,
        keyPair._1
      )(serverUtils, blockFlow)

      confirmNewBlock(blockFlow, ChainIndex.unsafe(1, 1))
      if (hardFork.isDanubeEnabled()) {
        confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 0))
      }
      confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 0))
      serverUtils.getTransaction(blockFlow, deployContractTxResult.txId, None, None).rightValue

      // Check that gas is paid correctly
      getAlphBalance(blockFlow, LockupScript.p2pkh(keyPair._2)) is testAddressAlphBalance.subUnsafe(
        deployContractTx.gasPrice * deployContractTx.gasAmount + initialAttoAlphAmount.value
      )
      deployContractTxResult.contractAddress
    }

    def createContract(
        code: String,
        immFields: AVector[vm.Val],
        mutFields: AVector[vm.Val]
    ): (Block, ContractId) = {
      val contract = Compiler.compileContract(code).rightValue
      createContract(contract, immFields, mutFields)
    }

    def createContract(
        contract: vm.StatefulContract,
        immFields: AVector[vm.Val],
        mutFields: AVector[vm.Val]
    ): (Block, ContractId) = {
      val script =
        contractCreation(contract, immFields, mutFields, lockupScript, minimalAlphInContract)
      val block      = executeScript(script, None)
      val contractId = ContractId.from(block.transactions.head.id, 0, chainIndex.from)
      (block, contractId)
    }
  }

  trait ContractFixture extends ContractFixtureBase {
    setHardForkSince(HardFork.Rhone)
  }

  trait CallContractFixture extends ContractFixture {
    val callerAddress = Address.Asset(lockupScript)
    val inputAsset    = TestInputAsset(callerAddress, AssetState(ALPH.oneAlph))

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

    val (_, barId) = createContract(barCode, AVector.empty, AVector[vm.Val](vm.Val.U256(U256.Zero)))
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
      createContract(fooCode, AVector.empty, AVector[vm.Val](vm.Val.U256(U256.Zero)))
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

    def checkContractStates(contractId: ContractId, value: U256, attoAlphAmount: U256) = {
      val worldState    = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
      val contractState = worldState.getContractState(contractId).rightValue
      contractState.mutFields is AVector[vm.Val](vm.Val.U256(value))
      val contractOutput = worldState.getContractAsset(contractState.contractOutputRef).rightValue
      contractOutput.amount is attoAlphAmount
    }
  }

  "ServerUtils.callContract" should "call contract" in new CallContractFixture {
    executeScript(callScriptCode, None)
    checkContractStates(barId, U256.unsafe(1), minimalAlphInContract)
    checkContractStates(fooId, U256.unsafe(1), minimalAlphInContract + ALPH.oneNanoAlph)

    info("call contract against the latest world state")
    val params0 = CallContract(
      group = chainIndex.from.value,
      address = fooAddress,
      methodIndex = 0,
      inputAssets = Some(AVector(inputAsset)),
      interestedContracts = Some(AVector(barAddress))
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

  it should "call TxScript" in new ContractFixture {
    val simpleScript =
      s"""
         |TxScript Main {
         |  pub fn main() -> ([U256; 2], Bool) {
         |    return [1, 2], false
         |  }
         |}
         |""".stripMargin
    val simpleScriptByteCode = serialize(Compiler.compileTxScript(simpleScript).rightValue)

    {
      info("Call TxScript")
      val params = CallTxScript(group = 0, bytecode = simpleScriptByteCode)
      val result = serverUtils.callTxScript(blockFlow, params).rightValue
      result.returns is AVector[Val](ValU256(1), ValU256(2), ValBool(false))
    }

    {
      info("Load contract fields")
      val foo =
        s"""
           |Contract Foo(bar: Bar) {
           |  pub fn foo() -> Bar {
           |    return bar
           |  }
           |}
           |struct Bar { a: U256, b: Bool }
           |""".stripMargin

      val fooId =
        createContract(foo, AVector[vm.Val](vm.Val.U256(1), vm.Val.True), AVector.empty)._2
      val script =
        s"""
           |TxScript Main {
           |  pub fn main() -> Bar {
           |    return Foo(#${fooId.toHexString}).foo()
           |  }
           |}
           |$foo
           |""".stripMargin

      val bytecode = serialize(Compiler.compileTxScript(script).rightValue)
      val params = CallTxScript(
        group = 0,
        bytecode = bytecode,
        interestedContracts = Some(AVector(Address.contract(fooId)))
      )
      val result = serverUtils.callTxScript(blockFlow, params).rightValue
      result.returns is AVector[Val](ValU256(1), ValBool(true))
      result.contracts.length is 1
      result.contracts.head.mutFields.isEmpty is true
      result.contracts.head.immFields is AVector[Val](ValU256(1), ValBool(true))
    }

    {
      info("Call TxScript and return the new state")
      val foo =
        s"""
           |Contract Foo(mut value: U256) {
           |  @using(checkExternalCaller = false, updateFields = true, preapprovedAssets = true, assetsInContract = true)
           |  pub fn foo() -> U256 {
           |    transferTokenToSelf!(callerAddress!(), ALPH, minimalContractDeposit!())
           |    value = value + 1
           |    return value
           |  }
           |}
           |""".stripMargin

      val fooId = createContract(foo, AVector.empty, AVector[vm.Val](vm.Val.U256(1)))._2
      def checkFooState(newValue: Int, alphAmount: U256) = {
        val worldState    = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
        val contractState = worldState.getContractState(fooId).rightValue
        contractState.mutFields is AVector[vm.Val](vm.Val.U256(newValue))
        contractState.immFields.isEmpty is true
        val contractAsset = worldState.getContractAsset(fooId).rightValue
        contractAsset.amount is alphAmount
      }

      def script(returnValue: Boolean) =
        s"""
           |TxScript Main {
           |  @using(preapprovedAssets = true)
           |  pub fn main() -> ${if (returnValue) "U256" else "()"} {
           |    ${if (returnValue) "return" else ""} Foo(#${fooId.toHexString}).foo{
           |      callerAddress!() -> ALPH: minimalContractDeposit!()
           |    }()
           |  }
           |}
           |$foo
           |""".stripMargin

      val blockHash = blockFlow.getBlockChain(chainIndex).getBestTipUnsafe()
      executeScript(script(false), None)
      checkFooState(2, minimalAlphInContract * 2)

      val callerAddress = Address.p2pkh(chainIndex.from.generateKey._2)
      val fooAddress    = Address.contract(fooId)
      val bytecode      = serialize(Compiler.compileTxScript(script(true)).rightValue)
      val params0 = CallTxScript(
        group = 0,
        bytecode = bytecode,
        callerAddress = Some(callerAddress),
        interestedContracts = Some(AVector(fooAddress)),
        worldStateBlockHash = Some(blockHash),
        inputAssets = Some(AVector(TestInputAsset(callerAddress, AssetState(ALPH.oneAlph))))
      )
      val result0 = serverUtils.callTxScript(blockFlow, params0).rightValue
      result0.returns is AVector[Val](ValU256(2))
      result0.contracts.length is 1
      result0.contracts.head.mutFields is AVector[Val](ValU256(2))
      result0.contracts.head.immFields.isEmpty is true
      result0.contracts.head.asset.attoAlphAmount is minimalAlphInContract * 2
      result0.txOutputs
        .find(_.address == callerAddress)
        .exists(_.attoAlphAmount.value == ALPH.cent(90)) is true
      result0.txOutputs
        .find(_.address == fooAddress)
        .exists(_.attoAlphAmount.value == ALPH.cent(20)) is true

      val params1 = params0.copy(worldStateBlockHash = None)
      val result1 = serverUtils.callTxScript(blockFlow, params1).rightValue
      result1.returns is AVector[Val](ValU256(3))
      result1.contracts.length is 1
      result1.contracts.head.mutFields is AVector[Val](ValU256(3))
      result1.contracts.head.immFields.isEmpty is true
      result1.contracts.head.asset.attoAlphAmount is minimalAlphInContract * 3
      result1.txOutputs
        .find(_.address == callerAddress)
        .exists(_.attoAlphAmount.value == ALPH.cent(90)) is true
      result1.txOutputs
        .find(_.address == fooAddress)
        .exists(_.attoAlphAmount.value == ALPH.cent(30)) is true

      checkFooState(2, minimalAlphInContract * 2)
    }

    {
      info("Invalid group")
      val params = CallTxScript(groupConfig.groups + 1, simpleScriptByteCode)
      serverUtils.callTxScript(blockFlow, params).leftValue.detail is "Invalid group 5"
    }

    {
      info("Invalid caller address")
      val invalidCaller = Address.p2pkh(GroupIndex.unsafe(1).generateKey._2)
      val params        = CallTxScript(0, simpleScriptByteCode, callerAddress = Some(invalidCaller))
      serverUtils.callTxScript(blockFlow, params).leftValue.detail is
        s"Group mismatch: provided group is 0; group for ${invalidCaller.toBase58} is 1"
    }

    {
      info("Invalid world state block hash")
      val invalidBlockHash = randomBlockHash(ChainIndex.unsafe(1, 1))
      val params =
        CallTxScript(0, simpleScriptByteCode, worldStateBlockHash = Some(invalidBlockHash))
      serverUtils.callTxScript(blockFlow, params).leftValue.detail is
        s"Invalid block hash ${invalidBlockHash.toHexString}"
    }
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
    override lazy val serverUtils = new ServerUtils() {
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
      interestedContracts = Some(AVector(barAddress))
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
    assetOutput.attoAlphAmount is Amount(ALPH.alph(2))
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

    def buildTestParam(
        callerContractAddressOpt: Option[Address.Contract]
    ): TestContract.Complete = {
      TestContract(
        bytecode = childContract,
        address = Some(childAddress),
        callerContractAddress = callerContractAddressOpt,
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
    assetOutput.attoAlphAmount is Amount(ALPH.oneAlph)
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
      Amount(1000000000000000000L),
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
      Amount(1000000000000000000L),
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
      Amount(ALPH.nanoAlph(91000000000L) - dustUtxoAmount),
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
      Amount(ALPH.nanoAlph(111000000000L)),
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
      Amount(ALPH.nanoAlph(106000000000L)),
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
      Amount(ALPH.nanoAlph(96000000000L) - dustUtxoAmount),
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
      "Found unused variable in Foo: foo.a",
      "Found unused field in Foo: x"
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
         |const A = 0
         |enum Error { Err0 = 0 }
         |struct Baz { x: U256 }
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
    val (contracts, scripts, globalState, globalWarnings) =
      Compiler.compileProject(rawCode).rightValue
    val query  = Compile.Project(rawCode)
    val result = serverUtils.compileProject(blockFlow, query).rightValue

    result.contracts.length is 1
    contracts.length is 1
    val contractCode = result.contracts(0).bytecode
    contractCode is Hex.toHexString(serialize(contracts(0).code))

    result.scripts.length is 1
    scripts.length is 1
    val scriptCode = result.scripts(0).bytecodeTemplate
    scriptCode is scripts(0).code.toTemplateString()

    result.structs is Some(AVector(CompileResult.StructSig.from(globalState.structs(0))))
    result.enums is Some(AVector(CompileResult.Enum.from(globalState.enums(0))))
    val constant = globalState.getCalculatedConstants()(0)
    result.constants is Some(AVector(CompileResult.Constant.from(constant._1, constant._2)))

    globalWarnings.length is 2
    globalWarnings(0).message is "Found unused global constant: A"
    warningString(rawCode, globalWarnings(0).sourceIndex) is "const A = 0"
    globalWarnings(1).message is "Found unused global constant: Error.Err0"
    warningString(rawCode, globalWarnings(1).sourceIndex) is "Err0"
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
        "Found unused variable in Main: main.c",
        "Found unused field in Main: b"
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

  it should "return inline functions" in new Fixture {
    val serverUtils = new ServerUtils()
    val rawCode =
      s"""
         |Contract Foo() {
         |  @inline fn foo() -> U256 {
         |    return 0
         |  }
         |  pub fn bar() -> U256 {
         |    return foo()
         |  }
         |}
         |TxScript Main(foo: Foo) {
         |  baz()
         |  foo.bar()
         |
         |  @inline fn baz() -> () {}
         |}
         |""".stripMargin

    val query  = Compile.Project(rawCode)
    val result = serverUtils.compileProject(blockFlow, query).rightValue
    result.contracts.length is 1
    result.contracts.head.functions.length is 2
    result.scripts.length is 1
    result.scripts.head.functions.length is 2
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
    val fromAddressStr        = fromAddress.toBase58
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
           |  createContract!{@$fromAddressStr -> ALPH: 10}(#$codeRaw, #$stateRaw, #00)
           |}
           |""".stripMargin
      Compiler.compileTxScript(expected).isRight is true
      ServerUtils
        .buildDeployContractScriptRawWithParsedState(
          codeRaw,
          fromAddressStr,
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
           |  createContractWithToken!{@$fromAddressStr -> ALPH: 10}(#$codeRaw, #$stateRaw, #00, 50, @$toAddress)
           |  transferToken!{@$fromAddressStr -> ALPH: dustAmount!()}(@$fromAddressStr, @$toAddress, ALPH, dustAmount!())
           |}
           |""".stripMargin
      Compiler.compileTxScript(expected).isRight is true
      ServerUtils
        .buildDeployContractScriptRawWithParsedState(
          codeRaw,
          fromAddressStr,
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
           |  createContractWithToken!{@$fromAddressStr -> ALPH: 10, #${token1.toHexString}: 10, #${token2.toHexString}: 20}(#$codeRaw, #$stateRaw, #00, 50)
           |}
           |""".stripMargin
      Compiler.compileTxScript(expected).isRight is true
      ServerUtils
        .buildDeployContractScriptRawWithParsedState(
          codeRaw,
          fromAddressStr,
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
           |  createContractWithToken!{@$fromAddressStr -> ALPH: 10}(#$codeRaw, #$stateRaw, #00, 50)
           |}
           |""".stripMargin
      Compiler.compileTxScript(expected).isRight is true
      ServerUtils
        .buildDeployContractScriptRawWithParsedState(
          codeRaw,
          fromAddressStr,
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
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardForkSince(HardFork.Rhone)

    implicit val serverUtils: ServerUtils = new ServerUtils

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
          bytecode = serialize(Code(code, AVector.empty, AVector.empty)),
          initialAttoAlphAmount = Some(Amount(ALPH.oneAlph))
        )
      )
      .rightValue
      .asInstanceOf[BuildSimpleDeployContractTxResult]

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
  }

  trait ExecuteScriptFixture extends Fixture {
    def serverUtils: ServerUtils

    val gasAmount = GasBox.unsafe(30000)
    val gasPrice  = nonCoinbaseMinGasPrice
    val gasFee    = gasPrice * gasAmount

    val script =
      s"""
         |TxScript Foo {
         |  emit Debug(`Hey, I am Foo`)
         |}
         |""".stripMargin
    val scriptCode = Compiler.compileTxScript(script).toOption.get

    def executeTxScript(
        buildExecuteScript: BuildExecuteScriptTx
    ): BuildExecuteScriptTxResult = {
      serverUtils
        .buildExecuteScriptTx(blockFlow, buildExecuteScript)
        .rightValue
        .asInstanceOf[BuildSimpleExecuteScriptTxResult]
    }

    def failedExecuteTxScript(
        buildExecuteScript: BuildExecuteScriptTx,
        errorDetails: String
    ) = {
      serverUtils
        .buildExecuteScriptTx(blockFlow, buildExecuteScript)
        .leftValue
        .detail is errorDetails
    }
  }

  trait DustAmountFixture extends ExecuteScriptFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    implicit val serverUtils: ServerUtils       = new ServerUtils

    val chainIndex      = ChainIndex.unsafe(0, 0)
    val (_, testPubKey) = chainIndex.from.generateKey
    val testAddress     = Address.p2pkh(testPubKey)

    def attoAlphAmount: Option[Amount]

    val alphPerUTXO = dustUtxoAmount + gasFee + attoAlphAmount.getOrElse(Amount.Zero).value - 1
    val block1      = transfer(blockFlow, genesisKeys(1)._1, testPubKey, alphPerUTXO)
    addAndCheck(blockFlow, block1)
    checkAddressBalance(testAddress, alphPerUTXO, utxoNum = 1)

    val block2 = transfer(blockFlow, genesisKeys(1)._1, testPubKey, alphPerUTXO)
    addAndCheck(blockFlow, block2)
    checkAddressBalance(testAddress, alphPerUTXO * 2, utxoNum = 2)

    def executeTxScript(): BuildExecuteScriptTxResult = {
      executeTxScript(
        BuildExecuteScriptTx(
          fromPublicKey = Hex.unsafe(testPubKey.toHexString),
          bytecode = serialize(scriptCode),
          gasAmount = Some(gasAmount),
          gasPrice = Some(gasPrice),
          attoAlphAmount = attoAlphAmount
        )
      )
    }
  }

  trait GasFeeFixture extends ContractFixture {
    val (testPriKey, testPubKey)          = chainIndex.from.generateKey
    val testAddress                       = Address.p2pkh(testPubKey)
    val (genesisPriKey, genesisPubKey, _) = genesisKeys(0)
    val genesisAddress                    = Address.p2pkh(genesisPubKey)

    def deployContract(
        contract: String,
        initialAttoAlphAmount: Amount = Amount(ALPH.alph(3))
    ): Address.Contract = {
      deployContract(contract, initialAttoAlphAmount, (testPriKey, testPubKey), None)
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

  // Inactive instrs check will be enabled in future upgrades
  ignore should "should throw exception for payGasFee instr before Rhone hardfork" in new GasFeeFixture {
    implicit override lazy val networkConfig: NetworkSetting = config.network.copy(
      rhoneHardForkTimestamp = TimeStamp.unsafe(Long.MaxValue)
    )

    intercept[AssertionError](deployContract(fooContract, Amount(ALPH.alph(3)))).getMessage is
      "BadRequest(Execution error when emulating tx script or contract: InactiveInstr(MethodSelector(Selector(-1928645066))))"
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
    if (hardFork.isDanubeEnabled()) {
      confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 0))
    }
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
    blockFlow.getMemPool(GroupIndex.unsafe(1)).contains(blockTx.id) is false

    blockFlow.getGrandPool().get(deployContractTxResult.txId).isEmpty is false
    confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 0))
    if (hardFork.isDanubeEnabled()) {
      confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 0))
    }
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

    implicit val serverUtils: ServerUtils = new ServerUtils()
    def buildDeployContractTx(
        query: BuildDeployContractTx
    ): BuildSimpleDeployContractTxResult = {
      val result = serverUtils
        .buildDeployContractTx(blockFlow, query)
        .rightValue
        .asInstanceOf[BuildSimpleDeployContractTxResult]
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
      val balance =
        blockFlow.getBalance(lockupScript, defaultUtxoLimit, true).rightValue
      balance.totalAlph is expectedAlphBalance
      balance.totalTokens.find(_._1 == tokenId).map(_._2) is expectedTokenBalance
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
    val balance =
      blockFlow.getBalance(lockupScript, defaultUtxoLimit, true).rightValue
    balance.totalTokens.find(_._1 == tokenId).map(_._2) is Some(U256.unsafe(10))

    val query = BuildDeployContractTx(
      fromPublicKey = publicKey.bytes,
      bytecode = serialize(Code(contract, AVector.empty, AVector.empty)),
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
      bytecode = serialize(Code(contract, AVector.empty, AVector.empty)),
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
      bytecode = serialize(Code(contract, AVector.empty, AVector.empty)),
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

  trait ChainedTransactionsFixture extends ExecuteScriptFixture with ContractFixture {
    def checkAlphBalance(
        lockupScript: LockupScript,
        expectedAlphBalance: U256
    ) = {
      val balance =
        blockFlow.getBalance(lockupScript, defaultUtxoLimit, true).rightValue
      expectedAlphBalance is balance.totalAlph
    }

    def checkTokenBalance(
        lockupScript: LockupScript,
        tokenId: TokenId,
        expectedTokenBalance: U256
    ) = {
      val balance =
        blockFlow.getBalance(lockupScript, defaultUtxoLimit, true).rightValue
      balance.totalTokens
        .find(_._1 == tokenId)
        .map(_._2)
        .getOrElse(U256.Zero) is expectedTokenBalance
    }

    def signAndAndToMemPool(
        buildTransactionResult: GasInfo with ChainIndexInfo with TransactionInfo,
        fromPrivateKey: PrivateKey
    ): TransactionTemplate = {
      val chainIndex =
        ChainIndex.unsafe(buildTransactionResult.fromGroup, buildTransactionResult.toGroup)
      signAndAddToMemPool(
        buildTransactionResult.txId,
        buildTransactionResult.unsignedTx,
        chainIndex,
        fromPrivateKey
      )
    }

    def failedBuildTransferTx(buildTransfer: BuildTransferTx, errorDetails: String) = {
      serverUtils
        .buildTransferTransaction(
          blockFlow,
          buildTransfer
        )
        .leftValue
        .detail is errorDetails
    }

    def buildChainedTransactions(
        buildTransactions: BuildChainedTx*
    ): AVector[BuildChainedTxResult] = {
      serverUtils
        .buildChainedTransactions(
          blockFlow,
          AVector.from(buildTransactions)
        )
        .rightValue
    }

    def failedChainedTransactions(
        buildTransactions: AVector[BuildChainedTx],
        errorDetails: String
    ) = {
      serverUtils
        .buildChainedTransactions(
          blockFlow,
          buildTransactions
        )
        .leftValue
        .detail is errorDetails
    }

    def failedDeployContract(
        buildTransfer: BuildDeployContractTx,
        errorDetails: String
    ) = {
      serverUtils.buildDeployContractTx(blockFlow, buildTransfer).leftValue.detail is errorDetails
    }

    trait GroupInfo {
      val groupIndex: GroupIndex
      val privateKey: PrivateKey
      val publicKey: PublicKey
      val address                  = Address.p2pkh(publicKey)
      def destination(value: U256) = Destination(address, Some(Amount(value)))
    }

    def groupInfo(group: Int): GroupInfo = {
      new GroupInfo {
        lazy val groupIndex: GroupIndex  = GroupIndex.unsafe(group)
        lazy val (privateKey, publicKey) = groupIndex.generateKey
      }
    }

    lazy val (genesisPrivateKey, genesisPublicKey, _) = genesisKeys(0)
    lazy val genesisAddress                           = Address.p2pkh(genesisPublicKey)

    val groupInfo0 = groupInfo(0)
    val groupInfo1 = groupInfo(1)
    val groupInfo2 = groupInfo(2)
    val groupInfo3 = groupInfo(3)

    val address0InitBalance = ALPH.alph(3)
    val transferToAddress0 = serverUtils
      .buildTransferTransaction(
        blockFlow,
        BuildTransferTx(
          genesisPublicKey.bytes,
          None,
          AVector(groupInfo0.destination(address0InitBalance))
        )
      )
      .rightValue
      .asInstanceOf[BuildSimpleTransferTxResult]

    signAndAndToMemPool(transferToAddress0, genesisPrivateKey)
    confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 0))
    checkAlphBalance(groupInfo0.address.lockupScript, address0InitBalance)
  }

  it should "build multiple transfers transactions" in new ChainedTransactionsFixture {
    failedBuildTransferTx(
      BuildTransferTx(
        groupInfo1.publicKey.bytes,
        None,
        AVector(groupInfo2.destination(ALPH.oneAlph))
      ),
      errorDetails = "Not enough balance: got 0, expected 1001000000000000000"
    )

    failedChainedTransactions(
      AVector(
        BuildChainedTransferTx(
          BuildTransferTx(
            groupInfo0.publicKey.bytes,
            None,
            AVector(groupInfo1.destination(ALPH.alph(2)))
          )
        ),
        BuildChainedTransferTx(
          BuildTransferTx(
            groupInfo0.publicKey.bytes,
            None,
            AVector(groupInfo2.destination(ALPH.oneAlph))
          )
        )
      ),
      errorDetails = "Not enough balance: got 998000000000000000, expected 1001000000000000000"
    )

    failedChainedTransactions(
      AVector(
        BuildChainedTransferTx(
          BuildTransferTx(
            groupInfo0.publicKey.bytes,
            None,
            AVector(groupInfo1.destination(ALPH.alph(2)))
          )
        ),
        BuildChainedTransferTx(
          BuildTransferTx(
            groupInfo1.publicKey.bytes,
            None,
            AVector(groupInfo2.destination(ALPH.oneAlph))
          )
        ),
        BuildChainedTransferTx(
          BuildTransferTx(
            groupInfo0.publicKey.bytes,
            None,
            AVector(groupInfo3.destination(ALPH.alph(1)))
          )
        )
      ),
      errorDetails = "Not enough balance: got 998000000000000000, expected 1001000000000000000"
    )

    val buildTransactions = buildChainedTransactions(
      BuildChainedTransferTx(
        BuildTransferTx(
          groupInfo0.publicKey.bytes,
          None,
          AVector(groupInfo1.destination(ALPH.alph(2)))
        )
      ),
      BuildChainedTransferTx(
        BuildTransferTx(
          groupInfo1.publicKey.bytes,
          None,
          AVector(groupInfo2.destination(ALPH.oneAlph))
        )
      ),
      BuildChainedTransferTx(
        BuildTransferTx(
          groupInfo0.publicKey.bytes,
          None,
          AVector(groupInfo3.destination(ALPH.alph(1) - nonCoinbaseMinGasFee * 3))
        )
      )
    )

    buildTransactions.length is 3
    val buildTransferTransaction01 =
      buildTransactions(0).asInstanceOf[BuildChainedTransferTxResult]
    val buildTransferTransaction12 =
      buildTransactions(1).asInstanceOf[BuildChainedTransferTxResult]
    val buildTransferTransaction03 =
      buildTransactions(2).asInstanceOf[BuildChainedTransferTxResult]

    signAndAndToMemPool(buildTransferTransaction01.value, groupInfo0.privateKey)
    signAndAndToMemPool(buildTransferTransaction12.value, groupInfo1.privateKey)
    signAndAndToMemPool(buildTransferTransaction03.value, groupInfo0.privateKey)
    confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 1))
    confirmNewBlock(blockFlow, ChainIndex.unsafe(1, 2))
    confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 3))

    val address0Transfer01Cost = ALPH.alph(2) + buildTransferTransaction01.value.gasFee
    val address0Transfer03Cost =
      ALPH.alph(1) - nonCoinbaseMinGasFee * 3 + buildTransferTransaction03.value.gasFee
    val buildTransferTransaction01GasFee =
      buildTransferTransaction01.value.gasPrice * buildTransferTransaction01.value.gasAmount
    checkAlphBalance(
      groupInfo0.address.lockupScript,
      address0InitBalance - address0Transfer01Cost - address0Transfer03Cost
    )
    checkAlphBalance(
      groupInfo1.address.lockupScript,
      ALPH.oneAlph - buildTransferTransaction01GasFee
    )
    checkAlphBalance(groupInfo2.address.lockupScript, ALPH.oneAlph)
    checkAlphBalance(groupInfo3.address.lockupScript, ALPH.alph(1) - nonCoinbaseMinGasFee * 3)
  }

  it should "build a transfer transaction followed by an execute script transactions" in new ChainedTransactionsFixture {
    def buildExecuteScript(publicKey: PublicKey, attoAlphAmount: Option[Amount] = None) =
      BuildExecuteScriptTx(
        fromPublicKey = publicKey.bytes,
        bytecode = serialize(scriptCode),
        gasAmount = Some(gasAmount),
        gasPrice = Some(gasPrice),
        attoAlphAmount = attoAlphAmount
      )

    failedExecuteTxScript(
      buildExecuteScript(groupInfo1.publicKey),
      errorDetails = "Not enough balance: got 0, expected 5000000000000000"
    )

    failedChainedTransactions(
      AVector(
        BuildChainedTransferTx(
          BuildTransferTx(
            groupInfo0.publicKey.bytes,
            None,
            AVector(groupInfo1.destination(address0InitBalance - nonCoinbaseMinGasFee * 2))
          )
        ),
        BuildChainedExecuteScriptTx(
          buildExecuteScript(groupInfo0.publicKey)
        )
      ),
      errorDetails = "Not enough balance: got 2000000000000000, expected 5000000000000000"
    )

    failedChainedTransactions(
      AVector(
        BuildChainedTransferTx(
          BuildTransferTx(
            groupInfo0.publicKey.bytes,
            None,
            AVector(groupInfo1.destination(ALPH.alph(2)))
          )
        ),
        BuildChainedExecuteScriptTx(
          buildExecuteScript(groupInfo1.publicKey)
        ),
        BuildChainedExecuteScriptTx(
          buildExecuteScript(groupInfo0.publicKey, Some(Amount(ALPH.oneAlph)))
        )
      ),
      errorDetails = "Not enough balance: got 998000000000000000, expected 1005000000000000000"
    )

    val buildTransactions = buildChainedTransactions(
      BuildChainedTransferTx(
        BuildTransferTx(
          groupInfo0.publicKey.bytes,
          None,
          AVector(groupInfo1.destination(ALPH.alph(2)))
        )
      ),
      BuildChainedExecuteScriptTx(
        buildExecuteScript(groupInfo1.publicKey)
      ),
      BuildChainedExecuteScriptTx(
        buildExecuteScript(groupInfo0.publicKey, Some(Amount(ALPH.oneAlph / 2)))
      )
    )

    buildTransactions.length is 3
    val buildTransferTransaction =
      buildTransactions(0).asInstanceOf[BuildChainedTransferTxResult]
    val buildExecuteScriptTransaction1 =
      buildTransactions(1).asInstanceOf[BuildChainedExecuteScriptTxResult]
    val buildExecuteScriptTransaction0 =
      buildTransactions(2).asInstanceOf[BuildChainedExecuteScriptTxResult]

    signAndAndToMemPool(buildTransferTransaction.value, groupInfo0.privateKey)
    signAndAndToMemPool(buildExecuteScriptTransaction1.value, groupInfo1.privateKey)
    signAndAndToMemPool(buildExecuteScriptTransaction0.value, groupInfo0.privateKey)
    if (hardFork.isDanubeEnabled()) {
      addAndCheck(blockFlow, emptyBlock(blockFlow, ChainIndex.unsafe(0, 1)))
    }
    val block0 = confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 1))
    block0.nonCoinbaseLength is 1

    addAndCheck(blockFlow, emptyBlock(blockFlow, ChainIndex.unsafe(0, 0)))
    if (hardFork.isDanubeEnabled()) {
      addAndCheck(blockFlow, emptyBlock(blockFlow, ChainIndex.unsafe(1, 1)))
    }
    val block1 = confirmNewBlock(blockFlow, buildExecuteScriptTransaction1.value.chainIndex().value)
    val block2 = confirmNewBlock(blockFlow, buildExecuteScriptTransaction0.value.chainIndex().value)
    block1.nonCoinbaseLength is 1
    block2.nonCoinbaseLength is 1

    checkAlphBalance(
      groupInfo0.address.lockupScript,
      address0InitBalance - ALPH.alph(
        2
      ) - buildTransferTransaction.value.gasFee - buildExecuteScriptTransaction0.value.gasFee
    )
    checkAlphBalance(
      groupInfo1.address.lockupScript,
      ALPH.oneAlph * 2 - buildExecuteScriptTransaction1.value.gasFee
    )
  }

  it should "build a transfer transaction followed by a deploy contract transactions" in new ChainedTransactionsFixture {
    val contractCode =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val contract = Compiler.compileContract(contractCode).toOption.get

    def buildDeployContract(publicKey: PublicKey, initialAttoAlphAmount: Option[Amount] = None) =
      BuildDeployContractTx(
        fromPublicKey = publicKey.bytes,
        initialAttoAlphAmount = initialAttoAlphAmount,
        bytecode = serialize(contract) ++ ByteString(0, 0)
      )

    failedDeployContract(
      buildDeployContract(groupInfo1.publicKey),
      errorDetails = "Insufficient funds for gas"
    )

    failedChainedTransactions(
      AVector(
        BuildChainedTransferTx(
          BuildTransferTx(
            groupInfo0.publicKey.bytes,
            None,
            AVector(groupInfo1.destination(address0InitBalance - nonCoinbaseMinGasFee * 10))
          )
        ),
        BuildChainedDeployContractTx(
          buildDeployContract(groupInfo0.publicKey)
        )
      ),
      errorDetails =
        s"Execution error when emulating tx script or contract: Not enough approved balance for address ${groupInfo0.address.toBase58}, tokenId: ALPH, expected: 100000000000000000, got: 16000000000000000"
    )

    failedChainedTransactions(
      AVector(
        BuildChainedTransferTx(
          BuildTransferTx(
            groupInfo0.publicKey.bytes,
            None,
            AVector(groupInfo1.destination(ALPH.alph(2)))
          )
        ),
        BuildChainedDeployContractTx(
          buildDeployContract(groupInfo1.publicKey)
        ),
        BuildChainedDeployContractTx(
          buildDeployContract(
            groupInfo0.publicKey,
            initialAttoAlphAmount = Some(Amount(ALPH.alph(1)))
          )
        )
      ),
      errorDetails =
        s"Execution error when emulating tx script or contract: Not enough approved balance for address ${groupInfo0.address.toBase58}, tokenId: ALPH, expected: 1000000000000000000, got: 996000000000000000"
    )

    val buildTransactions = buildChainedTransactions(
      BuildChainedTransferTx(
        BuildTransferTx(
          groupInfo0.publicKey.bytes,
          None,
          AVector(groupInfo1.destination(ALPH.alph(2)))
        )
      ),
      BuildChainedDeployContractTx(
        buildDeployContract(groupInfo1.publicKey)
      ),
      BuildChainedDeployContractTx(
        buildDeployContract(
          groupInfo0.publicKey,
          initialAttoAlphAmount = Some(Amount(ALPH.alph(1) / 2))
        )
      )
    )

    buildTransactions.length is 3
    val buildTransferTransaction =
      buildTransactions(0).asInstanceOf[BuildChainedTransferTxResult]
    val buildDeployContractTransaction1 =
      buildTransactions(1).asInstanceOf[BuildChainedDeployContractTxResult]
    val buildDeployContractTransaction0 =
      buildTransactions(2).asInstanceOf[BuildChainedDeployContractTxResult]

    signAndAndToMemPool(buildTransferTransaction.value, groupInfo0.privateKey)
    signAndAndToMemPool(buildDeployContractTransaction1.value, groupInfo1.privateKey)
    signAndAndToMemPool(buildDeployContractTransaction0.value, groupInfo0.privateKey)
    confirmNewBlock(blockFlow, ChainIndex.unsafe(0, 1))
    confirmNewBlock(blockFlow, buildDeployContractTransaction1.value.chainIndex().value)
    confirmNewBlock(blockFlow, buildDeployContractTransaction0.value.chainIndex().value)

    val address0TransferCost       = buildTransferTransaction.value.gasFee + ALPH.alph(2)
    val address0DeployContractCost = buildDeployContractTransaction0.value.gasFee + ALPH.alph(1) / 2
    checkAlphBalance(
      groupInfo0.address.lockupScript,
      address0InitBalance - address0TransferCost - address0DeployContractCost
    )
    checkAlphBalance(
      groupInfo1.address.lockupScript,
      ALPH.oneAlph * 2 - minimalAlphInContract - buildDeployContractTransaction1.value.gasFee
    )
  }

  it should "build chained execute script transactions" in new ChainedTransactionsFixture {
    val tokenContract1 =
      s"""
         |Contract TokenContract1() {
         |  @using(assetsInContract = true)
         |  pub fn withdraw() -> () {
         |    transferTokenFromSelf!(callerAddress!(), selfTokenId!(), 1)
         |  }
         |}
         |""".stripMargin

    val tokenContract1Address = deployContract(
      tokenContract1,
      initialAttoAlphAmount = Amount(ALPH.alph(5)),
      keyPair = (genesisPrivateKey, genesisPublicKey),
      issueTokenAmount = Some(Amount(U256.unsafe(100)))
    )

    val tokenId1 = TokenId.from(tokenContract1Address.contractId)
    checkTokenBalance(tokenContract1Address.lockupScript, tokenId1, U256.unsafe(100))

    val tokenContract2 =
      s"""
         |Contract TokenContract2() {
         |  @using(assetsInContract = true, preapprovedAssets = true)
         |  pub fn withdraw() -> () {
         |    assert!(tokenRemaining!(callerAddress!(), #${tokenId1.toHexString}) > 0, 0)
         |    transferTokenFromSelf!(callerAddress!(), selfTokenId!(), 1)
         |  }
         |}
         |""".stripMargin

    val tokenContract2Address = deployContract(
      tokenContract2,
      initialAttoAlphAmount = Amount(ALPH.alph(5)),
      keyPair = (genesisPrivateKey, genesisPublicKey),
      issueTokenAmount = Some(Amount(U256.unsafe(200)))
    )

    val tokenId2 = TokenId.from(tokenContract2Address.contractId)
    checkTokenBalance(tokenContract2Address.lockupScript, tokenId2, U256.unsafe(200))

    val withdrawToken2Script =
      s"""
         |TxScript Main {
         |  TokenContract2(#${tokenContract2Address.toBase58}).withdraw{callerAddress!() -> #${tokenId1.toHexString}:1}()
         |}
         |$tokenContract2
         |""".stripMargin

    val withdrawToken2ScriptCode = Compiler.compileTxScript(withdrawToken2Script).toOption.get
    val buildWithdrawToken2ExecuteScriptTx = BuildExecuteScriptTx(
      fromPublicKey = genesisPublicKey.bytes,
      bytecode = serialize(withdrawToken2ScriptCode),
      attoAlphAmount = Some(Amount(ALPH.alph(1))),
      tokens = Some(AVector(Token(tokenId1, U256.unsafe(1))))
    )

    failedExecuteTxScript(
      buildWithdrawToken2ExecuteScriptTx,
      errorDetails =
        s"Execution error when emulating tx script or contract: Not enough approved balance for address ${genesisAddress.toBase58}, tokenId: ${tokenId1.toHexString}, expected: 1, got: 0"
    )

    val withdrawToken1Script =
      s"""
         |TxScript Main {
         |  TokenContract1(#${tokenContract1Address.toBase58}).withdraw()
         |}
         |$tokenContract1
         |""".stripMargin

    val withdrawToken1ScriptCode = Compiler.compileTxScript(withdrawToken1Script).toOption.get
    val buildWithdrawToken1ExecuteScriptTx = BuildExecuteScriptTx(
      fromPublicKey = genesisPublicKey.bytes,
      attoAlphAmount = Some(Amount(ALPH.alph(1))),
      bytecode = serialize(withdrawToken1ScriptCode)
    )

    val buildTransactions = buildChainedTransactions(
      BuildChainedExecuteScriptTx(buildWithdrawToken1ExecuteScriptTx),
      BuildChainedExecuteScriptTx(buildWithdrawToken2ExecuteScriptTx)
    )

    buildTransactions.length is 2
    val buildWithdrawToken1ExecuteScriptTxResult =
      buildTransactions(0).asInstanceOf[BuildChainedExecuteScriptTxResult]
    val buildWithdrawToken2ExecuteScriptTxResult =
      buildTransactions(1).asInstanceOf[BuildChainedExecuteScriptTxResult]

    signAndAndToMemPool(buildWithdrawToken1ExecuteScriptTxResult.value, genesisPrivateKey)
    signAndAndToMemPool(buildWithdrawToken2ExecuteScriptTxResult.value, genesisPrivateKey)
    confirmNewBlock(blockFlow, buildWithdrawToken1ExecuteScriptTxResult.value.chainIndex().value)
    confirmNewBlock(blockFlow, buildWithdrawToken2ExecuteScriptTxResult.value.chainIndex().value)
    checkTokenBalance(genesisAddress.lockupScript, tokenId1, U256.unsafe(1))
    checkTokenBalance(genesisAddress.lockupScript, tokenId2, U256.unsafe(1))
  }

  it should "only take generated asset outputs in buildChainedTransaction" in new ChainedTransactionsFixture {
    val tokenContract =
      s"""
         |Contract TokenContract() {
         |  @using(assetsInContract = true)
         |  pub fn withdraw() -> () {
         |    transferTokenFromSelf!(callerAddress!(), selfTokenId!(), 1)
         |  }
         |}
         |""".stripMargin

    val tokenContractAddress = deployContract(
      tokenContract,
      initialAttoAlphAmount = Amount(ALPH.alph(5)),
      keyPair = (genesisPrivateKey, genesisPublicKey),
      issueTokenAmount = Some(Amount(U256.unsafe(100)))
    )
    val tokenId                   = TokenId.from(tokenContractAddress.contractId)
    val tokenContractLockupScript = LockupScript.p2c(tokenContractAddress.contractId)

    val withdrawTokenScript =
      s"""
         |TxScript Main {
         |  TokenContract(#${tokenContractAddress.toBase58}).withdraw()
         |}
         |$tokenContract
         |""".stripMargin

    val withdrawTokenScriptCode = Compiler.compileTxScript(withdrawTokenScript).toOption.get
    val buildWithdrawTokenExecuteScriptTx = BuildExecuteScriptTx(
      fromPublicKey = genesisPublicKey.bytes,
      attoAlphAmount = Some(Amount(ALPH.alph(1))),
      bytecode = serialize(withdrawTokenScriptCode)
    )

    val (unsignedTx, txScriptExecution) = serverUtils
      .buildExecuteScriptUnsignedTx(
        blockFlow,
        buildWithdrawTokenExecuteScriptTx,
        ExtraUtxosInfo.empty
      )
      .rightValue

    val genesisAddressUtxos =
      serverUtils.getUTXOsIncludePool(blockFlow, genesisAddress).rightValue.utxos
    genesisAddressUtxos.length is 1
    val genesisAddressUtxosAmount = genesisAddressUtxos.head.amount.value

    val fixedOutputs = unsignedTx.fixedOutputs
    fixedOutputs.length is 1
    fixedOutputs.head.lockupScript is genesisAddress.lockupScript
    fixedOutputs.head.amount is genesisAddressUtxosAmount - ALPH.alph(1) - unsignedTx.gasFee
    fixedOutputs.head.tokens is AVector.empty[(TokenId, U256)]

    val contractPrevOutputs = txScriptExecution.contractPrevOutputs
    contractPrevOutputs.length is 1
    contractPrevOutputs.head.amount is ALPH.alph(5)
    contractPrevOutputs.head.tokens is AVector(tokenId -> U256.unsafe(100))

    val simulatedGeneratedOutputs = txScriptExecution.generatedOutputs
    simulatedGeneratedOutputs.length is 3
    simulatedGeneratedOutputs(0).lockupScript is genesisAddress.lockupScript
    simulatedGeneratedOutputs(0).amount is dustUtxoAmount
    simulatedGeneratedOutputs(0).tokens is AVector(tokenId -> U256.unsafe(1))
    simulatedGeneratedOutputs(1).lockupScript is genesisAddress.lockupScript
    simulatedGeneratedOutputs(1).amount is 999000000000000000L
    simulatedGeneratedOutputs(1).tokens is AVector.empty[(TokenId, U256)]
    simulatedGeneratedOutputs(2).lockupScript is tokenContractLockupScript
    simulatedGeneratedOutputs(2).amount is ALPH.alph(5)
    simulatedGeneratedOutputs(2).tokens is AVector(tokenId -> U256.unsafe(99))

    val (_, extraUtxosInfo) = serverUtils
      .buildChainedTransaction(
        blockFlow,
        BuildChainedExecuteScriptTx(buildWithdrawTokenExecuteScriptTx),
        ExtraUtxosInfo.empty
      )
      .rightValue

    val generatedOutputs = Output.fromGeneratedOutputs(unsignedTx, simulatedGeneratedOutputs)
    val generatedAssetOutputs = txScriptExecution.generatedOutputs.collect {
      case output: model.AssetOutput => Some(output)
      case _                         => None
    }

    generatedAssetOutputs.length is 2
    extraUtxosInfo.newUtxos.map(_.output) is (unsignedTx.fixedOutputs ++ generatedAssetOutputs)
    extraUtxosInfo.newUtxos.tail.map(o => (o.ref.hint.value, o.ref.key.value)) is
      generatedOutputs.filter(_.toProtocol().isAsset).map(o => (o.hint, o.key))
  }

  it should "should estimate the generated output correctly" in new ChainedTransactionsFixture {
    val tokenContract =
      s"""
         |Contract TokenContract() {
         |  @using(assetsInContract = true)
         |  pub fn withdraw() -> () {
         |    transferTokenFromSelf!(callerAddress!(), ALPH, 1 alph)
         |  }
         |}
         |""".stripMargin

    val tokenContractAddress = deployContract(
      tokenContract,
      initialAttoAlphAmount = Amount(ALPH.alph(5)),
      keyPair = (genesisPrivateKey, genesisPublicKey),
      issueTokenAmount = None
    )
    val tokenContractLockupScript = LockupScript.p2c(tokenContractAddress.contractId)

    val withdrawALPHScript =
      s"""
         |TxScript Main {
         |  TokenContract(#${tokenContractAddress}).withdraw()
         |}
         |$tokenContract
         |""".stripMargin

    val withdrawALPHScriptCode = Compiler.compileTxScript(withdrawALPHScript).toOption.get

    val (_, testPubKey)  = chainIndex.from.generateKey
    val testLockupScript = LockupScript.p2pkh(testPubKey)
    val block            = transfer(blockFlow, genesisKeys(1)._1, testPubKey, ALPH.alph(5))
    addAndCheck(blockFlow, block)

    val buildWithdrawALPHExecuteScriptTx = BuildExecuteScriptTx(
      fromPublicKey = testPubKey.bytes,
      bytecode = serialize(withdrawALPHScriptCode)
    )

    val (unsignedTx, txScriptExecution) = serverUtils
      .buildExecuteScriptUnsignedTx(
        blockFlow,
        buildWithdrawALPHExecuteScriptTx,
        ExtraUtxosInfo.empty
      )
      .rightValue

    val fixedOutputs = unsignedTx.fixedOutputs
    fixedOutputs.length is 1
    fixedOutputs.head.lockupScript is testLockupScript
    fixedOutputs.head.amount is ALPH.alph(5) - unsignedTx.gasFee

    val contractPrevOutputs = txScriptExecution.contractPrevOutputs
    contractPrevOutputs.length is 1
    contractPrevOutputs.head.lockupScript is tokenContractLockupScript
    contractPrevOutputs.head.amount is ALPH.alph(5)

    val simulatedGeneratedOutputs = txScriptExecution.generatedOutputs
    simulatedGeneratedOutputs.length is 2
    simulatedGeneratedOutputs(0).lockupScript is testLockupScript
    simulatedGeneratedOutputs(0).amount is U256.unsafe(1000000000000000000L)
    simulatedGeneratedOutputs(1).lockupScript is tokenContractLockupScript
    simulatedGeneratedOutputs(1).amount is ALPH.alph(4)
  }

  it should "get ghost uncles" in new Fixture with GhostUncleFixture {
    val chainIndex      = ChainIndex.unsafe(0, 0)
    val ghostUncleHash  = mineUncleBlocks(blockFlow, chainIndex, 1).head
    val ghostUncleBlock = blockFlow.getBlock(ghostUncleHash).rightValue
    val block           = mineBlockTemplate(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)

    val serverUtils = new ServerUtils()
    serverUtils.getBlock(blockFlow, block.hash).rightValue.ghostUncles is
      AVector(
        GhostUncleBlockEntry(ghostUncleHash, Address.Asset(ghostUncleBlock.minerLockupScript))
      )
  }

  it should "get mainchain block by ghost uncle hash" in new Fixture with GhostUncleFixture {
    val chainIndex     = ChainIndex.unsafe(0, 0)
    val ghostUncleHash = mineUncleBlocks(blockFlow, chainIndex, 1).head

    val serverUtils = new ServerUtils()
    serverUtils.getMainChainBlockByGhostUncle(blockFlow, ghostUncleHash).leftValue.detail is
      s"The mainchain block that references the ghost uncle block ${ghostUncleHash.toHexString} not found"

    val block = mineBlockTemplate(blockFlow, chainIndex)
    block.ghostUncleHashes.rightValue is AVector(ghostUncleHash)
    addAndCheck(blockFlow, block)
    serverUtils.getMainChainBlockByGhostUncle(blockFlow, ghostUncleHash).rightValue is
      BlockEntry.from(block, blockFlow.getHeightUnsafe(block.hash)).rightValue

    val invalidBlockHash = randomBlockHash(chainIndex)
    serverUtils.getMainChainBlockByGhostUncle(blockFlow, invalidBlockHash).leftValue.detail is
      s"The block ${invalidBlockHash.toHexString} does not exist, please check if your full node synced"
    serverUtils.getMainChainBlockByGhostUncle(blockFlow, block.hash).leftValue.detail is
      s"The block ${block.hash.toHexString} is not a ghost uncle block, you should use a ghost uncle block hash to call this endpoint"
  }

  it should "return block information" in new Fixture {
    val chainIndex       = ChainIndex.unsafe(0, 0)
    val serverUtils      = new ServerUtils()
    val invalidBlockHash = randomBlockHash(chainIndex)
    val block            = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)
    serverUtils.getBlock(blockFlow, block.hash).rightValue is BlockEntry.from(block, 1).rightValue
    serverUtils.getBlock(blockFlow, invalidBlockHash).leftValue.detail is
      s"The block ${invalidBlockHash.toHexString} does not exist, please check if your full node synced"

    val transactions =
      block.transactions
        .mapE(tx => serverUtils.getRichTransaction(blockFlow, tx, block.hash))
        .rightValue
    serverUtils.getRichBlockAndEvents(blockFlow, block.hash).rightValue is RichBlockAndEvents(
      RichBlockEntry
        .from(block, 1, transactions)
        .rightValue,
      AVector.empty
    )
    serverUtils.getRichBlockAndEvents(blockFlow, invalidBlockHash).leftValue.detail is
      s"The block ${invalidBlockHash.toHexString} does not exist, please check if your full node synced"

    serverUtils.getRawBlock(blockFlow, block.hash).rightValue is RawBlock(serialize(block))
    serverUtils.getRawBlock(blockFlow, invalidBlockHash).leftValue.detail is
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

  trait TxContractOutputRefIndexInForkedChainFixture
      extends ContractFixture
      with ChainedTransactionsFixture {
    val testKeyPair = chainIndex.to.generateKey

    val contractCode =
      s"""
         |Contract TokenContract(mut burnAmount: U256) {
         |  @using(assetsInContract = true)
         |  pub fn burn() -> () {
         |    burnToken!(selfAddress!(), selfTokenId!(), burnAmount)
         |    transferTokenFromSelf!(callerAddress!(), ALPH, 1 alph)
         |  }
         |
         |  @using(updateFields = true)
         |  pub fn updateBurnAmount(amount: U256) -> () {
         |    burnAmount = amount
         |  }
         |}
         |""".stripMargin

    val contractId = createContract(
      contractCode,
      initialMutState = AVector(vm.Val.U256(ALPH.oneAlph)),
      initialAttoAlphAmount = ALPH.alph(100),
      tokenIssuanceInfo = Some(TokenIssuance.Info(ALPH.alph(20)))
    )._1

    val updateBurnAmountScriptCode =
      s"""
         |TxScript Main {
         |  TokenContract(#${contractId.toHexString}).updateBurnAmount(5 alph)
         |}
         |$contractCode
         |""".stripMargin

    val updateBurnAmountScript = Compiler.compileTxScript(updateBurnAmountScriptCode).rightValue

    val burnTokenScriptCode =
      s"""
         |TxScript Main {
         |  TokenContract(#${contractId.toHexString}).burn()
         |}
         |$contractCode
         |""".stripMargin

    val burnTokenScript = Compiler.compileTxScript(burnTokenScriptCode).rightValue

    def verifyAndUpdateContractGeneratedOutput(
        block: Block,
        tokenAmount: U256,
        newTokenAmount: U256
    ): Block = {
      block.nonCoinbase.length is 1
      val tx               = block.nonCoinbase.head
      val generatedOutputs = tx.generatedOutputs
      generatedOutputs.length is 2
      val contractOutputIndex = generatedOutputs.indexWhere(_.isContract)
      val generatedOutput = generatedOutputs(contractOutputIndex).asInstanceOf[ModelContractOutput]
      generatedOutput.tokens.head._2 is tokenAmount

      block.copy(
        transactions = block.transactions.replace(
          0,
          tx.copy(
            generatedOutputs = generatedOutputs.replace(
              contractOutputIndex,
              generatedOutput.copy(tokens =
                AVector((generatedOutput.tokens.head._1, newTokenAmount))
              )
            )
          )
        )
      )
    }

    def getContractInput(block: Block): RichContractInput = {
      val richBlockAndEvents = serverUtils
        .getRichBlockAndEvents(blockFlow, block.hash)
        .rightValue
      richBlockAndEvents.block.transactions.head.contractInputs.length is 1
      richBlockAndEvents.block.transactions.head.contractInputs.head
    }

    // main0  -> main1 -> main2 (update burn amount to 5)  -> main3 (genesis calls burn) -> main4 (genesis calls burn)
    //        -> fork1 -> fork2 (genesis calls burn)       -> fork3 (genesis calls burn)
    val main0 = transfer(blockFlow, genesisKeys(0)._1, testKeyPair._2, ALPH.alph(2))
    addAndCheck(blockFlow, main0)

    val block1 = emptyBlock(blockFlow, chainIndex)
    val block2 = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block1, block2)

    val height4Hashes = blockFlow.getHashes(chainIndex, 4).rightValue
    height4Hashes.toSet is Set(block1.hash, block2.hash)

    val main1 = blockFlow.getBlockUnsafe(height4Hashes(0))
    val fork1 = blockFlow.getBlockUnsafe(height4Hashes(1))
    main1.parentHash is main0.hash
    fork1.parentHash is main0.hash

    val main2 = payableCall(
      blockFlow,
      chainIndex,
      updateBurnAmountScript,
      keyPairOpt = Some(testKeyPair)
    )
    addAndCheck(blockFlow, main2)
    main2.parentHash is main1.hash

    def main3: Block
    def fork2: Block
    def main4: Block
    def fork3: Block

    // scalastyle:off method.length
    def verifyForkedChain(): Assertion = {
      main2.nonCoinbase.length is 1
      main2.nonCoinbase.head.inputsLength is 1
      val testKeyTxOutputRef = main2.nonCoinbase.head.unsigned.inputs(0).outputRef
      val testKeyTx          = main0.nonCoinbase.head.unsigned
      val testKeyTxOutput    = testKeyTx.fixedOutputs(0)
      blockFlow.getTxOutput(testKeyTxOutputRef, main2.hash, maxForkDepth) isE Some(
        (
          testKeyTx.id,
          testKeyTxOutput
        )
      )

      // Same tx in both main3 and fork2
      main3.nonCoinbase.head.id is fork2.nonCoinbase.head.id

      val fork3ContractInput = getContractInput(fork3)
      val main4ContractInput = getContractInput(main4)
      fork3ContractInput.key is main4ContractInput.key
      fork3ContractInput.hint is main4ContractInput.hint
      fork3ContractInput.tokens.head.amount is ALPH.alph(19)
      main4ContractInput.tokens.head.amount is ALPH.alph(15)

      val contractOutputRef = ContractOutputRef.unsafe(
        Hint.ofContract(LockupScript.p2c(contractId).scriptHint),
        TxOutputRef.unsafeKey(fork3ContractInput.key)
      )

      val contractOutputMain3 = ModelContractOutput(
        amount = ALPH.alph(99),
        lockupScript = LockupScript.p2c(contractId),
        tokens = AVector((TokenId.from(contractId), ALPH.alph(15)))
      )

      blockFlow.getTxOutput(contractOutputRef, main4.hash, 0) isE Some(
        (
          main3.nonCoinbase.head.id,
          contractOutputMain3
        )
      )
      blockFlow.getTxOutput(contractOutputRef, main4.hash, 1) isE Some(
        (
          main3.nonCoinbase.head.id,
          contractOutputMain3
        )
      )
      blockFlow.getTxOutput(contractOutputRef, main4.hash, maxForkDepth) isE Some(
        (
          main3.nonCoinbase.head.id,
          contractOutputMain3
        )
      )

      val contractOutputFork2 = ModelContractOutput(
        amount = ALPH.alph(99),
        lockupScript = LockupScript.p2c(contractId),
        tokens = AVector((TokenId.from(contractId), ALPH.alph(19)))
      )
      blockFlow.getTxOutput(contractOutputRef, fork3.hash, 0) isE Some(
        (
          main3.nonCoinbase.head.id,
          contractOutputMain3
        )
      )
      blockFlow.getTxOutput(contractOutputRef, fork3.hash, 1) isE Some(
        (
          main3.nonCoinbase.head.id,
          contractOutputFork2
        )
      )
      blockFlow.getTxOutput(contractOutputRef, fork3.hash, maxForkDepth) isE Some(
        (
          main3.nonCoinbase.head.id,
          contractOutputFork2
        )
      )
    }
  }

  it should "return correct transaction output info in forked chain, build transactions one by one" in new TxContractOutputRefIndexInForkedChainFixture {
    val main3 = payableCall(blockFlow, chainIndex, burnTokenScript)
    addAndCheck(blockFlow, main3)
    main3.parentHash is main2.hash

    val main3Updated = verifyAndUpdateContractGeneratedOutput(main3, ALPH.alph(15), ALPH.alph(19))

    val fork2 = mineBlock(fork1.hash, main3Updated, 4)
    addAndCheck(blockFlow, fork2)
    fork2.parentHash is fork1.hash

    val main4 = payableCall(blockFlow, chainIndex, burnTokenScript)
    addAndCheck(blockFlow, main4)
    main4.parentHash is main3.hash

    val main4Updated = verifyAndUpdateContractGeneratedOutput(main4, ALPH.alph(10), ALPH.alph(18))

    val fork3 = mineBlock(fork2.hash, main4Updated, 5)
    addAndCheck(blockFlow, fork3)
    fork3.parentHash is fork2.hash

    verifyForkedChain()
  }

  it should "return correct transaction output info in forked chain, with chained transactions" in new TxContractOutputRefIndexInForkedChainFixture {
    val burnTokenScriptTx = BuildChainedExecuteScriptTx(
      BuildExecuteScriptTx(genesisPublicKey.bytes, bytecode = serialize(burnTokenScript))
    )
    val burnTokenTxsResults = buildChainedTransactions(burnTokenScriptTx, burnTokenScriptTx)
    burnTokenTxsResults.length is 2
    val burnTokenTx0 = burnTokenTxsResults(0).value
    val burnTokenTx1 = burnTokenTxsResults(1).value
    signAndAddToMemPool(burnTokenTx0.txId, burnTokenTx0.unsignedTx, chainIndex, genesisPrivateKey)
    val main3 = mineFromMemPool(blockFlow, chainIndex)
    addAndCheck(blockFlow, main3)

    val main3Updated = verifyAndUpdateContractGeneratedOutput(main3, ALPH.alph(15), ALPH.alph(19))
    val fork2        = mineBlock(fork1.hash, main3Updated, 4)
    addAndCheck(blockFlow, fork2)
    fork2.parentHash is fork1.hash

    signAndAddToMemPool(burnTokenTx1.txId, burnTokenTx1.unsignedTx, chainIndex, genesisPrivateKey)
    val main4 = mineFromMemPool(blockFlow, chainIndex)
    addAndCheck(blockFlow, main4)

    val main4Updated = verifyAndUpdateContractGeneratedOutput(main4, ALPH.alph(10), ALPH.alph(18))
    val fork3        = mineBlock(fork2.hash, main4Updated, 5)
    addAndCheck(blockFlow, fork3)
    fork3.parentHash is fork2.hash

    verifyForkedChain()
  }

  it should "return correct transaction output for coinbase transactions" in new Fixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.node.indexes.tx-output-ref-index", "true")
    )

    val chainIndex                      = ChainIndex.unsafe(0, 0)
    val (fromPrivateKey, fromPublicKey) = chainIndex.to.generateKey
    val (_, toPublicKey)                = chainIndex.to.generateKey
    val lockupScript                    = LockupScript.p2pkh(fromPublicKey)
    val blockTs = TimeStamp.now().minusUnsafe(networkConfig.coinbaseLockupPeriod)
    val block0 = mine(
      blockFlow,
      chainIndex,
      AVector.empty[Transaction],
      lockupScript,
      Some(blockTs)
    )
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, fromPrivateKey, toPublicKey, dustUtxoAmount)
    addAndCheck(blockFlow, block1)

    block1.nonCoinbase.length is 1
    block1.nonCoinbase.head.inputsLength is 1
    val txOutputRef    = block1.nonCoinbase.head.unsigned.inputs(0).outputRef
    val coinbaseTx     = block0.coinbase.unsigned
    val coinbaseOutput = coinbaseTx.fixedOutputs(0)
    blockFlow.getTxOutput(txOutputRef, block1.hash) isE Some((coinbaseTx.id, coinbaseOutput))
  }

  it should "return correct tx output when original tx exists in forked blocks and the spending tx's block height is lower" in new Fixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.node.indexes.tx-output-ref-index", "true")
    )

    val chainIndex00                  = ChainIndex.unsafe(0, 0)
    val chainIndex01                  = ChainIndex.unsafe(0, 1)
    val (toPrivateKey0, toPublicKey0) = chainIndex00.to.generateKey
    val (_, toPublicKey1)             = chainIndex01.to.generateKey

    val block00_1 = mineFromMemPool(blockFlow, chainIndex00)
    addAndCheck(blockFlow, block00_1)

    val block00_2 = mineFromMemPool(blockFlow, chainIndex00)
    addAndCheck(blockFlow, block00_2)

    val block00_31 = transfer(blockFlow, genesisKeys(0)._1, toPublicKey0, ALPH.alph(2))
    val block00_32 = transfer(blockFlow, genesisKeys(0)._1, toPublicKey0, ALPH.alph(2))
    addAndCheck(blockFlow, block00_31, block00_32)

    val heightHashes00_3 = blockFlow.getHashes(chainIndex00, 3).rightValue
    heightHashes00_3.toSet is Set(block00_31.hash, block00_32.hash)

    val block01_1 = transfer(blockFlow, toPrivateKey0, toPublicKey1, ALPH.alph(1))
    addAndCheck(blockFlow, block01_1)

    val heightHashes01_1 = blockFlow.getHashes(chainIndex01, 1).rightValue
    heightHashes01_1.toSet is Set(block01_1.hash)

    val txOutputRef = block01_1.nonCoinbase.head.unsigned.inputs(0).outputRef
    val outputTx    = block00_31.nonCoinbase.head.unsigned
    val output      = outputTx.fixedOutputs(0)
    blockFlow.getTxOutput(txOutputRef, block01_1.hash) isE Some((outputTx.id, output))
  }

  it should "return error if the BuildTransaction.ExecuteScript is invalid" in new Fixture {
    val chainIndex        = ChainIndex.unsafe(0, 0)
    val (_, publicKey, _) = genesisKeys(chainIndex.from.value)
    val serverUtils       = new ServerUtils()
    serverUtils
      .buildExecuteScriptTx(
        blockFlow,
        BuildExecuteScriptTx(
          fromPublicKey = publicKey.bytes,
          bytecode = ByteString.empty,
          gasEstimationMultiplier = Some(1.05),
          gasAmount = Some(GasBox.unsafe(20000))
        )
      )
      .leftValue
      .detail is "Parameters `gasAmount` and `gasEstimationMultiplier` cannot be specified simultaneously"

    serverUtils
      .buildExecuteScriptTx(
        blockFlow,
        BuildExecuteScriptTx(
          fromPublicKey = publicKey.bytes,
          bytecode = ByteString.empty,
          gasEstimationMultiplier = Some(1.005)
        )
      )
      .leftValue
      .detail is "Invalid gas estimation multiplier precision, maximum allowed precision is 2"
  }

  it should "build execute script tx with group derived from contract in TxScript" in new ContractFixture {
    setHardForkSince(HardFork.Danube)
    val (_, fromPublicKey) = SecP256R1.generatePriPub()
    val publicKeyLike      = PublicKeyLike.SecP256R1(fromPublicKey)

    val (genesisPrivateKey, _, _) = genesisKeys(chainIndex.from.value)
    val block = transferWithGas(
      blockFlow,
      genesisPrivateKey,
      LockupScript.p2pk(publicKeyLike, chainIndex.from),
      AVector.empty[(TokenId, U256)],
      ALPH.alph(10),
      nonCoinbaseMinGasPrice
    )
    addAndCheck(blockFlow, block)

    val foo =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    emit Debug(`foo`)
         |  }
         |}
         |""".stripMargin

    val (_, fooId) = createContract(foo, AVector.empty, AVector.empty)
    val script =
      s"""
         |TxScript Main {
         |  Foo(#${fooId.toHexString}).foo()
         |}
         |$foo
         |""".stripMargin

    val scriptBytecode = serialize(Compiler.compileTxScript(script).rightValue)
    val query = BuildExecuteScriptTx(
      fromPublicKey.bytes,
      fromPublicKeyType = Some(BuildTxCommon.GLSecP256R1),
      scriptBytecode,
      group = None
    )

    val result = serverUtils.buildExecuteScriptTx(blockFlow, query).rightValue
    result.fromGroup is fooId.groupIndex.value
  }

  it should "estimate gas using gas estimation multiplier" in new ContractFixture {
    val (genesisPrivateKey, genesisPublicKey, _) = genesisKeys(chainIndex.from.value)
    val (privateKey, publicKey)                  = chainIndex.from.generateKey
    (0 to 10).foreach { _ =>
      val block = transfer(blockFlow, genesisPrivateKey, publicKey, ALPH.alph(1))
      addAndCheck(blockFlow, block)
    }

    val foo =
      s"""
         |Contract Foo(mut bytes: ByteVec) {
         |  pub fn foo() -> () {
         |    bytes = bytes ++ #00
         |  }
         |}
         |""".stripMargin

    val (_, fooId) =
      createContract(foo, AVector.empty, AVector[vm.Val](vm.Val.ByteVec(ByteString.empty)))
    val script =
      s"""
         |TxScript Main {
         |  Foo(#${fooId.toHexString}).foo()
         |}
         |$foo
         |""".stripMargin

    val scriptBytecode = serialize(Compiler.compileTxScript(script).rightValue)
    val result0 = serverUtils
      .buildExecuteScriptTx(
        blockFlow,
        BuildExecuteScriptTx(
          fromPublicKey = publicKey.bytes,
          bytecode = scriptBytecode,
          attoAlphAmount = Some(Amount(ALPH.alph(10)))
        )
      )
      .rightValue
      .asInstanceOf[BuildSimpleExecuteScriptTxResult]

    val result1 = serverUtils
      .buildExecuteScriptTx(
        blockFlow,
        BuildExecuteScriptTx(
          fromPublicKey = publicKey.bytes,
          bytecode = scriptBytecode,
          attoAlphAmount = Some(Amount(ALPH.alph(10))),
          gasEstimationMultiplier = Some(1.01)
        )
      )
      .rightValue
      .asInstanceOf[BuildSimpleExecuteScriptTxResult]

    result1.gasAmount.value > result0.gasAmount.value is true

    executeScript(script, Some((genesisPrivateKey, genesisPublicKey)))

    def createTxTemplate(result: BuildSimpleExecuteScriptTxResult): TransactionTemplate = {
      val signature = SecP256K1.sign(result.txId.bytes, privateKey)
      serverUtils.createTxTemplate(SubmitTransaction(result.unsignedTx, signature)).rightValue
    }

    def submitTx(txTemplate: TransactionTemplate): Block = {
      blockFlow.getGrandPool().add(chainIndex, AVector(txTemplate), TimeStamp.now())
      mineFromMemPool(blockFlow, chainIndex)
    }

    val validator = blockFlow.templateValidator.nonCoinbaseValidation
    val tx0       = createTxTemplate(result0)
    validator.validateMempoolTxTemplate(tx0, blockFlow).leftValue isE TxScriptExeFailed(vm.OutOfGas)
    val block0 = submitTx(tx0)
    block0.nonCoinbase.head.scriptExecutionOk is false
    blockFlow.getGrandPool().clear()

    val tx1 = createTxTemplate(result1)
    validator.validateMempoolTxTemplate(tx1, blockFlow) isE ()
    val block1 = submitTx(tx1)
    block1.nonCoinbase.head.scriptExecutionOk is true
    addAndCheck(blockFlow, block1)
  }

  trait TxOutputRefIndexFixture extends Fixture {
    def enableTxOutputRefIndex: Boolean = true

    override val configValues: Map[String, Any] = Map(
      ("alephium.node.indexes.tx-output-ref-index", s"$enableTxOutputRefIndex"),
      ("alephium.node.indexes.subcontract-index", "false")
    )

    val serverUtils               = new ServerUtils()
    val chainIndex                = ChainIndex.unsafe(0, 0)
    val (genesisPrivateKey, _, _) = genesisKeys(chainIndex.from.value)
    val (_, publicKey)            = chainIndex.from.generateKey
    val block                     = transfer(blockFlow, genesisPrivateKey, publicKey, ALPH.alph(10))
    addAndCheck(blockFlow, block)
    val txId = block.nonCoinbase.head.id

    val utxos = blockFlow.getUTXOs(LockupScript.p2pkh(publicKey), Int.MaxValue, true).rightValue
    utxos.length is 1
    val txOutputRef = utxos.head.ref.asInstanceOf[AssetOutputRef]
  }

  it should "find tx id from tx output ref" in new TxOutputRefIndexFixture {
    serverUtils.getTxIdFromOutputRef(blockFlow, txOutputRef) isE txId
  }

  it should "fail to find tx id from tx output ref when node.indexes.tx-output-ref-index is not enabled" in new TxOutputRefIndexFixture {
    override def enableTxOutputRefIndex: Boolean = false

    serverUtils
      .getTxIdFromOutputRef(blockFlow, txOutputRef)
      .leftValue
      .detail
      .contains(
        "Please set `alephium.node.indexes.tx-output-ref-index = true` to query transaction id from transaction output reference"
      ) is true
  }

  it should "find rich block when node.indexes.tx-output-ref-index is enabled" in new TxOutputRefIndexFixture {
    val transactions =
      block.transactions
        .mapE(tx => serverUtils.getRichTransaction(blockFlow, tx, block.hash))
        .rightValue
    serverUtils
      .getRichBlockAndEvents(blockFlow, block.hash)
      .rightValue is RichBlockAndEvents(
      RichBlockEntry.from(block, 1, transactions).rightValue,
      AVector.empty
    )
  }

  it should "fail to find rich block when node.indexes.tx-output-ref-index is not enabled" in new TxOutputRefIndexFixture {
    override def enableTxOutputRefIndex: Boolean = false

    serverUtils
      .getRichBlockAndEvents(blockFlow, block.hash)
      .leftValue
      .detail
      .contains(
        "Please set `alephium.node.indexes.tx-output-ref-index = true` to query transaction id from transaction output reference"
      ) is true
  }

  it should "fail to find rich transaction when node.indexes.tx-output-ref-index is not enabled" in new TxOutputRefIndexFixture {
    override def enableTxOutputRefIndex: Boolean = false

    serverUtils
      .getRichTransaction(blockFlow, txId, Some(chainIndex.from), Some(chainIndex.to))
      .leftValue
      .detail
      .contains(
        "Please set `alephium.node.indexes.tx-output-ref-index = true` to query transaction id from transaction output reference"
      ) is true
  }

  trait ConflictedTxsFixture extends Fixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.broker.broker-num", 1),
      ("alephium.node.indexes.tx-output-ref-index", "true")
    )

    var now = TimeStamp.now()
    def nextBlockTs: TimeStamp = {
      val newTs = now.plusMillisUnsafe(1)
      now = newTs
      newTs
    }

    val chainIndex0 = ChainIndex.unsafe(0, 1)
    val chainIndex1 = ChainIndex.unsafe(0, 2)

    val block0 = transfer(blockFlow, chainIndex0, nextBlockTs)
    val block1 = transfer(blockFlow, chainIndex1, nextBlockTs)
    addAndCheck(blockFlow, block1)

    val block2 =
      transfer(blockFlow, chainIndex1, nextBlockTs) // it uses the conflicted tx from block1
    addAndCheck(blockFlow, block2)

    addAndCheck(blockFlow, block0)
    addAndCheck(blockFlow, emptyBlock(blockFlow, ChainIndex.unsafe(0, 0), nextBlockTs))
    addAndCheck(
      blockFlow,
      emptyBlock(blockFlow, ChainIndex.unsafe(1, 1), nextBlockTs),
      emptyBlock(blockFlow, ChainIndex.unsafe(2, 2), nextBlockTs)
    )

    val serverUtils = new ServerUtils()
  }

  it should "return conflicted tx status" in new ConflictedTxsFixture {
    val tx0 = block0.transactions.head.id
    val tx1 = block0.transactions.last.id
    serverUtils.getTransactionStatus(blockFlow, tx0, block0.chainIndex).rightValue is a[Confirmed]
    serverUtils.getTransactionStatus(blockFlow, tx1, block0.chainIndex).rightValue is a[Confirmed]
    val tx2 = block1.transactions.head.id
    val tx3 = block1.transactions.last.id
    serverUtils.getTransactionStatus(blockFlow, tx2, block1.chainIndex).rightValue is Conflicted(
      block1.hash,
      txIndex = 0,
      chainConfirmations = 2,
      fromGroupConfirmations = 1,
      toGroupConfirmations = 1
    )
    serverUtils.getTransactionStatus(blockFlow, tx3, block1.chainIndex).rightValue is a[Confirmed]

    addAndCheck(blockFlow, emptyBlock(blockFlow, block1.chainIndex, nextBlockTs))
    addAndCheck(blockFlow, emptyBlock(blockFlow, ChainIndex.unsafe(0, 0), nextBlockTs))
    addAndCheck(blockFlow, emptyBlock(blockFlow, ChainIndex.unsafe(2, 2), nextBlockTs))
    serverUtils.getTransactionStatus(blockFlow, tx2, block1.chainIndex).rightValue is Conflicted(
      block1.hash,
      txIndex = 0,
      chainConfirmations = 3,
      fromGroupConfirmations = 2,
      toGroupConfirmations = 2
    )
  }

  it should "return correct status if the conflicted tx is only on the fork chain" in new ConflictedTxsFixture {
    val blockFlow1 = isolatedBlockFlow()
    addAndCheck(blockFlow1, block1, block2)

    val blocks = (0 until 3).flatMap { _ =>
      brokerConfig.chainIndexes.map { chainIndex =>
        val block = emptyBlock(blockFlow1, chainIndex, nextBlockTs)
        addAndCheck(blockFlow1, block)
        block
      }
    }

    blockFlow.isBlockInMainChainUnsafe(block0.hash) is true
    blockFlow.isBlockInMainChainUnsafe(block1.hash) is true
    blockFlow.isBlockInMainChainUnsafe(block2.hash) is true
    val tx = block1.nonCoinbase.head.id
    serverUtils.getTransactionStatus(blockFlow, tx, block1.chainIndex).rightValue is a[Conflicted]

    addAndCheck(blockFlow, blocks: _*)
    blockFlow.isBlockInMainChainUnsafe(block0.hash) is false
    blockFlow.isBlockInMainChainUnsafe(block1.hash) is true
    blockFlow.isBlockInMainChainUnsafe(block2.hash) is true
    serverUtils.getTransactionStatus(blockFlow, tx, block1.chainIndex).rightValue is a[Confirmed]
  }

  it should "return empty rich inputs for conflicted txs" in new ConflictedTxsFixture {
    private def checkTx(tx: Transaction) = {
      val result = serverUtils.getTransaction(blockFlow, tx.id, None, None).rightValue
      result.unsigned.inputs.isEmpty is false
      result.unsigned.fixedOutputs.isEmpty is false
      result is api.Transaction.fromProtocol(tx)
    }

    val tx0 = block0.nonCoinbase.head
    val tx1 = block1.nonCoinbase.head
    val tx2 = block2.nonCoinbase.head

    checkTx(tx0)
    checkTx(tx1)
    checkTx(tx2)

    val richTx0     = serverUtils.getRichTransaction(blockFlow, tx0.id, None, None).rightValue
    val richInputs0 = serverUtils.getRichAssetInputs(blockFlow, tx0, block0.hash).rightValue
    richTx0 is api.RichTransaction.from(tx0, richInputs0, AVector.empty)

    val richTx1 = serverUtils.getRichTransaction(blockFlow, tx1.id, None, None).rightValue
    richTx1 is api.RichTransaction.from(tx1, richInputs0, AVector.empty)

    val richTx2 = serverUtils.getRichTransaction(blockFlow, tx2.id, None, None).rightValue
    richTx2 is api.RichTransaction.from(tx2, AVector.empty, AVector.empty)
  }

  it should "get rich transaction that spends asset output" in new Fixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.node.indexes.tx-output-ref-index", "true")
    )

    val serverUtils               = new ServerUtils()
    val chainIndex                = ChainIndex.unsafe(0, 0)
    val (genesisPrivateKey, _, _) = genesisKeys(chainIndex.from.value)
    val (privateKey0, publicKey0) = chainIndex.from.generateKey
    val (_, publicKey1)           = chainIndex.from.generateKey
    val block0 = transfer(blockFlow, genesisPrivateKey, publicKey0, ALPH.alph(10))
    addAndCheck(blockFlow, block0)

    val outputToBeSpent = {
      val utxos = blockFlow.getUTXOs(LockupScript.p2pkh(publicKey0), Int.MaxValue, true).rightValue
      utxos.length is 1
      utxos.head.output
    }

    val block1 = transfer(blockFlow, privateKey0, publicKey1, ALPH.alph(1))
    addAndCheck(blockFlow, block1)

    val transaction = block1.nonCoinbase.head
    val richInput = {
      transaction.unsigned.inputs.length is 1
      val input   = transaction.unsigned.inputs.head
      val txIdRef = serverUtils.getTxIdFromOutputRef(blockFlow, input.outputRef).rightValue
      RichInput.from(input, outputToBeSpent.asInstanceOf[model.AssetOutput], txIdRef)
    }
    val richTransaction = RichTransaction.from(transaction, AVector(richInput), AVector.empty)

    serverUtils
      .getRichTransaction(blockFlow, transaction, block1.hash)
      .rightValue is richTransaction

    serverUtils
      .getRichTransaction(blockFlow, transaction.id, Some(chainIndex.from), Some(chainIndex.to))
      .rightValue is richTransaction

    serverUtils
      .getRichTransaction(blockFlow, transaction.id, None, None)
      .rightValue is richTransaction

    serverUtils
      .getRichTransaction(blockFlow, transaction.id, GroupIndex.from(2), GroupIndex.from(3))
      .leftValue
      .detail is s"Transaction ${transaction.id.toHexString} not found"
  }

  it should "get rich transaction that spends asset & contract output" in new ContractFixture {
    val (genesisPrivateKey, _, _) = genesisKeys(chainIndex.from.value)
    val (privateKey0, publicKey0) = chainIndex.from.generateKey
    val block0 = transfer(blockFlow, genesisPrivateKey, publicKey0, ALPH.alph(10))
    addAndCheck(blockFlow, block0)

    val fooContract: String =
      s"""
         |Contract Foo() {
         |  event Foo()
         |
         |  @using(assetsInContract = true)
         |  pub fn foo() -> () {
         |    emit Foo()
         |    transferTokenFromSelf!(callerAddress!(), ALPH, 1 alph)
         |  }
         |}
         |""".stripMargin

    val contractAddress = deployContract(
      fooContract,
      initialAttoAlphAmount = Amount(ALPH.alph(5)),
      keyPair = (privateKey0, publicKey0),
      None
    )

    val contractOutputToBeSpent = {
      val utxos = blockFlow.getUTXOs(contractAddress.lockupScript, Int.MaxValue, true).rightValue
      utxos.length is 1
      utxos.head.output
    }

    val assetOutputToBeSpent = {
      val utxos = blockFlow.getUTXOs(LockupScript.p2pkh(publicKey0), Int.MaxValue, true).rightValue
      utxos.length is 1
      utxos.head.output
    }

    def script =
      s"""
         |TxScript Main {
         |  Foo(#${contractAddress.toBase58}).foo()
         |}
         |
         |$fooContract
         |""".stripMargin

    val scriptBlock       = executeScript(script, Some((privateKey0, publicKey0)))
    val scriptTransaction = scriptBlock.nonCoinbase.head
    val richAssetInput = {
      scriptTransaction.unsigned.inputs.length is 1
      val input   = scriptTransaction.unsigned.inputs.head
      val txIdRef = serverUtils.getTxIdFromOutputRef(blockFlow, input.outputRef).rightValue
      RichInput.from(input, assetOutputToBeSpent.asInstanceOf[model.AssetOutput], txIdRef)
    }
    val richContractInput = {
      scriptTransaction.contractInputs.length is 1
      val input   = scriptTransaction.contractInputs.head
      val txIdRef = serverUtils.getTxIdFromOutputRef(blockFlow, input).rightValue
      RichInput.from(input, contractOutputToBeSpent.asInstanceOf[model.ContractOutput], txIdRef)
    }
    val richTransaction =
      RichTransaction.from(scriptTransaction, AVector(richAssetInput), AVector(richContractInput))

    serverUtils
      .getRichTransaction(blockFlow, scriptTransaction, scriptBlock.hash)
      .rightValue is richTransaction
    serverUtils
      .getRichTransaction(
        blockFlow,
        scriptTransaction.id,
        Some(chainIndex.from),
        Some(chainIndex.to)
      )
      .rightValue is richTransaction

    val height = if (hardFork.isDanubeEnabled()) 4 else 3
    val richBlockAndEvents = {
      val richTxs = scriptBlock.transactions
        .mapE(tx => serverUtils.getRichTransaction(blockFlow, tx, scriptBlock.hash))
        .rightValue
      RichBlockAndEvents(
        RichBlockEntry.from(scriptBlock, height, richTxs).rightValue,
        AVector(ContractEventByBlockHash(scriptTransaction.id, contractAddress, 0, AVector.empty))
      )
    }
    serverUtils.getRichBlockAndEvents(blockFlow, scriptBlock.hash).rightValue is richBlockAndEvents
  }

  trait SubContractIndexesFixture extends Fixture {
    def subcontractIndexEnabled: Boolean = true

    override val configValues: Map[String, Any] = Map(
      ("alephium.node.indexes.tx-output-ref-index", "false"),
      ("alephium.node.indexes.subcontract-index", s"$subcontractIndexEnabled")
    )

    val serverUtils           = new ServerUtils()
    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (_, genesisPubKey, _) = genesisKeys(0)
    val genesisAddress        = Address.p2pkh(genesisPubKey)
    val subContractRaw: String =
      s"""
         |Contract SubContract() {
         |  pub fn call() -> () {}
         |}
         |""".stripMargin

    val subContractTemplateId = createContract(subContractRaw)._1.toHexString

    val parentContractRaw: String =
      s"""
         |Contract ParentContract() {
         |  @using(preapprovedAssets = true)
         |  pub fn createSubContract(index: U256) -> () {
         |    copyCreateSubContract!{callerAddress!() -> ALPH: 1 alph}(toByteVec!(index), #$subContractTemplateId, #00, #00)
         |  }
         |}
         |""".stripMargin

    val subContractIndex   = 0
    val parentContractId   = createContract(parentContractRaw)._1
    val parentContractAddr = Address.contract(parentContractId)
    val subContractId =
      parentContractId.subContractId(ByteString.fromInts(subContractIndex), chainIndex.from)
    val subContractAddr = Address.contract(subContractId)

    callTxScript(
      s"""
         |TxScript Main {
         |  ParentContract(#${parentContractId.toHexString}).createSubContract{@$genesisAddress -> ALPH: 1 alph}($subContractIndex)
         |}
         |$parentContractRaw
         |""".stripMargin
    )
  }

  it should "return parent contract, subcontracts and subcontract count" in new SubContractIndexesFixture {
    serverUtils.getParentContract(blockFlow, parentContractAddr) isE ContractParent(None)
    serverUtils.getParentContract(blockFlow, subContractAddr) isE ContractParent(
      Some(parentContractAddr)
    )
    serverUtils.getSubContractsCurrentCount(blockFlow, parentContractAddr) isE 1
    serverUtils.getSubContracts(blockFlow, 0, 1, parentContractAddr) isE SubContracts(
      AVector(subContractAddr),
      1
    )
    serverUtils.getSubContracts(blockFlow, 1, 100, parentContractAddr) isE SubContracts(
      AVector.empty,
      1
    )
    serverUtils.getSubContracts(blockFlow, 2, 10, parentContractAddr).leftValue.detail is
      s"Current count for sub-contracts of ${parentContractAddr} is '1', sub-contracts start from '2' with limit '10' not found"

    serverUtils.getSubContracts(blockFlow, 1, 10, subContractAddr).leftValue.detail is
      s"Sub-contracts of ${subContractAddr} not found"
  }

  it should "return contract events" in new ContractFixture {
    val contractCode: String =
      s"""
         |Contract Foo() {
         |  event TestEvent(a: U256)
         |
         |  pub fn testEvent() -> () {
         |    emit TestEvent(5)
         |    return
         |  }
         |}
         |""".stripMargin

    val contractId      = createContract(contractCode)._1
    val contractAddress = Address.contract(contractId)

    val scriptCode: String =
      s"""
         |TxScript Main {
         |  let foo = Foo(#${contractId.toHexString})
         |  foo.testEvent()
         |}
         |$contractCode
         |""".stripMargin

    val block = executeScript(scriptCode)
    val txId  = block.nonCoinbase.head.id

    serverUtils.getEventsForContractCurrentCount(blockFlow, contractAddress) isE 1
    serverUtils.getEventsByContractAddress(blockFlow, 0, 1, contractAddress) isE ContractEvents(
      AVector(ContractEvent(block.hash, txId, 0, AVector(ValU256(U256.unsafe(5))))),
      1
    )
    serverUtils.getEventsByContractAddress(blockFlow, 0, 10, contractAddress) isE ContractEvents(
      AVector(ContractEvent(block.hash, txId, 0, AVector(ValU256(U256.unsafe(5))))),
      1
    )
    serverUtils.getEventsByContractAddress(blockFlow, 2, 10, contractAddress).leftValue.detail is
      s"Current count for events of ${contractAddress} is '1', events start from '2' with limit '10' not found"

    val randomAddress = Address.contract(ContractId.random)
    serverUtils.getEventsByContractAddress(blockFlow, 2, 10, randomAddress).leftValue.detail is
      s"Contract events of ${randomAddress} not found"
  }

  it should "return error when node.indexes.subcontract-index is not enabled" in new SubContractIndexesFixture {
    override def subcontractIndexEnabled: Boolean = false

    def verifyError[T](result: Try[T]) = {
      result.leftValue.detail.contains(
        "Please set `alephium.node.indexes.subcontract-index = true` to query parent contract or subcontracts"
      ) is true
    }

    verifyError(serverUtils.getParentContract(blockFlow, subContractAddr))
    verifyError(serverUtils.getSubContractsCurrentCount(blockFlow, parentContractAddr))
    verifyError(serverUtils.getSubContracts(blockFlow, 0, 1, parentContractAddr))
  }

  trait VerifyTxOutputFixture extends ContractFixture {
    val (genesisPrivateKey, genesisPublicKey, _) = genesisKeys(chainIndex.from.value)
    val (_, testPublicKey)                       = chainIndex.from.generateKey
    val testLockupScript                         = LockupScript.p2pkh(testPublicKey)
    val genesisLockupScript                      = lockupScript

    val tokenCode =
      s"""
         |Contract Token() {
         |  pub fn supply() -> () {}
         |}
         |""".stripMargin
    val tokenContract = Compiler.compileContract(tokenCode).rightValue
    val tokenIssuance = TokenIssuance.Info(vm.Val.U256(100), Some(genesisLockupScript))
    val contractCreationScript = contractCreation(
      tokenContract,
      AVector.empty,
      AVector.empty,
      genesisLockupScript,
      minimalAlphInContract,
      Some(tokenIssuance)
    )
    val tokenIssuanceBlock = payableCall(blockFlow, chainIndex, contractCreationScript)
    addAndCheck(blockFlow, tokenIssuanceBlock)
    val tokenId =
      TokenId.from(ContractId.from(tokenIssuanceBlock.transactions.head.id, 0, chainIndex.from))
  }

  it should "consider dustUtxoAmount for outputs when building execute script tx" in new VerifyTxOutputFixture {
    // Transfer tokens
    (1 to 8).foreach { _ =>
      val block = transfer(
        blockFlow,
        genesisPrivateKey,
        testLockupScript,
        tokens = AVector((tokenId, 10)),
        dustUtxoAmount
      )
      addAndCheck(blockFlow, block)
    }

    // Transfer ALPH
    (1 to 4).foreach { _ =>
      val block = transfer(blockFlow, genesisPrivateKey, testPublicKey, dustUtxoAmount * 2)
      addAndCheck(blockFlow, block)
    }

    def buildExecuteScriptTx(approvedAlphAmount: U256, iterations: Int) = {
      val script = s"""
                      |TxScript Main {
                      |  let mut sum = 0
                      |  for(let mut i = 0; i < ${iterations}; i = i + 1) {
                      |    sum = sum + 1
                      |  }
                      |}
                      |""".stripMargin

      val scriptBytecode = serialize(Compiler.compileTxScript(script).rightValue)

      serverUtils
        .buildExecuteScriptTx(
          blockFlow,
          BuildExecuteScriptTx(
            fromPublicKey = testPublicKey.bytes,
            bytecode = scriptBytecode,
            attoAlphAmount = Some(Amount(approvedAlphAmount)),
            tokens = Some(AVector(Token(tokenId, U256.unsafe(78))))
          ),
          ExtraUtxosInfo.empty
        )
    }

    buildExecuteScriptTx(dustUtxoAmount * 2, 10).rightValue
    buildExecuteScriptTx(dustUtxoAmount * 2, 1000).rightValue
    buildExecuteScriptTx(dustUtxoAmount, 10).rightValue
    buildExecuteScriptTx(dustUtxoAmount, 1000).rightValue
    buildExecuteScriptTx(dustUtxoAmount.subUnsafe(1), 10).rightValue
    buildExecuteScriptTx(dustUtxoAmount.subUnsafe(1), 1000).rightValue
    buildExecuteScriptTx(U256.Zero, 10).rightValue
    buildExecuteScriptTx(U256.Zero, 1000).rightValue
  }

  it should "consider dustUtxoAmount for outputs when building transfer tx" in new VerifyTxOutputFixture {
    def verifyBuildTransferTx(alphAmount: Option[U256]) = {
      val unsignedTx = serverUtils
        .prepareUnsignedTransaction(
          blockFlow,
          LockupScript.p2pkh(genesisPublicKey),
          UnlockScript.p2pkh(genesisPublicKey),
          outputRefsOpt = None,
          destinations = AVector(
            Destination(
              address = Address.Asset(testLockupScript),
              attoAlphAmount = alphAmount.map(Amount(_)),
              tokens = Some(AVector(Token(tokenId, U256.unsafe(10))))
            )
          ),
          gasOpt = None,
          gasPrice = nonCoinbaseMinGasPrice,
          targetBlockHashOpt = None,
          ExtraUtxosInfo.empty
        )
        .rightValue

      AVector(
        model.AssetOutput(
          dustUtxoAmount,
          testLockupScript,
          TimeStamp.zero,
          AVector(tokenId -> 10),
          ByteString.empty
        ),
        model.AssetOutput(
          dustUtxoAmount,
          LockupScript.p2pkh(genesisPublicKey),
          TimeStamp.zero,
          AVector(tokenId -> 90),
          ByteString.empty
        )
      ).forall(unsignedTx.fixedOutputs.contains) is true
    }

    verifyBuildTransferTx(Some(U256.Zero))
    verifyBuildTransferTx(Some(dustUtxoAmount.subUnsafe(1)))
    verifyBuildTransferTx(Some(dustUtxoAmount))
    verifyBuildTransferTx(Some(dustUtxoAmount.addUnsafe(1)))
    verifyBuildTransferTx(Some(dustUtxoAmount.mulUnsafe(10)))
    verifyBuildTransferTx(None)
  }

  it should "not allow destination with no tokens and no attoAlphAmount" in new VerifyTxOutputFixture {
    serverUtils
      .prepareUnsignedTransaction(
        blockFlow,
        LockupScript.p2pkh(genesisPublicKey),
        UnlockScript.p2pkh(genesisPublicKey),
        outputRefsOpt = None,
        destinations = AVector(
          Destination(address = Address.Asset(testLockupScript))
        ),
        gasOpt = None,
        gasPrice = nonCoinbaseMinGasPrice,
        targetBlockHashOpt = None,
        ExtraUtxosInfo.empty
      )
      .leftValue
      .detail is "Tx output value is too small, avoid spreading dust"
  }

  it should "return an error if there are too many utxos" in new Fixture {
    def createServerUtils(utxosLimit: Int): ServerUtils = {
      new ServerUtils()(
        brokerConfig,
        consensusConfigs,
        networkConfig,
        apiConfig.copy(defaultUtxosLimit = utxosLimit),
        logConfig,
        ec
      )
    }

    val chainIndex                = ChainIndex.unsafe(0, 0)
    val (genesisPrivateKey, _, _) = genesisKeys(chainIndex.from.value)
    val (_, publicKey)            = chainIndex.from.generateKey
    (0 until 10).foreach { _ =>
      val block = transfer(blockFlow, genesisPrivateKey, publicKey, ALPH.alph(1))
      addAndCheck(blockFlow, block)
    }
    val lockupScript = LockupScript.p2pkh(publicKey)
    val assetAddress = Address.from(lockupScript)
    val apiAddress   = api.Address.from(lockupScript)
    blockFlow.getUTXOs(lockupScript, Int.MaxValue, true).rightValue.length is 10

    val serverUtils0 = createServerUtils(9)
    serverUtils0.getBalance(blockFlow, apiAddress, true).leftValue.detail is
      "Your address has too many UTXOs and exceeds the API limit. Please consolidate your UTXOs, or run your own full node with a higher API limit."
    serverUtils0.getUTXOsIncludePool(blockFlow, Address.from(lockupScript)).leftValue.detail is
      "Your address has too many UTXOs and exceeds the API limit. Please consolidate your UTXOs, or run your own full node with a higher API limit."

    val serverUtils1 = createServerUtils(10)
    serverUtils1
      .getBalance(blockFlow, apiAddress, true)
      .rightValue
      .balance
      .value is ALPH.alph(10)
    serverUtils1
      .getUTXOsIncludePool(blockFlow, assetAddress)
      .rightValue
      .utxos
      .length is 10

    val serverUtils2 = createServerUtils(11)
    serverUtils2
      .getBalance(blockFlow, apiAddress, true)
      .rightValue
      .balance
      .value is ALPH.alph(10)
    serverUtils2
      .getUTXOsIncludePool(blockFlow, assetAddress)
      .rightValue
      .utxos
      .length is 10
  }

  it should "return an error if the error code is invalid" in new Fixture {
    val serverUtils = new ServerUtils()
    val publicKey   = genesisKeys(0)._2

    def executeScript(errorCode: String) = {
      val code =
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main {
           |  assert!(true, $errorCode)
           |}
           |""".stripMargin
      val bytecode = serialize(Compiler.compileTxScript(code).rightValue)
      serverUtils.buildExecuteScriptTx(
        blockFlow,
        BuildExecuteScriptTx(fromPublicKey = publicKey.bytes, bytecode = bytecode)
      )
    }

    executeScript(s"${Int.MaxValue - 1}").isRight is true
    executeScript(s"${Int.MaxValue}").isRight is true
    executeScript(s"${Int.MaxValue.toLong + 1L}").leftValue.detail is
      "Execution error when emulating tx script or contract: Invalid error code 2147483648: The error code cannot exceed the maximum value for int32 (2147483647)"
  }

  it should "get contract code" in new ContractFixture {
    val code =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin
    val statefulContract = Compiler.compileContract(code).rightValue
    val codeHash         = statefulContract.hash
    serverUtils.getContractCode(blockFlow, codeHash).leftValue.detail is
      s"Contract code hash: ${codeHash.toHexString} not found"
    createContract(code, AVector.empty, AVector.empty)._2
    serverUtils.getContractCode(blockFlow, codeHash) is Right(statefulContract)
  }

  it should "run unit tests" in new Fixture {
    val serverUtils = new ServerUtils

    {
      val now = TimeStamp.now()

      def code(blockTimeStamp: TimeStamp) =
        s"""
           |Contract Foo(@unused v: U256) {
           |  pub fn foo() -> U256 {
           |    return blockTimeStamp!()
           |  }
           |  test "foo"
           |  with blockTimeStamp = ${now.millis}
           |  before Self(0) {
           |    testCheck!(foo() == ${blockTimeStamp.millis})
           |  }
           |}
           |""".stripMargin

      serverUtils.compileProject(blockFlow, api.Compile.Project(code(now))).isRight is true
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code(TimeStamp.zero)))
        .leftValue
        .detail is
        s"""|-- error (9:5): Testing error
            |9 |    testCheck!(foo() == 0)
            |  |    ^^^^^^^^^^^^^^^^^^^^^^
            |  |    Test failed: Foo:foo, detail: VM execution error: Assertion Failed: left(U256(${now.millis})) is not equal to right(U256(0))
            |""".stripMargin
    }

    {
      def code(result: Int) =
        s"""
           |Contract Foo(bar0: Bar, bar1: Bar) {
           |  pub fn add() -> U256 {
           |    return bar0.value() + bar1.value()
           |  }
           |  test "add"
           |  before Bar(10)@addr0, Bar(20)@addr1, Self(addr0, addr1)
           |  {
           |    testCheck!(add() == $result)
           |  }
           |}
           |Contract Bar(v: U256) {
           |  pub fn value() -> U256 {
           |    return v
           |  }
           |}
           |""".stripMargin

      serverUtils.compileProject(blockFlow, api.Compile.Project(code(30))).isRight is true
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code(20)))
        .leftValue
        .detail is
        s"""|-- error (9:5): Testing error
            |9 |    testCheck!(add() == 20)
            |  |    ^^^^^^^^^^^^^^^^^^^^^^^
            |  |    Test failed: Foo:add, detail: VM execution error: Assertion Failed: left(U256(30)) is not equal to right(U256(20))
            |""".stripMargin
    }

    {
      val fromAddress = generateAddress(ChainIndex.unsafe(0))

      def code(result: String) =
        s"""
           |Contract Foo(mut balance: U256) {
           |  @using(preapprovedAssets = true, assetsInContract = true, checkExternalCaller = false)
           |  pub fn transfer(from: Address) -> U256 {
           |    let amount = 1 alph
           |    transferTokenToSelf!(from, ALPH, 1 alph)
           |    balance = balance + amount
           |    return balance
           |  }
           |
           |  test "transfer"
           |  before Self(0)
           |  after Self{ALPH: 1.1 alph}(1 alph)
           |  approve{@$fromAddress -> ALPH: 1 alph} {
           |    emit Debug(`balance: $${balance}`)
           |    testCheck!(transfer{callerAddress!() -> ALPH: 1 alph}(callerAddress!()) == $result)
           |  }
           |}
           |""".stripMargin

      serverUtils.compileProject(blockFlow, api.Compile.Project(code("1 alph"))).isRight is true
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code("2 alph")))
        .leftValue
        .detail is
        s"""|-- error (16:5): Testing error
            |16 |    testCheck!(transfer{callerAddress!() -> ALPH: 1 alph}(callerAddress!()) == 2 alph)
            |   |    ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
            |   |    Test failed: Foo:transfer, detail: VM execution error: Assertion Failed: left(U256(1000000000000000000)) is not equal to right(U256(2000000000000000000))
            |   |-------------------------------------------------------------------------------------------------------------------------------------------------------------
            |   |Debug messages:
            |   |> Contract @ Foo - balance: 0
            |""".stripMargin
    }

    {
      def code(a: Int, b: Int) =
        s"""
           |Contract Foo(a: U256, b: U256) extends Base(a, b) {
           |  pub fn foo() -> U256 { return base() }
           |  fn base() -> U256 { return a + b }
           |  fn isSum() -> Bool { return true }
           |}
           |Contract Bar(a: U256, b: U256) extends Base(a, b) {
           |  pub fn bar() -> U256 { return base() }
           |  fn base() -> U256 { return a - b }
           |  fn isSum() -> Bool { return false }
           |}
           |Abstract Contract Base(a: U256, b: U256) {
           |  fn isSum() -> Bool
           |  fn base() -> U256
           |  test "base" before Self(20, 10) {
           |    let result = if (isSum()) $a else $b
           |    testCheck!(base() == result)
           |  }
           |}
           |""".stripMargin

      serverUtils.compileProject(blockFlow, api.Compile.Project(code(30, 10))).isRight is true
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code(20, 10)))
        .leftValue
        .detail is
        s"""|-- error (17:5): Testing error
            |17 |    testCheck!(base() == result)
            |   |    ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
            |   |    Test failed: Base:base, detail: VM execution error: Assertion Failed: left(U256(30)) is not equal to right(U256(20))
            |""".stripMargin
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code(30, 20)))
        .leftValue
        .detail is
        s"""|-- error (17:5): Testing error
            |17 |    testCheck!(base() == result)
            |   |    ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
            |   |    Test failed: Base:base, detail: VM execution error: Assertion Failed: left(U256(10)) is not equal to right(U256(20))
            |""".stripMargin
    }

    {
      def code(value: String) =
        s"""
           |struct Bar {
           |  mut a: U256,
           |  mut b: [U256; 2],
           |  c: [U256; 2]
           |}
           |Contract Foo(mut bar: Bar) {
           |  pub fn foo() -> () {
           |    bar.a = bar.a + 1
           |    bar.b[0] = bar.b[0] + 1
           |  }
           |  test "foo"
           |  before Self(Bar { a: 0, b: [0; 2], c: [0; 2] })
           |  after Self($value) {
           |    foo()
           |  }
           |}
           |""".stripMargin

      val correct = "Bar { a: 1, b: [1, 0], c: [0; 2] }"
      serverUtils.compileProject(blockFlow, api.Compile.Project(code(correct))).isRight is true
      val invalid0 = "Bar { a: 0, b: [1, 0], c: [0; 2] }"
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code(invalid0)))
        .leftValue
        .detail is
        s"""|-- error (14:9): Testing error
            |14 |  after Self(Bar { a: 0, b: [1, 0], c: [0; 2] }) {
            |   |        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
            |   |        Test failed: Foo:foo, detail: invalid field bar.a, expected U256(0), have: U256(1)
            |""".stripMargin

      val invalid1 = "Bar { a: 1, b: [1, 1], c: [0; 2] }"
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code(invalid1)))
        .leftValue
        .detail is
        s"""|-- error (14:9): Testing error
            |14 |  after Self(Bar { a: 1, b: [1, 1], c: [0; 2] }) {
            |   |        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
            |   |        Test failed: Foo:foo, detail: invalid field bar.b[1], expected U256(1), have: U256(0)
            |""".stripMargin
    }

    {
      val code =
        s"""
           |Contract Foo(bar: Bar) {
           |  @using(checkExternalCaller = false, assetsInContract = enforced)
           |  pub fn foo() -> () {
           |    bar.bar()
           |  }
           |
           |  test "foo"
           |  before Bar{ALPH: 10 alph}(0)@barId, Self(barId)
           |  after Bar{ALPH: 9 alph}(1)@barId, Self{ALPH: 1.1 alph}(barId)
           |  {
           |    foo()
           |  }
           |}
           |Contract Bar(mut value: U256) {
           |  @using(checkExternalCaller = false, assetsInContract = true)
           |  pub fn bar() -> () {
           |    value += 1
           |    transferTokenFromSelf!(callerAddress!(), ALPH, 1 alph)
           |  }
           |}
           |""".stripMargin
      serverUtils.compileProject(blockFlow, api.Compile.Project(code)).isRight is true
    }

    {
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> U256 {
           |    return 0
           |  }
           |  test "foo" {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin
      val options0 = CompilerOptions(skipTests = Some(true))
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code, Some(options0)))
        .isRight is true
      val options1 = CompilerOptions(skipTests = Some(false))
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code, Some(options1)))
        .isRight is false
    }

    {
      def code(str: String) =
        s"""
           |Contract Foo() {
           |  pub fn foo0(v: U256) -> () {
           |    let _ = v / 0
           |  }
           |  fn foo1() -> U256 {
           |    return 0
           |  }
           |  test "foo" {
           |    testFail!($str)
           |  }
           |}
           |""".stripMargin

      Seq("foo0(1)", "1 / foo1()", "foo1() / foo1()").foreach { expr =>
        serverUtils.compileProject(blockFlow, api.Compile.Project(code(expr))).isRight is true
      }

      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code("foo1() / 1")))
        .leftValue
        .detail is
        s"""|-- error (10:5): Testing error
            |10 |    testFail!(foo1() / 1)
            |   |    ^^^^^^^^^^^^^^^^^^^^^
            |   |    Test failed: Foo:foo, detail: VM execution error: Assertion Failed: the test code did not throw an exception
            |""".stripMargin
    }

    {
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo0() -> U256 {
           |    return 0
           |  }
           |  pub fn foo1() -> I256 {
           |    return i256Max!()
           |  }
           |  test "foo" {
           |    testCheck!(randomU256!() >= foo0())
           |    testCheck!(randomI256!() <= foo1())
           |  }
           |}
           |""".stripMargin
      serverUtils.compileProject(blockFlow, api.Compile.Project(code)).isRight is true
    }

    {
      def code(testCall: String) =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> U256 {
           |    return 0
           |  }
           |  test "foo" {
           |    $testCall
           |  }
           |}
           |""".stripMargin

      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code("testEqual!(foo(), 0)")))
        .isRight is true
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code("testEqual!(foo(), 1)")))
        .leftValue
        .detail is
        s"""|-- error (7:5): Testing error
            |7 |    testEqual!(foo(), 1)
            |  |    ^^^^^^^^^^^^^^^^^^^^
            |  |    Test failed: Foo:foo, detail: VM execution error: Assertion Failed: left(U256(0)) is not equal to right(U256(1))
            |""".stripMargin
    }

    {
      def code(testCall: String) =
        s"""
           |Contract Foo() {
           |  pub fn foo(a: U256, b: U256) -> U256 {
           |    if (a > b) {
           |      assert!(a >= 5, 0)
           |    } else {
           |      assert!(b >= 5, 1)
           |    }
           |    return mulModN!(a, a, b)
           |  }
           |  test "foo" {
           |    $testCall
           |  }
           |}
           |""".stripMargin

      Seq(
        "testError!(foo(4, 3), 0)",
        "testError!(foo(3, 4), 1)",
        "testFail!(foo(9, 0))",
        "testError!(foo(4, 3), 0)\ntestError!(foo(3, 4), 1)\ntestFail!(foo(9, 0))"
      ).foreach { testCall =>
        serverUtils
          .compileProject(blockFlow, api.Compile.Project(code(testCall)))
          .isRight is true
      }
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code("testError!(foo(4, 3), 1)")))
        .leftValue
        .detail is
        s"""|-- error (12:5): Testing error
            |12 |    testError!(foo(4, 3), 1)
            |   |    ^^^^^^^^^^^^^^^^^^^^^^^^
            |   |    Test failed: Foo:foo, detail: VM execution error: Unexpected error code in test. Expected: 1, but got: 0.
            |""".stripMargin
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code("testError!(foo(3, 4), 0)")))
        .leftValue
        .detail is
        s"""|-- error (12:5): Testing error
            |12 |    testError!(foo(3, 4), 0)
            |   |    ^^^^^^^^^^^^^^^^^^^^^^^^
            |   |    Test failed: Foo:foo, detail: VM execution error: Unexpected error code in test. Expected: 0, but got: 1.
            |""".stripMargin
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code("testError!(foo(9, 0), 1)")))
        .leftValue
        .detail is
        s"""|-- error (12:5): Testing error
            |12 |    testError!(foo(9, 0), 1)
            |   |    ^^^^^^^^^^^^^^^^^^^^^^^^
            |   |    Test failed: Foo:foo, detail: VM execution error: Unexpected execution failure in test. Expected error code: 1, but got failure: ArithmeticError((U256(9) * U256(9)) % U256(0)).
            |""".stripMargin
    }

    {
      val code =
        s"""
           |Contract TokenVault() {
           |    @using(preapprovedAssets = true, assetsInContract = true, checkExternalCaller = false)
           |    pub fn deposit() -> () {
           |        transferTokenToSelf!(externalCallerAddress!(), ALPH, 2 alph)
           |    }
           |
           |    test "should increase contract balance on deposit"
           |    before Self{ALPH: 1 alph}()
           |    after Self{ALPH: 3 alph}()
           |    approve{address -> ALPH: 2 alph}
           |    {
           |        deposit{callerAddress!() -> ALPH: 2 alph}()
           |    }
           |}
           |""".stripMargin
      serverUtils.compileProject(blockFlow, api.Compile.Project(code)).isRight is true
    }

    {
      val code =
        s"""
           |Contract Bank(mut totalDeposits: U256) {
           |    @using(preapprovedAssets = true, assetsInContract = true, checkExternalCaller = false)
           |    pub fn deposit(depositor: Address) -> () {
           |        totalDeposits = totalDeposits + 1 alph
           |        transferTokenToSelf!(depositor, ALPH, 1 alph)
           |    }
           |}
           |Contract Customer() {
           |    @using(preapprovedAssets = true, checkExternalCaller = false)
           |    pub fn makeDeposit(bank: Bank) -> () {
           |        let depositor = externalCallerAddress!()
           |        bank.deposit{depositor -> ALPH: 1 alph}(depositor)
           |    }
           |    test "customer should be able to make deposit to bank"
           |    before Bank{ALPH: 0 alph}(0)@bank, Self()
           |    after Bank{ALPH: 1 alph}(1 alph)@bank, Self()
           |    approve{address -> ALPH: 1 alph}
           |    {
           |        makeDeposit{callerAddress!() -> ALPH: 1 alph}(bank)
           |    }
           |}
           |""".stripMargin
      serverUtils.compileProject(blockFlow, api.Compile.Project(code)).isRight is true
    }

    {
      def code(times: Int) =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map
           |
           |  @using(checkExternalCaller = false, updateFields = true)
           |  pub fn foo(num: U256) -> () {
           |    for (let mut i = 0; i < num; i += 1) {
           |      map.insert!(i, i)
           |    }
           |  }
           |
           |  test "foo"
           |  approve{address -> ALPH: 1 alph} {
           |    foo($times)
           |  }
           |}
           |""".stripMargin
      serverUtils.compileProject(blockFlow, api.Compile.Project(code(1))).isRight is true
      serverUtils.compileProject(blockFlow, api.Compile.Project(code(2))).isRight is true
      serverUtils.compileProject(blockFlow, api.Compile.Project(code(3))).isRight is true
      serverUtils
        .compileProject(blockFlow, api.Compile.Project(code(4)))
        .leftValue
        .detail
        .contains("Insufficient funds to cover the minimum amount") is true
    }

    {
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> () {}
           |
           |  test "random address" {
           |    let address0 = randomContractAddress!()
           |    testCheck!(!isAssetAddress!(address0))
           |    testCheck!(isContractAddress!(address0))
           |
           |    let address1 = randomAssetAddress!()
           |    testCheck!(isAssetAddress!(address1))
           |    testCheck!(!isContractAddress!(address1))
           |  }
           |}
           |""".stripMargin
      serverUtils.compileProject(blockFlow, api.Compile.Project(code)).isRight is true
    }

    {
      val code =
        s"""
           |Contract Foo(parent: Address, mut amount: U256) {
           |  @using(updateFields = true)
           |  pub fn foo() -> () {
           |    checkCaller!(callerAddress!() == parent, 0)
           |    amount += 1
           |  }
           |  test "foo" with updateImmFields = true {
           |    testEqual!(amount, 0)
           |    parent = randomContractAddress!()
           |    testError!(foo(), 0)
           |    testEqual!(amount, 0)
           |
           |    parent = selfAddress!()
           |    foo()
           |    testEqual!(amount, 1)
           |  }
           |}
           |""".stripMargin
      serverUtils.compileProject(blockFlow, api.Compile.Project(code)).isRight is true
    }
  }

  it should "handle test error properly" in new Fixture {
    val code =
      s"""
         |Contract Foo() {
         |  pub fn f0() -> U256 {
         |    return f1()
         |  }
         |
         |  fn f1() -> U256 {
         |    assert!(false, 0)
         |    return 0
         |  }
         |
         |  test "f0" {
         |    testError!(f0(), 0)
         |    testFail!(f0())
         |  }
         |}
         |""".stripMargin
    val serverUtils = new ServerUtils
    serverUtils.compileProject(blockFlow, api.Compile.Project(code)).isRight is true
  }

  it should "support auto fund in test-contract endpoint" in {
    new Fixture {
      info("success if the dust amount is not specified")
      setHardForkSince(HardFork.Danube)
      val contract =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map
           |
           |  @using(checkExternalCaller = false, preapprovedAssets = true, assetsInContract = true)
           |  pub fn foo() -> () {
           |    transferTokenToSelf!(callerAddress!(), ALPH, 0.1 alph)
           |    map.insert!(0, 0)
           |  }
           |}
           |""".stripMargin

      val assetAddress = Address.p2pkh(genesisKeys.head._2)
      val code         = Compiler.compileContract(contract).toOption.get
      val testContractParams = TestContract(
        bytecode = code,
        inputAssets = Some(AVector(TestInputAsset(assetAddress, AssetState(ALPH.oneAlph))))
      ).toComplete().rightValue
      val serverUtils = new ServerUtils()
      serverUtils.runTestContract(blockFlow, testContractParams).isRight is true
    }

    new Fixture {
      info("fail if the dust amount is not enough")
      setHardForkSince(HardFork.Danube)
      val contract =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map
           |
           |  @using(checkExternalCaller = false)
           |  pub fn foo() -> () {
           |    map.insert!(0, 0)
           |  }
           |}
           |""".stripMargin

      val assetAddress = Address.p2pkh(genesisKeys.head._2)
      val code         = Compiler.compileContract(contract).toOption.get
      val testContractParams = TestContract(
        bytecode = code,
        inputAssets = Some(AVector(TestInputAsset(assetAddress, AssetState(ALPH.oneNanoAlph)))),
        dustAmount = Some(Amount(ALPH.oneNanoAlph))
      ).toComplete().rightValue
      val serverUtils = new ServerUtils()
      serverUtils
        .runTestContract(blockFlow, testContractParams)
        .leftValue
        .detail
        .contains(
          "Insufficient funds to cover the minimum amount for contract UTXO"
        ) is true
    }

    new Fixture {
      info("fail if exceeds max retry times")
      setHardForkSince(HardFork.Danube)
      val contract =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map
           |
           |  @using(checkExternalCaller = false, preapprovedAssets = true, assetsInContract = true)
           |  pub fn foo(num: U256) -> () {
           |    transferTokenToSelf!(callerAddress!(), ALPH, 0.1 alph)
           |    for (let mut i = 0; i < num; i += 1) {
           |      map.insert!(i, i)
           |    }
           |  }
           |}
           |""".stripMargin

      val assetAddress = Address.p2pkh(genesisKeys.head._2)
      val code         = Compiler.compileContract(contract).toOption.get
      val baseParams = TestContract(
        bytecode = code,
        inputAssets = Some(AVector(TestInputAsset(assetAddress, AssetState(ALPH.alph(2)))))
      ).toComplete().rightValue
      val serverUtils = new ServerUtils()
      (0 to 3).map(v => AVector[Val](ValU256(U256.unsafe(v)))).foreach { args =>
        val params = baseParams.copy(testArgs = args)
        serverUtils.runTestContract(blockFlow, params).isRight is true
      }
      (4 to 6).map(v => AVector[Val](ValU256(U256.unsafe(v)))).foreach { args =>
        val params = baseParams.copy(testArgs = args)
        val errorString =
          "Test failed due to insufficient funds to cover the dust amount. We tried increasing the dust amount to 0.3 ALPH, " +
            "but at least 0.1 ALPH is still required. Please figure out the exact dust amount needed and specify it using the dustAmount parameter."
        serverUtils
          .runTestContract(blockFlow, params)
          .leftValue
          .detail
          .contains(errorString) is true
      }
    }
  }

  trait AutoFundFixture extends ContractFixtureBase {
    setHardForkSince(HardFork.Danube)
    val contract =
      s"""
         |Contract AutoFundTest() {
         |  mapping[U256, U256] map
         |
         |  @using(checkExternalCaller = false, preapprovedAssets = true, assetsInContract = true)
         |  pub fn insert(num: U256) -> () {
         |    transferTokenToSelf!(callerAddress!(), ALPH, 1 alph)
         |    for (let mut i = 0; i < num; i += 1) {
         |      map.insert!(i, i)
         |    }
         |  }
         |}
         |""".stripMargin
    val contractId = createContract(contract, AVector.empty, AVector.empty)._2

    val (privKey, pubKey) = chainIndex.from.generateKey
    val fromAddress       = Address.p2pkh(pubKey)
    def script(num: Int) =
      s"""
         |TxScript Main {
         |  let autoFundTest = AutoFundTest(#${contractId.toHexString})
         |  autoFundTest.insert{@${fromAddress.toBase58} -> ALPH: 1 alph}($num)
         |}
         |$contract
         |""".stripMargin

    val (genesisPrivKey, genesisPubKey, _) = genesisKeys(chainIndex.from.value)
    val destinations = AVector.fill(30)(Destination(fromAddress, Some(Amount(ALPH.cent(10)))))
    val buildTxQuery = BuildTransferTx(genesisPubKey.bytes, None, destinations)
    val unsignedTx = serverUtils
      .buildTransferUnsignedTransaction(blockFlow, buildTxQuery, ExtraUtxosInfo.empty)
      .rightValue
    val signedTx = Transaction.from(unsignedTx, genesisPrivKey)
    addAndCheck(blockFlow, mineWithTxs(blockFlow, chainIndex, AVector(signedTx)))
    getAlphBalance(blockFlow, fromAddress.lockupScript) is ALPH.alph(3)

    def submitTx(unsignedTx: String): Unit = {
      val unsigned   = deserialize[UnsignedTransaction](Hex.unsafe(unsignedTx)).rightValue
      val txTemplate = TransactionTemplate.from(unsigned, privKey)
      blockFlow.getGrandPool().add(chainIndex, AVector(txTemplate), TimeStamp.now())
      val block = mineFromMemPool(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      block.nonCoinbase.head.id is unsigned.id
      ()
    }
  }

  it should "support auto fund in build-script-tx endpoint" in {
    new AutoFundFixture {
      info("success if the dust amount is not specified")
      val code = Compiler.compileTxScript(script(nextInt(0, 3))).rightValue
      val query = BuildExecuteScriptTx(
        pubKey.bytes,
        None,
        serialize(code),
        attoAlphAmount = Some(Amount(ALPH.oneAlph))
      )
      val result = serverUtils.buildExecuteScriptTx(blockFlow, query).rightValue.unsignedTx
      submitTx(result)
    }

    new AutoFundFixture {
      info("fail if the dust amount is not enough")
      val code = Compiler.compileTxScript(script(nextInt(7, 9))).rightValue
      val query0 = BuildExecuteScriptTx(
        pubKey.bytes,
        None,
        serialize(code),
        attoAlphAmount = Some(Amount(ALPH.oneAlph)),
        dustAmount = Some(Amount(ALPH.cent(10)))
      )
      serverUtils
        .buildExecuteScriptTx(blockFlow, query0)
        .leftValue
        .detail
        .contains(
          "Insufficient funds to cover the minimum amount"
        ) is true

      val query1 = query0.copy(dustAmount = Some(Amount(ALPH.cent(40))))
      val result = serverUtils.buildExecuteScriptTx(blockFlow, query1).rightValue.unsignedTx
      submitTx(result)
    }
  }

  it should "return events from canonical chain" in new Fixture {
    val chainIndex         = ChainIndex.unsafe(0, 0)
    val canonicalBlockHash = randomBlockHash(chainIndex)
    def contractAddress    = Address.contract(ContractId.random)

    def isCanonical(hash: BlockHash) = Right(hash == canonicalBlockHash)
    def events(num: Int, blockHash: BlockHash) = {
      AVector.tabulate(num)(ContractEventByTxId(blockHash, contractAddress, _, AVector.empty))
    }

    // If empty, return directly
    val emptyEvents = AVector.empty[ContractEventByTxId]
    ServerUtils.eventsFromCanonicalChain(emptyEvents, isCanonical) isE emptyEvents

    // If only one block hash, return directly
    val blockHash = randomBlockHash(chainIndex)
    forAll(Gen.choose(0, 10)) { num =>
      val allEvents = events(num, blockHash)
      ServerUtils.eventsFromCanonicalChain(allEvents, isCanonical) isE allEvents
    }

    // If not all block hashes are the same, check canonical block hash
    forAll(Gen.choose(1, 10), Gen.choose(0, 10)) { (num1, num2) =>
      val canonicalEvents    = events(num1, canonicalBlockHash)
      val nonCanonicalEvents = events(num2, randomBlockHash(chainIndex))
      val allEvents          = canonicalEvents ++ nonCanonicalEvents
      ServerUtils.eventsFromCanonicalChain(allEvents, isCanonical) isE canonicalEvents
    }
  }

  it should "create right tx when preapprovedAssets annotation is not on" in new ContractFixture {
    val (genesisPrivateKey, genesisPublicKey, _) = genesisKeys(chainIndex.from.value)
    val tokenFaucetCode =
      s"""
         |Contract TokenFaucet() {
         |  @using(assetsInContract = true)
         |  pub fn withDrawAlph(caller: Address) -> () {
         |    transferTokenFromSelf!(caller, ALPH, 10 alph)
         |  }
         |}
         |""".stripMargin

    val tokenFaucetId = createContract(
      tokenFaucetCode,
      initialAttoAlphAmount = ALPH.alph(1000),
      tokenIssuanceInfo = None
    )._1

    val withdrawCode =
      s"""
         |TxScript Main {
         |  pub fn main() -> () {
         |    let tokenFaucet = TokenFaucet(#${tokenFaucetId.toHexString})
         |    tokenFaucet.withDrawAlph(callerAddress!())
         |  }
         |}
         |$tokenFaucetCode
         |""".stripMargin

    val scriptBytecode = serialize(Compiler.compileTxScript(withdrawCode).rightValue)
    val result = serverUtils
      .buildExecuteScriptTx(
        blockFlow,
        BuildExecuteScriptTx(
          fromPublicKey = genesisPublicKey.bytes,
          bytecode = scriptBytecode,
          attoAlphAmount = Some(Amount(ALPH.alph(10)))
        )
      )
      .rightValue
      .asInstanceOf[BuildSimpleExecuteScriptTxResult]

    val signature = SecP256K1.sign(result.txId.bytes, genesisPrivateKey)
    val submitTx  = SubmitTransaction(result.unsignedTx, signature)
    val tx        = serverUtils.createTxTemplate(submitTx).rightValue
    val validator = blockFlow.templateValidator.nonCoinbaseValidation

    validator.validateMempoolTxTemplate(tx, blockFlow) isE ()
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
    Destination(address, Some(amount), None, None, Some(message))
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
      fromPrivateKey: PrivateKey,
      txSeenAt: TimeStamp = TimeStamp.now()
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

    blockFlow.getGrandPool().add(chainIndex, AVector(txTemplate), txSeenAt)
    serverUtils.getTransactionStatus(blockFlow, txTemplate.id, chainIndex) isE MemPooled()

    txTemplate
  }

  private def checkAddressBalance(address: Address, amount: U256, utxoNum: Int = 1)(implicit
      serverUtils: ServerUtils,
      blockFlow: BlockFlow
  ) = {
    serverUtils
      .getBalance(blockFlow, api.Address.from(address.lockupScript), true) isE Balance.from(
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
    checkAddressBalance(destination.address, destination.getAttoAlphAmount().value, utxoNum)
  }
}
