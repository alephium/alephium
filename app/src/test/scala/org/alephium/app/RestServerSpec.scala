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

import java.math.BigInteger
import java.net.{InetAddress, InetSocketAddress}

import scala.concurrent._
import scala.io.Source
import scala.util.{Random, Using}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActor, TestProbe}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, EitherValues}
import org.scalatest.compatible.Assertion
import sttp.client3.Response
import sttp.model.StatusCode

import org.alephium.api.{ApiError, ApiModel, OpenAPIWriters}
import org.alephium.api.UtilJson.avectorReadWriter
import org.alephium.api.model.{Address => _, _}
import org.alephium.app.ServerFixture.NodeDummy
import org.alephium.crypto.Blake2b
import org.alephium.flow.handler.{TestUtils, ViewHandler}
import org.alephium.flow.mining.Miner
import org.alephium.flow.network.{CliqueManager, InterCliqueManager}
import org.alephium.flow.network.bootstrap._
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.http.HttpFixture._
import org.alephium.http.HttpRouteFixture
import org.alephium.json.Json._
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.mining.HashRate
import org.alephium.protocol.model.{Balance => _, Transaction => _, _}
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.LockupScript
import org.alephium.ralph.Compiler
import org.alephium.serde.serialize
import org.alephium.util._
import org.alephium.util.Hex.HexStringSyntax
import org.alephium.wallet.WalletApp
import org.alephium.wallet.config.WalletConfig

//scalastyle:off file.size.limit
abstract class RestServerSpec(
    val nbOfNodes: Int,
    val apiKeys: AVector[ApiKey] = AVector.empty,
    val apiKeyEnabled: Boolean = false,
    val utxosLimit: Int = Int.MaxValue,
    val maxFormBufferedBytes: Int = 1024
) extends RestServerFixture {
  it should "call GET /blockflow/blocks" in {
    Get(blockflowFromTo(0, 1)) check { response =>
      response.code is StatusCode.Ok
      response.as[BlocksPerTimeStampRange] is dummyFetchResponse
    }

    Get(blockflowFromTo(10, 0)) check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        """Invalid value (expected value to pass validation: `fromTs` must be before `toTs`, but got: TimeInterval(TimeStamp(10ms),Some(TimeStamp(0ms))))"""
      )
    }
  }

  it should "call GET /blockflow/blocks-with-events" in {
    Get(blocksWithEvents(0, 1)) check { response =>
      response.code is StatusCode.Ok
      response.as[BlocksAndEventsPerTimeStampRange].blocksAndEvents.map(_.map(_.block)) is
        dummyFetchResponse.blocks
    }

    Get(blocksWithEvents(10, 0)) check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        """Invalid value (expected value to pass validation: `fromTs` must be before `toTs`, but got: TimeInterval(TimeStamp(10ms),Some(TimeStamp(0ms))))"""
      )
    }
  }

  it should "call GET /blockflow/blocks/<hash>" in {
    servers.foreach { server =>
      Get(s"/blockflow/blocks/${dummyBlockHeader.hash.toHexString}", server.port) check {
        response =>
          val chainIndex = ChainIndex.from(dummyBlockHeader.hash)
          if (chainIndex.relateTo(server.brokerConfig)) {
            response.code is StatusCode.Ok
            response.as[BlockEntry] is dummyBlockEntry
          } else {
            response.code is StatusCode.BadRequest
          }
      }
    }
  }

  it should "call GET /blockflow/blocks-with-events/<hash>" in {
    servers.foreach { server =>
      Get(
        s"/blockflow/blocks-with-events/${dummyBlockHeader.hash.toHexString}",
        server.port
      ) check { response =>
        val chainIndex = ChainIndex.from(dummyBlockHeader.hash)
        if (chainIndex.relateTo(server.brokerConfig)) {
          response.code is StatusCode.Ok
          response.as[BlockAndEvents].block is dummyBlockEntry
        } else {
          response.code is StatusCode.BadRequest
        }
      }
    }
  }

  it should "call GET /blockflow/headers/<hash>" in {
    servers.foreach { server =>
      Get(s"/blockflow/headers/${dummyBlockHeader.hash.toHexString}", server.port) check {
        response =>
          response.code is StatusCode.Ok
          response.as[BlockHeaderEntry] is BlockHeaderEntry.from(
            dummyBlockHeader,
            dummyBlockEntry.height
          )
      }
    }
  }

  it should "call GET /blockflow/raw-blocks/<hash>" in {
    servers.foreach { server =>
      val blockHash = dummyBlockHeader.hash
      Get(s"/blockflow/raw-blocks/${blockHash.toHexString}", server.port) check { response =>
        {
          val chainIndex = ChainIndex.from(blockHash)
          if (
            server.brokerConfig
              .contains(chainIndex.from) || server.brokerConfig.contains(chainIndex.to)
          ) {
            response.code is StatusCode.Ok
            response.as[RawBlock] is RawBlock(serialize(dummyBlock))
          } else {
            response.code is StatusCode.BadRequest
            response.as[ApiError.BadRequest] is ApiError.BadRequest(
              s"${blockHash.toHexString} belongs to other groups"
            )
          }
        }
      }
    }
  }

  it should "call GET /addresses/<address>/balance" in {
    val group = LockupScript.p2pkh(dummyKey).groupIndex(brokerConfig)
    if (utxosLimit > 0) {
      Get(s"/addresses/$dummyKeyAddress/balance", getPort(group)) check { response =>
        response.code is StatusCode.Ok
        response.as[Balance] is dummyBalance
      }
    }
  }

  it should "call GET /addresses/<address>/group" in {
    Get(s"/addresses/$dummyKeyAddress/group") check { response =>
      response.code is StatusCode.Ok
      response.as[Group] is dummyGroup
    }
    Get(s"/addresses/${dummyContractAddress}/group") check { response =>
      response.code is StatusCode.Ok
      response.as[Group] is Group(0)
    }
  }

  it should "call GET /addresses/<address>/utxos" in {
    val group = LockupScript.p2pkh(dummyKey).groupIndex(brokerConfig)
    Get(s"/addresses/$dummyKeyAddress/utxos", getPort(group)) check { response =>
      response.code is StatusCode.Ok
      val utxos = response.as[UTXOs]
      utxos.utxos.length is 2
    }
  }

  it should "call GET /blockflow/hashes" in {
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=1&height=1") check { response =>
      response.code is StatusCode.Ok
      response.as[HashesAtHeight] is dummyHashesAtHeight
    }
    Get(s"/blockflow/hashes?toGroup=1&height=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&height=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=10&toGroup=1&height=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=10&height=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=10&height=-1") check { response =>
      response.code is StatusCode.BadRequest
    }
  }

  it should "call GET /mempool/transactions" in {
    Get(s"/mempool/transactions") check { response =>
      response.code is StatusCode.Ok
      response.as[AVector[MempoolTransactions]] is AVector.empty[MempoolTransactions]
    }
  }

  it should "call DELETE /mempool/transactions" in {
    Delete(s"/mempool/transactions") check { response =>
      response.code is StatusCode.Ok
    }
  }

  it should "call PUT /mempool/transactions/rebroadcast" in {
    val txId = TransactionId.generate.toHexString
    Put(s"/mempool/transactions/rebroadcast/${txId}") check { response =>
      response.code is StatusCode.NotFound
    }
  }

  it should "call PUT /mempool/transactions/validate" in {
    Put(s"/mempool/transactions/validate") check { response =>
      // TODO: the dummy blockflow does not work for multi-node clique
      (response.code == StatusCode.Ok || response.code == StatusCode.InternalServerError) is true
    }
  }

  it should "call GET /blockflow/chain-info" in {
    Get(s"/blockflow/chain-info?fromGroup=1&toGroup=1") check { response =>
      response.code is StatusCode.Ok
      response.as[ChainInfo] is dummyChainInfo
    }
    Get(s"/blockflow/chain-info?toGroup=1") check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        s"Invalid value for: query parameter fromGroup"
      )
    }
    Get(s"/blockflow/chain-info?fromGroup=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/chain-info?fromGroup=10&toGroup=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/chain-info?fromGroup=1&toGroup=10") check { response =>
      response.code is StatusCode.BadRequest
    }
  }

  it should "call POST /transactions/build" in {
    Post(
      s"/transactions/build",
      body = s"""
                |{
                |  "fromPublicKey": "$dummyKeyHex",
                |  "destinations": [
                |    {
                |      "address": "$dummyToAddress",
                |      "attoAlphAmount": "1",
                |      "tokens": []
                |    }
                |  ]
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok
      response.as[BuildTransferTxResult] is dummyBuildTransactionResult(
        ServerFixture.dummyTransferTx(
          dummyTx,
          AVector(TxOutputInfo(dummyToLockupScript, U256.One, AVector.empty, None))
        )
      )
    }
    Post(
      s"/transactions/build",
      body = s"""
                |{
                |  "fromPublicKey": "$dummyKeyHex",
                |  "destinations": [
                |    {
                |      "address": "$dummyToAddress",
                |      "attoAlphAmount": "1",
                |      "tokens": [],
                |      "lockTime": "1234"
                |    }
                |  ]
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok
      response.as[BuildTransferTxResult] is dummyBuildTransactionResult(
        ServerFixture.dummyTransferTx(
          dummyTx,
          AVector(
            TxOutputInfo(
              dummyToLockupScript,
              U256.One,
              AVector.empty,
              Some(TimeStamp.unsafe(1234))
            )
          )
        )
      )
    }

    interCliqueSynced = false

    Post(
      s"/transactions/build",
      body = s"""
                |{
                |  "fromPublicKey": "$dummyKeyHex",
                |  "destinations": [
                |    {
                |      "address": "$dummyToAddress",
                |      "attoAlphAmount": "1",
                |      "tokens": []
                |    }
                |  ]
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.ServiceUnavailable
      response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call POST /transactions/submit" in {
    val tx =
      s"""{"unsignedTx":"${Hex.toHexString(
          serialize(dummyTx.unsigned)
        )}","signature":"${dummySignature.toHexString}","publicKey":"dummyKey),"}"""
    Post(s"/transactions/submit", tx) check { response =>
      response.code is StatusCode.Ok
      response.as[SubmitTxResult] is dummyTransferResult
    }

    interCliqueSynced = false

    Post(s"/transactions/submit", tx) check { response =>
      response.code is StatusCode.ServiceUnavailable
      response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call POST /transactions/sweep-address/build" in {
    Post(
      s"/transactions/sweep-address/build",
      body = s"""
                |{
                |  "fromPublicKey": "$dummyKeyHex",
                |  "toAddress": "$dummyToAddress"
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok
      response.as[BuildSweepAddressTransactionsResult] is dummySweepAddressBuildTransactionsResult(
        ServerFixture.dummySweepAddressTx(dummyTx, dummyToLockupScript, None),
        Address.asset(dummyKeyAddress).value.groupIndex,
        Address.asset(dummyToAddress).value.groupIndex
      )
    }
    Post(
      s"/transactions/sweep-address/build",
      body = s"""
                |{
                |  "fromPublicKey": "$dummyKeyHex",
                |  "toAddress": "$dummyToAddress",
                |  "lockTime": "1234"
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok
      response.as[BuildSweepAddressTransactionsResult] is dummySweepAddressBuildTransactionsResult(
        ServerFixture.dummySweepAddressTx(
          dummyTx,
          dummyToLockupScript,
          Some(TimeStamp.unsafe(1234))
        ),
        Address.asset(dummyKeyAddress).value.groupIndex,
        Address.asset(dummyToAddress).value.groupIndex
      )
    }

    interCliqueSynced = false

    Post(
      s"/transactions/sweep-address/build",
      body = s"""
                |{
                |  "fromPublicKey": "$dummyKeyHex",
                |  "toAddress": "$dummyToAddress"
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.ServiceUnavailable
      response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call GET /transactions/status" in {
    val chainIndex = ChainIndex.from(dummyBlock.hash, groupConfig.groups)

    forAll(hashGen) { txId =>
      servers.foreach { server =>
        verifyResponseWithNodes(
          s"/transactions/status?txId=${txId.toHexString}",
          s"/transactions/status?txId=${txId.toHexString}&fromGroup=${chainIndex.from.value}",
          chainIndex,
          server.port
        ) { response =>
          val status = response.as[TxStatus]
          response.code is StatusCode.Ok
          status is dummyTxStatus
        }

        verifyResponseWithNodes(
          s"/transactions/status?txId=${txId.toHexString}&toGroup=${chainIndex.to.value}",
          s"/transactions/status?txId=${txId.toHexString}&fromGroup=${chainIndex.from.value}&toGroup=${chainIndex.to.value}",
          chainIndex,
          server.port
        ) { response =>
          val status = response.as[TxStatus]
          response.code is StatusCode.Ok
          status is dummyTxStatus
        }

        verifyResponseWithNodes(
          s"/transactions/status?txId=${txId.toHexString}",
          s"/transactions/status?txId=${txId.toHexString}&fromGroup=${chainIndex.from.value}",
          chainIndex,
          server.port
        ) { response =>
          val status = response.as[TxStatus]
          response.code is StatusCode.Ok
          status is dummyTxStatus
        }
      }
    }
  }

  it should "call GET /transactions/details" in {
    servers.foreach { server =>
      val chainIndex = server.brokerConfig.chainIndexes.head
      verifyResponseWithNodes(
        s"/transactions/details/${dummyTx.id.toHexString}",
        s"/transactions/details/${dummyTx.id.toHexString}?fromGroup=${chainIndex.from.value}&toGroup=${chainIndex.to.value}",
        chainIndex,
        server.port
      ) { response =>
        val tx = response.as[Transaction]
        response.code is StatusCode.Ok
        tx is Transaction.fromProtocol(dummyTx)
      }

      verifyResponseWithNodes(
        s"/transactions/details/${dummyTx.id.toHexString}?toGroup=${chainIndex.to.value}",
        s"/transactions/details/${dummyTx.id.toHexString}?fromGroup=${chainIndex.from.value}",
        chainIndex,
        server.port
      ) { response =>
        val tx = response.as[Transaction]
        response.code is StatusCode.Ok
        tx is Transaction.fromProtocol(dummyTx)
      }

      verifyResponseWithNodes(
        s"/transactions/details/${dummyTx.id.toHexString}",
        s"/transactions/details/${dummyTx.id.toHexString}?fromGroup=${chainIndex.from.value}",
        chainIndex,
        server.port
      ) { response =>
        val tx = response.as[Transaction]
        response.code is StatusCode.Ok
        tx is Transaction.fromProtocol(dummyTx)
      }

      val txId = TransactionId.generate.toHexString
      verifyResponseWithNodes(
        s"/transactions/details/$txId",
        s"/transactions/details/$txId?fromGroup=${chainIndex.from.value}&toGroup=${chainIndex.to.value}",
        chainIndex,
        server.port
      ) { response =>
        response.code is StatusCode.NotFound
        response.body.leftValue is s"""{"resource":"Transaction $txId","detail":"Transaction $txId not found"}"""
      }
    }
  }

  it should "call GET /transactions/raw" in {
    def verifyResponse(response: Response[Either[String, String]]) = {
      val tx = response.as[RawTransaction]
      response.code is StatusCode.Ok
      tx is RawTransaction(serialize(dummyTx))
    }

    servers.foreach { server =>
      val chainIndex = server.brokerConfig.chainIndexes.head
      verifyResponseWithNodes(
        s"/transactions/raw/${dummyTx.id.toHexString}",
        s"/transactions/raw/${dummyTx.id.toHexString}?fromGroup=${chainIndex.from.value}&toGroup=${chainIndex.to.value}",
        chainIndex,
        server.port
      )(verifyResponse)

      verifyResponseWithNodes(
        s"/transactions/raw/${dummyTx.id.toHexString}?toGroup=${chainIndex.to.value}",
        s"/transactions/raw/${dummyTx.id.toHexString}?fromGroup=${chainIndex.from.value}",
        chainIndex,
        server.port
      )(verifyResponse)

      verifyResponseWithNodes(
        s"/transactions/raw/${dummyTx.id.toHexString}",
        s"/transactions/raw/${dummyTx.id.toHexString}?fromGroup=${chainIndex.from.value}",
        chainIndex,
        server.port
      )(verifyResponse)

      val txId = TransactionId.generate.toHexString
      verifyResponseWithNodes(
        s"/transactions/raw/$txId",
        s"/transactions/raw/$txId?fromGroup=${chainIndex.from.value}&toGroup=${chainIndex.to.value}",
        chainIndex,
        server.port
      ) { response =>
        response.code is StatusCode.NotFound
        response.body.leftValue is s"""{"resource":"Transaction $txId","detail":"Transaction $txId not found"}"""
      }
    }
  }

  it should "call POST /multisig/address for P2MPKH" in {
    lazy val (_, dummyKey2, _) = addressStringGen(
      GroupIndex.unsafe(1)
    ).sample.get

    lazy val dummyKeyHex2 = dummyKey2.toHexString

    Post(
      s"/multisig/address",
      body = s"""
                |{
                |  "keys": [
                | "$dummyKeyHex",
                | "$dummyKeyHex2"
                |],
                |  "mrequired": 1
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok

      val result   = response.as[BuildMultisigAddressResult]
      val expected = ServerFixture.p2mpkhAddress(AVector(dummyKeyHex, dummyKeyHex2), 1)

      result.address.toBase58 is expected
    }
  }

  it should "call POST /multisig/address for P2HMPK" in {
    lazy val (_, dummyKey2, _) = addressStringGen(
      GroupIndex.unsafe(1)
    ).sample.get

    lazy val dummyKeyHex2 = dummyKey2.toHexString

    Post(
      s"/multisig/address",
      body = s"""
                |{
                |  "keys": [
                | "$dummyKeyHex",
                | "$dummyKeyHex2"
                |],
                |  "keyTypes": [
                |  "default",
                |  "default"
                |],
                |  "multiSigType": "P2HMPK",
                |  "mrequired": 1
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok

      val result = response.as[BuildMultisigAddressResult]
      val expected = ServerFixture.p2hmpkAddress(
        AVector(dummyKeyHex, dummyKeyHex2),
        AVector(BuildTxCommon.Default, BuildTxCommon.Default),
        1
      )

      result.address.toBase58 is expected
    }

    Post(
      s"/multisig/address",
      body = s"""
                |{
                |  "keys": [
                | "$dummyKeyHex",
                | "$dummyKeyHex2"
                |],
                |  "keyTypes": [
                |  "default",
                |  "default"
                |],
                |  "multiSigType": "P2HMPK",
                |  "mrequired": 3
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        "Invalid m in m-of-n multisig: m=3, n=2"
      )
    }

    lazy val tooManyDummyKeys = AVector.fill(ALPH.MaxKeysInP2HMPK + 1)(
      addressStringGen(
        GroupIndex.unsafe(1)
      ).sample.get._2.toHexString
    )
    Post(
      s"/multisig/address",
      body = s"""
                |{
                |  "keys": [
                |    ${tooManyDummyKeys.map(k => s""""$k"""").mkString(",")}
                |  ],
                |  "multiSigType": "P2HMPK",
                |  "mrequired": 3
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        s"Too many public keys in P2HMPK: ${tooManyDummyKeys.length}, max is ${ALPH.MaxKeysInP2HMPK}"
      )
    }

    Post(
      s"/multisig/address",
      body = s"""
                |{
                |  "keys": [
                | "$dummyKeyHex",
                | "$dummyKeyHex2"
                |],
                |  "keyTypes": [
                |  "default"
                |],
                |  "multiSigType": "P2HMPK",
                |  "mrequired": 1
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        "`keyTypes` length should be the same as `keys` length"
      )
    }
  }

  it should "call POST /multisig/build" in {
    {
      info("P2MPKH")

      lazy val (_, dummyKey2, _) = addressStringGen(
        GroupIndex.unsafe(1)
      ).sample.get

      lazy val dummyKeyHex2 = dummyKey2.toHexString

      val address = ServerFixture.p2mpkhAddress(AVector(dummyKeyHex, dummyKeyHex2), 1)

      Post(
        s"/multisig/build",
        body = s"""
                  |{
                  |  "fromAddress": "${address}",
                  |  "fromPublicKeys": ["$dummyKeyHex"],
                  |  "destinations": [
                  |    {
                  |      "address": "$dummyToAddress",
                  |      "attoAlphAmount": "1",
                  |      "tokens": []
                  |    }
                  |  ]
                  |}
        """.stripMargin
      ) check { response =>
        response.code is StatusCode.Ok
        response.as[BuildTransferTxResult] is dummyBuildTransactionResult(
          ServerFixture.dummyTransferTx(
            dummyTx,
            AVector(TxOutputInfo(dummyToLockupScript, U256.One, AVector.empty, None))
          )
        )
      }
    }

    {
      info("P2HMPK")

      lazy val (_, dummyKey2, _) = addressStringGen(
        GroupIndex.unsafe(1)
      ).sample.get

      lazy val dummyKeyHex2 = dummyKey2.toHexString

      val address = ServerFixture.p2hmpkAddress(
        AVector(dummyKeyHex, dummyKeyHex2),
        AVector(BuildTxCommon.Default, BuildTxCommon.Default),
        1
      )

      Post(
        s"/multisig/build",
        body = s"""
                  |{
                  |  "fromAddress": "${address}",
                  |  "fromPublicKeys": ["$dummyKeyHex", "$dummyKeyHex2"],
                  |  "destinations": [
                  |    {
                  |      "address": "$dummyToAddress",
                  |      "attoAlphAmount": "1",
                  |      "tokens": []
                  |    }
                  |  ],
                  |  "group": 0,
                  |  "multiSigType": "P2HMPK"
                  |}
        """.stripMargin
      ) check { response =>
        response.code is StatusCode.BadRequest
        response.as[ApiError.BadRequest] is ApiError.BadRequest(
          "fromPublicKeyIndexes is required for P2HMPK multisig"
        )
      }

      Post(
        s"/multisig/build",
        body = s"""
                  |{
                  |  "fromAddress": "${address}",
                  |  "fromPublicKeys": ["$dummyKeyHex", "$dummyKeyHex2"],
                  |  "destinations": [
                  |    {
                  |      "address": "$dummyToAddress",
                  |      "attoAlphAmount": "1",
                  |      "tokens": []
                  |    }
                  |  ],
                  |  "group": 0,
                  |  "fromPublicKeyIndexes": [0],
                  |  "multiSigType": "P2HMPK"
                  |}
        """.stripMargin
      ) check { response =>
        response.code is StatusCode.Ok
        response.as[BuildTransferTxResult] is dummyBuildGrouplessTransactionResult(
          ServerFixture.dummyTransferTx(
            dummyTx,
            AVector(TxOutputInfo(dummyToLockupScript, U256.One, AVector.empty, None))
          )
        )
      }
    }
  }

  it should "call POST /multisig/submit" in {
    val tx =
      s"""{"unsignedTx":"${Hex.toHexString(
          serialize(dummyTx.unsigned)
        )}","signatures":["${dummySignature.toHexString}"]}"""
    Post(s"/multisig/submit", tx) check { response =>
      response.code is StatusCode.Ok
      response.as[SubmitTxResult] is dummyTransferResult
    }
  }

  it should "call POST /multisig/sweep" in {
    lazy val (_, dummyKey2, _) = addressStringGen(
      GroupIndex.unsafe(1)
    ).sample.get

    lazy val dummyKeyHex2 = dummyKey2.toHexString

    val address = ServerFixture.p2mpkhAddress(AVector(dummyKeyHex, dummyKeyHex2), 1)

    Post(
      s"/multisig/sweep",
      body = s"""
                |{
                |  "fromAddress": "${address}",
                |  "fromPublicKeys": ["$dummyKeyHex"],
                |  "toAddress": "$dummyToAddress"
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok
      response.as[BuildSweepAddressTransactionsResult] is dummySweepAddressBuildTransactionsResult(
        ServerFixture.dummySweepAddressTx(dummyTx, dummyToLockupScript, None),
        Address.asset(dummyKeyAddress).value.groupIndex,
        Address.asset(dummyToAddress).value.groupIndex
      )
    }
  }

  it should "call POST /miners" in {
    val address      = Address.asset(dummyKeyAddress).rightValue
    val lockupScript = address.lockupScript
    allHandlersProbe.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ViewHandler.GetMinerAddresses =>
          sender ! None
          TestActor.KeepRunning
        case InterCliqueManager.IsSynced =>
          sender ! InterCliqueManager.SyncedResult(true)
          TestActor.KeepRunning
      }
    )

    Post(s"/miners/cpu-mining?action=start-mining") check { response =>
      minerProbe.expectNoMessage()
      response.code is StatusCode.InternalServerError
      response.as[ApiError.InternalServerError] is
        ApiError.InternalServerError("Miner addresses are not set up")
    }

    allHandlersProbe.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ViewHandler.GetMinerAddresses =>
          sender ! Some(AVector(lockupScript))
          TestActor.KeepRunning
        case InterCliqueManager.IsSynced =>
          sender ! InterCliqueManager.SyncedResult(interCliqueSynced)
          TestActor.KeepRunning
      }
    )

    Post(s"/miners/cpu-mining?action=start-mining") check { response =>
      minerProbe.expectMsg(Miner.Start)
      response.code is StatusCode.Ok
      response.as[Boolean] is true
    }

    Post(s"/miners/cpu-mining?action=stop-mining") check { response =>
      minerProbe.expectMsg(Miner.Stop)
      response.code is StatusCode.Ok
      response.as[Boolean] is true
    }

    interCliqueSynced = false

    Post(s"/miners/cpu-mining?action=start-mining") check { response =>
      response.code is StatusCode.ServiceUnavailable
      response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }

    Post(
      s"/miners/cpu-mining/mine-one-block?fromGroup=${dummyGroup.group}&toGroup=${dummyGroup.group}"
    ) check { response =>
      response.code is StatusCode.ServiceUnavailable
      response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }

    interCliqueSynced = true
    Post(
      s"/miners/cpu-mining/mine-one-block?fromGroup=${dummyGroup.group}&toGroup=${dummyGroup.group}"
    ) check { response =>
      response.code is StatusCode.Ok
      response.as[Boolean] is true
    }

    Post(
      s"/miners/cpu-mining/mine-one-block?fromGroup=${dummyGroup.group}&toGroup=${dummyGroup.group + 10}"
    ) check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        s"Invalid value for: query parameter toGroup (Invalid group index: ${dummyGroup.group + 10})"
      )
    }

    Post(
      s"/miners/cpu-mining/mine-one-block?fromGroup=${dummyGroup.group}"
    ) check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        s"Invalid value for: query parameter toGroup"
      )
    }
  }

  it should "call GET /miners/addresses" in {
    val address      = Address.asset(dummyKeyAddress).rightValue
    val lockupScript = address.lockupScript

    allHandlersProbe.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ViewHandler.GetMinerAddresses =>
          sender ! Some(AVector(lockupScript))
          TestActor.NoAutoPilot
      }
    )

    Get(s"/miners/addresses") check { response =>
      response.code is StatusCode.Ok
      response.as[MinerAddresses] is MinerAddresses(AVector(address))
    }

    allHandlersProbe.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ViewHandler.GetMinerAddresses =>
          sender ! None
          TestActor.NoAutoPilot
      }
    )

    Get(s"/miners/addresses") check { response =>
      response.code is StatusCode.InternalServerError
      response.as[ApiError.InternalServerError] is
        ApiError.InternalServerError("Miner addresses are not set up")
    }
  }

  it should "call PUT /miners/addresses" in {
    allHandlersProbe.viewHandler.setAutoPilot(TestActor.NoAutoPilot)

    val newAddresses = AVector.tabulate(config.broker.groups)(i =>
      addressStringGen(GroupIndex.unsafe(i)).sample.get._1
    )
    val body = s"""{"addresses":${writeJs(newAddresses)}}"""

    Put(s"/miners/addresses", body) check { response =>
      val addresses = newAddresses.map(Address.asset(_).rightValue)
      allHandlersProbe.viewHandler.fishForSpecificMessage()(_ =>
        ViewHandler.UpdateMinerAddresses(addresses)
      )
      response.code is StatusCode.Ok
    }

    val notEnoughAddressesBody = s"""{"addresses":["${dummyKeyAddress}"]}"""
    Put(s"/miners/addresses", notEnoughAddressesBody) check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        s"Wrong number of addresses, expected ${config.broker.groups}, got 1"
      )
    }

    val wrongGroup     = AVector.tabulate(config.broker.groups)(_ => dummyKeyAddress)
    val wrongGroupBody = s"""{"addresses":${writeJs(wrongGroup)}}"""
    Put(s"/miners/addresses", wrongGroupBody) check { response =>
      response.code is StatusCode.BadRequest
      val errorGroup = dummyGroup.group match {
        case 0 => 1
        case _ => 0
      }
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        s"Address ${dummyKeyAddress} doesn't belong to group $errorGroup"
      )
    }
  }

  it should "call GET /infos/node" in {
    val buildInfo = NodeInfo.BuildInfo(BuildInfo.releaseVersion, BuildInfo.commitId)

    Get(s"/infos/node") check { response =>
      response.code is StatusCode.Ok
      response.as[NodeInfo] is NodeInfo(
        buildInfo,
        networkConfig.upnp.enabled,
        networkConfig.externalAddressInferred
      )
    }
  }

  it should "call GET /infos/version" in {
    Get(s"/infos/version") check { response =>
      response.code is StatusCode.Ok
      response.as[NodeVersion] is NodeVersion(ReleaseVersion.current)
    }
  }

  it should "call GET /infos/chain-params" in {
    Get(s"/infos/chain-params") check { response =>
      response.code is StatusCode.Ok
      val now = TimeStamp.now()
      response.as[ChainParams] is ChainParams(
        networkConfig.networkId,
        consensusConfigs.getConsensusConfig(now).numZerosAtLeastInHash,
        brokerConfig.groupNumPerBroker,
        brokerConfig.groups
      )
    }
  }

  it should "call GET /infos/misbehaviors" in {
    import MisbehaviorManager._

    val inetAddress = InetAddress.getByName("127.0.0.1")
    val ts          = TimeStamp.now()

    misbehaviorManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case GetPeers =>
            sender ! Peers(AVector(Peer(inetAddress, Banned(ts))))
            TestActor.NoAutoPilot
        }
    })

    Get(s"/infos/misbehaviors") check { response =>
      response.code is StatusCode.Ok
      response.as[AVector[PeerMisbehavior]] is AVector(
        PeerMisbehavior(inetAddress, PeerStatus.Banned(ts))
      )
    }
  }

  it should "call POST /infos/misbehaviors" in {
    val body = """{"type":"Unban","peers":["123.123.123.123"]}"""
    Post(s"/infos/misbehaviors", body) check { response =>
      response.code is StatusCode.Ok
    }
  }

  it should "call GET /infos/current-difficulty" in {
    Get(s"/infos/current-difficulty") check { response =>
      response.code is StatusCode.Ok
      response.as[CurrentDifficulty] is CurrentDifficulty(
        BigInteger.ONE
      )
    }
  }

  it should "call GET /contracts/<address>/state" in {
    Get(s"/contracts/${dummyContractAddress.toBase58}/state") check { response =>
      response.code is StatusCode.Ok
      response.as[ContractState].address is dummyContractAddress
    }
  }

  it should "call GET /contracts/<address>/parent with parent" in {
    val contractId      = ContractId.from(TransactionId.random, 0, GroupIndex.unsafe(0))
    val contractAddress = Address.contract(contractId)
    Get(s"/contracts/${contractAddress.toBase58}/parent") check { response =>
      response.code is StatusCode.Ok
      response.as[ContractParent] is ContractParent(Some(ServerFixture.dummyParentContractAddress))
    }
  }

  it should "call GET /contracts/<address>/sub-contracts" in {
    val contractWithoutParent = Address.contract(ContractId.zero)
    val contractWithParent    = dummyContractAddress
    Get(s"/contracts/${contractWithParent.toBase58}/sub-contracts?start=0&limit=2") check {
      response =>
        response.code is StatusCode.Ok
        response.as[SubContracts] is SubContracts(
          AVector(
            ServerFixture.dummySubContractAddress1,
            ServerFixture.dummySubContractAddress2
          ),
          2
        )
    }

    Get(s"/contracts/${contractWithParent.toBase58}/sub-contracts?start=0&limit=101") check {
      response =>
        response.code is StatusCode.BadRequest
        response.as[ApiError.BadRequest] is ApiError.BadRequest(
          s"Invalid value (expected value to pass validation: `limit` must not be larger than 100, but got: CounterRange(0,Some(101)))"
        )
    }

    Get(s"/contracts/${contractWithoutParent.toBase58}/parent") check { response =>
      response.code is StatusCode.Ok
      response.as[ContractParent] is ContractParent(None)
    }
  }

  it should "call GET /contracts/<address>/sub-contracts/current-count" in {
    val contractWithCount =
      Address.contract(ContractId.from(TransactionId.random, 0, GroupIndex.unsafe(0)))
    val contractWithoutCount = dummyContractAddress
    Get(s"/contracts/${contractWithCount.toBase58}/sub-contracts/current-count") check { response =>
      response.code is StatusCode.Ok
      response.as[Int] is 10
    }

    Get(s"/contracts/${contractWithoutCount.toBase58}/sub-contracts/current-count") check {
      _.code is StatusCode.NotFound
    }
  }

  it should "call GET /transactions/tx-id-from-outputref" in {
    val assetOutputRefWithTxId    = ServerFixture.dummyAssetOutputRef
    val assetOutputRefWithoutTxId = assetOutputRefGen(GroupIndex.unsafe(0)).sample.value
    val contractOutputRef         = contractOutputRefGen(GroupIndex.unsafe(0)).sample.value
    def toQuery(outputRef: TxOutputRef) = {
      s"?hint=${outputRef.hint.value}&key=${outputRef.key.value.toHexString}"
    }

    Get(s"/transactions/tx-id-from-outputref${toQuery(assetOutputRefWithTxId)}") check { response =>
      response.code is StatusCode.Ok
      response.as[TransactionId] is ServerFixture.dummyTransactionId
    }

    Get(s"/transactions/tx-id-from-outputref${toQuery(assetOutputRefWithoutTxId)}") check {
      response =>
        response.code is StatusCode.NotFound
        response
          .as[ApiError.NotFound]
          .resource is s"Transaction id for output ref ${assetOutputRefWithoutTxId.key.value.toHexString}"
    }

    Get(s"/transactions/tx-id-from-outputref${toQuery(contractOutputRef)}") check { response =>
      response.code is StatusCode.NotFound
      response
        .as[ApiError.NotFound]
        .resource is s"Transaction id for output ref ${contractOutputRef.key.value.toHexString}"
    }
  }

  it should "call GET /docs" in {
    Get(s"/docs") check { response =>
      response.code is StatusCode.Ok
    }

    Get(s"/docs/openapi.json") check { response =>
      response.code is StatusCode.Ok

      val openapiPath = ApiModel.getClass.getResource("/openapi.json")
      val expectedOpenapi =
        read[ujson.Value](
          Using(Source.fromFile(openapiPath.getPath, "UTF-8")) { source =>
            val openApiJson = source.getLines().mkString("\n")
            reverseAddressTruncation(openApiJson).replaceFirst("12973", s"$port")
          }.get
        )

      val openapi =
        removeField("security", removeField("securitySchemes", response.as[ujson.Value]))

      Get(s"/docs") check { response =>
        response.code is StatusCode.Ok
        openapi is expectedOpenapi
      }

    }
  }

  it should "check the use of api-key is incorrect" in {
    val newApiKey = if (apiKeys.nonEmpty) {
      None
    } else {
      Some(Hash.random.toHexString)
    }

    Get(blockflowFromTo(0, 1), apiKey = newApiKey) check { response =>
      response.code is StatusCode.Unauthorized
      if (newApiKey.isDefined) {
        response.as[ApiError.Unauthorized] is ApiError.Unauthorized(
          "Api key not configured in server"
        )
      } else {
        response.as[ApiError.Unauthorized] is ApiError.Unauthorized("Missing api key")
      }
    }
  }

  it should "validate the api-key" in {
    val newApiKey = apiKeys.headOption.map(_ => Hash.random.toHexString)

    Get(blockflowFromTo(0, 1), apiKey = newApiKey) check { response =>
      if (apiKeys.headOption.isDefined) {
        response.code is StatusCode.Unauthorized
        response.as[ApiError.Unauthorized] is ApiError.Unauthorized("Wrong api key")
      } else {
        response.code is StatusCode.Ok
      }
    }
  }

  it should "get events for a contract within a counter range with events" in {
    val blockHash  = dummyBlock.hash
    val start      = 10
    val limit      = 90
    val urlBase    = s"/events/contract/$dummyContractAddress"
    val chainIndex = ChainIndex.from(blockHash, groupConfig.groups)

    info("with valid start and limit")
    verifyResponseWithNodes(
      s"$urlBase?start=$start&limit=$limit",
      s"$urlBase?start=$start&limit=$limit&group=${chainIndex.from.value}",
      chainIndex,
      port
    )(validResponse)

    info("with start only")
    verifyResponseWithNodes(
      s"$urlBase?start=$start",
      s"$urlBase?start=$start&group=${chainIndex.from.value}",
      chainIndex,
      port
    )(validResponse)

    info("with non-positive limit")
    Get(s"$urlBase?start=$start&limit=0").check { response =>
      response.code is StatusCode.BadRequest
      response.body.leftValue is s"""{"detail":"Invalid value (expected value to pass validation: `limit` must be larger than 0, but got: CounterRange(10,Some(0)))"}"""
    }

    info("with limit larger than MaxCounterRange")
    Get(s"$urlBase?start=$start&limit=${CounterRange.MaxCounterRange + 1}").check { response =>
      response.code is StatusCode.BadRequest
      response.body.leftValue is s"""{"detail":"Invalid value (expected value to pass validation: `limit` must not be larger than 100, but got: CounterRange(10,Some(101)))"}"""
    }

    info("with start larger than (Int.MaxValue - MaxCounterRange)")
    Get(s"$urlBase?start=${Int.MaxValue - CounterRange.MaxCounterRange + 1}").check { response =>
      response.code is StatusCode.BadRequest
      response.body.leftValue is s"""{"detail":"Invalid value (expected value to pass validation: `start` must be smaller than 2147483547, but got: CounterRange(2147483548,None))"}"""
    }

    def validResponse(response: Response[Either[String, String]]): Assertion = {
      response.code is StatusCode.Ok
      val events = response.body.rightValue
      events is s"""
                   |{
                   |  "events": [
                   |    {
                   |      "blockHash": "${blockHash.toHexString}",
                   |      "txId": "${dummyTx.id.toHexString}",
                   |      "eventIndex": 0,
                   |      "fields": [
                   |        {
                   |          "type": "U256",
                   |          "value": "4"
                   |        },
                   |        {
                   |          "type": "Address",
                   |          "value": "16BCZkZzGb3QnycJQefDHqeZcTA5RhrwYUDsAYkCf7RhS"
                   |        },
                   |        {
                   |          "type": "Address",
                   |          "value": "27gAhB8JB6UtE9tC3PwGRbXHiZJ9ApuCMoHqe1T4VzqFi"
                   |        }
                   |      ]
                   |    }
                   |  ],
                   |  "nextStart": 2
                   |}
                   |""".stripMargin.filterNot(_.isWhitespace)
    }
  }

  it should "get events for a contract within a counter range without events" in {
    val blockHash = dummyBlock.hash
    val start     = 10
    val end       = 100
    // No events for this contractId, see `getEvents` method for `BlockFlowDummy` in `ServerFixture.scala`
    val contractId =
      ContractId.unsafe(
        Blake2b.unsafe(hex"e939f9c5d2ad12ea2375dcc5231f5f25db0a2ac8af426f547819e13559aa693e")
      )
    val contractAddress = Address.Contract(LockupScript.P2C(contractId)).toBase58
    val urlBase         = s"/events/contract/$contractAddress"

    servers.foreach { server =>
      val chainIndex = ChainIndex.from(blockHash, server.node.config.broker.groups)
      verifyResponseWithNodes(
        s"$urlBase?start=$start&end=$end",
        s"$urlBase?start=$start&end=$end&group=${chainIndex.from.value}",
        chainIndex,
        server.port
      )(verifyEmptyContractEvents)
    }
  }

  trait RedirectFixture {
    allHandlersProbe.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case InterCliqueManager.IsSynced =>
          sender ! InterCliqueManager.SyncedResult(true)
          TestActor.KeepRunning
      }
    )
  }

  // scalastyle:off no.equal
  it should "get events for contract id with wrong group" in new RedirectFixture {
    val blockHash       = dummyBlock.hash
    val contractId      = ContractId.random
    val contractAddress = Address.Contract(LockupScript.P2C(contractId)).toBase58
    val chainIndex      = ChainIndex.from(blockHash, groupConfig.groups)
    val wrongGroup      = (chainIndex.from.value + 1) % groupConfig.groups
    val url             = s"/events/contract/$contractAddress?start=10&end=100&group=${wrongGroup}"

    if (nbOfNodes === 1) {
      // Ignore group if it is 1 node setup, since the events are always available
      Get(url, port).check(verifyNonEmptyEvents)
    } else {
      Get(url, port).check(verifyEmptyContractEvents)
    }
  }
  // scalastyle:on no.equal

  it should "get events for tx id with events" in {
    val blockHash = dummyBlock.hash
    val txId      = TransactionId.random
    val contractId =
      ContractId.unsafe(txId.value) // TODO: refactor BlockFlowDummy to fix this hacky value

    servers.foreach { server =>
      val chainIndex = ChainIndex.from(blockHash, server.node.config.broker.groups)
      verifyResponseWithNodes(
        s"/events/tx-id/${txId.toHexString}",
        s"/events/tx-id/${txId.toHexString}?group=${chainIndex.from.value}",
        chainIndex,
        server.port
      ) { response =>
        response.code is StatusCode.Ok
        val events = response.body.rightValue
        events is s"""
                     |{
                     |  "events": [
                     |    {
                     |      "blockHash": "${blockHash.toHexString}",
                     |      "contractAddress": "${Address.contract(contractId).toBase58}",
                     |      "eventIndex": 0,
                     |      "fields": [
                     |        {
                     |          "type": "U256",
                     |          "value": "4"
                     |        },
                     |        {
                     |          "type": "Address",
                     |          "value": "16BCZkZzGb3QnycJQefDHqeZcTA5RhrwYUDsAYkCf7RhS"
                     |        },
                     |        {
                     |          "type": "Address",
                     |          "value": "27gAhB8JB6UtE9tC3PwGRbXHiZJ9ApuCMoHqe1T4VzqFi"
                     |        }
                     |      ]
                     |    }
                     |  ]
                     |}
                     |""".stripMargin.filterNot(_.isWhitespace)
      }
    }
  }

  it should "get events for tx id without events" in {
    val blockHash = dummyBlock.hash
    // No events for this txId, see `getEvents` method for `BlockFlowDummy` in `ServerFixture.scala`
    val txId = Blake2b.unsafe(hex"aab64e9c814749cea508857b23c7550da30b67216950c461ccac1a14a58661c3")

    servers.foreach { server =>
      val chainIndex = ChainIndex.from(blockHash, server.node.config.broker.groups)
      verifyResponseWithNodes(
        s"/events/tx-id/${txId.toHexString}",
        s"/events/tx-id/${txId.toHexString}?group=${chainIndex.from.value}",
        chainIndex,
        server.port
      )(verifyEmptyContractEventsByHash)
    }
  }

  it should "get events for tx id with wrong group" in new RedirectFixture {
    val blockHash  = dummyBlock.hash
    val txId       = Hash.random
    val chainIndex = ChainIndex.from(blockHash, groupConfig.groups)
    val wrongGroup = (chainIndex.from.value + 1) % groupConfig.groups
    val url        = s"/events/tx-id/${txId.toHexString}?group=${wrongGroup}"

    if (nbOfNodes === 1) {
      // Ignore group if it is 1 node setup, since the events are always available
      Get(url, port).check(verifyNonEmptyEvents)
    } else {
      Get(url, port).check(verifyEmptyContractEventsByHash)
    }
  }

  it should "get events for block hash with events" in {
    val blockHash = dummyBlock.hash
    val contractId = ContractId.unsafe(
      Hash.unsafe(blockHash.bytes)
    ) // TODO: refactor BlockFlowDummy to fix this hacky value

    servers.foreach { server =>
      val chainIndex = ChainIndex.from(blockHash, server.node.config.broker.groups)
      verifyResponseWithNodes(
        s"/events/block-hash/${blockHash.toHexString}",
        s"/events/block-hash/${blockHash.toHexString}?group=${chainIndex.from.value}",
        chainIndex,
        server.port
      ) { response =>
        response.code is StatusCode.Ok
        val events = response.body.rightValue
        events is s"""
                     |{
                     |  "events": [
                     |    {
                     |      "txId": "${dummyTx.id.toHexString}",
                     |      "contractAddress": "${Address.contract(contractId).toBase58}",
                     |      "eventIndex": 0,
                     |      "fields": [
                     |        {
                     |          "type": "U256",
                     |          "value": "4"
                     |        },
                     |        {
                     |          "type": "Address",
                     |          "value": "16BCZkZzGb3QnycJQefDHqeZcTA5RhrwYUDsAYkCf7RhS"
                     |        },
                     |        {
                     |          "type": "Address",
                     |          "value": "27gAhB8JB6UtE9tC3PwGRbXHiZJ9ApuCMoHqe1T4VzqFi"
                     |        }
                     |      ]
                     |    }
                     |  ]
                     |}
                     |""".stripMargin.filterNot(_.isWhitespace)
      }
    }
  }

  it should "get events for block hash without events" in {
    val blockHash = dummyBlock.hash
    // No events for this blockHash, see `getEvents` method for `BlockFlowDummy` in `ServerFixture.scala`
    val blockHashToQuery =
      BlockHash.unsafe(hex"aab64e9c814749cea508857b23c7550da30b67216950c461ccac1a14a58661c3")

    servers.foreach { server =>
      val chainIndex = ChainIndex.from(blockHash, server.node.config.broker.groups)
      verifyResponseWithNodes(
        s"/events/block-hash/${blockHashToQuery.toHexString}",
        s"/events/block-hash/${blockHashToQuery.toHexString}?group=${chainIndex.from.value}",
        chainIndex,
        server.port
      )(verifyEmptyContractEventsByHash)
    }
  }

  it should "get events for block hash with wrong group" in new RedirectFixture {
    val blockHash  = dummyBlock.hash
    val chainIndex = ChainIndex.from(blockHash, groupConfig.groups)
    val wrongGroup = (chainIndex.from.value + 1) % groupConfig.groups
    val url        = s"/events/block-hash/${blockHash.toHexString}?group=${wrongGroup}"

    if (nbOfNodes === 1) {
      // Ignore group if it is 1 node setup, since the events are always available
      Get(url, port).check(verifyNonEmptyEvents)
    } else {
      Get(url, port).check(verifyEmptyContractEventsByHash)
    }
  }

  it should "get current events count for a contract" in {
    val url = s"/events/contract/$dummyContractAddress/current-count"
    Get(url) check { response =>
      response.code is StatusCode.Ok
      response.body.rightValue is "10"
    }
  }

  it should "get current events count for a transaction" in {
    val blockHash = dummyBlock.hash

    servers.foreach { server =>
      val chainIndex = ChainIndex.from(blockHash, server.node.config.broker.groups)
      verifyResponseWithNodes(
        s"/events/tx-id/${dummyTx.id.toHexString}",
        s"/events/tx-id/${dummyTx.id.toHexString}?group=${chainIndex.from.value}",
        chainIndex,
        server.port
      )(verifyNonEmptyEvents)
    }
  }

  it should "convert target to hashrate" in {
    val target = "1b032b55"

    Post(
      s"/utils/target-to-hashrate",
      body = s"""
                |{
                |  "target": "$target"
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok

      val hashrateResponse = response.as[TargetToHashrate.Result]
      val consensusConfig  = consensusConfigs.getConsensusConfig(TimeStamp.now())
      val expected =
        HashRate.from(Target.unsafe(Hex.unsafe(target)), consensusConfig.blockTargetTime).value

      hashrateResponse.hashrate is expected
    }

    Post(
      s"/utils/target-to-hashrate",
      body = s"""
                |{
                |  "target": "1234"
                |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.BadRequest

      val badRequest = response.as[ApiError.BadRequest]

      badRequest.detail is "Invalid target string: 1234"
    }
  }

  it should "call /contracts/call-tx-script" in {
    val script =
      s"""
         |TxScript Main {
         |  pub fn main() -> U256 {
         |    return 1
         |  }
         |}
         |""".stripMargin
    val bytecode = serialize(Compiler.compileTxScript(script).rightValue)
    Post(
      s"/contracts/call-tx-script",
      body = s"""
                |{
                |  "group": 0,
                |  "bytecode": "${Hex.toHexString(bytecode)}"
                |}
        """.stripMargin
    ) check { response =>
      response.as[CallTxScriptResult].returns is AVector[Val](ValU256(1))
    }
  }

  def verifyNonEmptyEvents(response: Response[Either[String, String]]): Assertion = {
    response.code is StatusCode.Ok
    val events = response.body.rightValue
    events.startsWith(s"""{"events":[{"""") is true
  }

  def verifyEmptyContractEvents(response: Response[Either[String, String]]): Assertion = {
    response.code is StatusCode.Ok
    response.body.rightValue is s"""
                                   |{
                                   |  "events": [],
                                   |  "nextStart": 0
                                   |}
                                   |""".stripMargin.filterNot(_.isWhitespace)
  }

  def verifyEmptyContractEventsByHash(response: Response[Either[String, String]]): Assertion = {
    response.code is StatusCode.Ok
    response.body.rightValue is s"""
                                   |{
                                   |  "events": []
                                   |}
                                   |""".stripMargin.filterNot(_.isWhitespace)
  }

  // scalastyle:off no.equal
  def verifyResponseWithNodes(
      urlWithoutGroup: String,
      urlWithGroup: String,
      chainIndex: ChainIndex,
      port: Int
  )(validVerify: Response[Either[String, String]] => Assertion) = {
    if (nbOfNodes === 1) {
      AVector(urlWithoutGroup, urlWithGroup).foreach(Get(_, port).check(validVerify))
    } else {
      Get(urlWithoutGroup, port) check { response =>
        response.code is StatusCode.BadRequest
        response.body.leftValue is s"""{"detail":"`group` parameter is required with multiple brokers"}"""
      }

      Get(s"$urlWithGroup?group=${chainIndex.from.value}", port).check(validVerify)
    }
  }
  // scalastyle:on no.equal
}

abstract class RestServerApiKeyDisableSpec(
    val apiKeys: AVector[ApiKey],
    val nbOfNodes: Int = 1,
    val apiKeyEnabled: Boolean = false,
    val utxosLimit: Int = Int.MaxValue,
    val maxFormBufferedBytes: Int = 1024
) extends RestServerFixture {

  it should "not require api key if disabled" in {
    Get(blockflowFromTo(0, 1), apiKey = None) check { response =>
      response.code is StatusCode.Ok
    }
  }
}

trait RestServerFixture
    extends ServerFixture
    with HttpRouteFixture
    with AlephiumFutureSpec
    with TxGenerators
    with EitherValues
    with NumericHelpers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  override def beforeAll() = {
    super.beforeAll()
    servers.foreach(_.start().futureValue)
  }

  override def afterAll() = {
    super.afterAll()
    servers.foreach(_.stop().futureValue)
  }

  override def beforeEach() = {
    super.beforeEach()
    interCliqueSynced = true
  }

  val nbOfNodes: Int
  val apiKeys: AVector[ApiKey]
  val apiKeyEnabled: Boolean
  val utxosLimit: Int
  val maxFormBufferedBytes: Int

  implicit val system: ActorSystem  = ActorSystem("rest-server-spec")
  implicit val ec: ExecutionContext = system.dispatcher

  override val configValues = {
    Map[String, Any](
      ("alephium.broker.broker-num", nbOfNodes),
      ("alephium.api.api-key-enabled", apiKeyEnabled),
      ("alephium.api.default-utxos-limit", utxosLimit),
      ("alephium.api.max-form-buffered-bytes", maxFormBufferedBytes),
      ("alephium.node.indexes.tx-output-ref-index", true),
      ("alephium.node.indexes.subcontract-index", true)
    ) ++ apiKeys.headOption
      .map(_ => Map(("alephium.api.api-key", apiKeys.map(_.value))))
      .getOrElse(Map.empty)
  }

  lazy val minerProbe                      = TestProbe()
  lazy val miner                           = ActorRefT[Miner.Command](minerProbe.ref)
  lazy val (allHandlers, allHandlersProbe) = TestUtils.createAllHandlersProbe

  var selfCliqueSynced  = true
  var interCliqueSynced = true

  allHandlersProbe.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
    msg match {
      case InterCliqueManager.IsSynced =>
        sender ! InterCliqueManager.SyncedResult(interCliqueSynced)
        TestActor.KeepRunning
    }
  )
  lazy val cliqueManager: ActorRefT[CliqueManager.Command] =
    ActorRefT.build(
      system,
      Props(new BaseActor {
        override def receive: Receive = { case CliqueManager.IsSelfCliqueReady =>
          sender() ! selfCliqueSynced
        }
      }),
      s"clique-manager-${Random.nextInt()}"
    )

  lazy val blockFlowProbe = TestProbe()

  lazy val misbehaviorManagerProbe = TestProbe()
  lazy val misbehaviorManager = ActorRefT[MisbehaviorManager.Command](misbehaviorManagerProbe.ref)

  implicit lazy val apiConfig: ApiConfig = ApiConfig.load(newConfig)

  lazy val node = new NodeDummy(
    dummyIntraCliqueInfo,
    dummyNeighborPeers,
    dummyBlock,
    blockFlowProbe.ref,
    allHandlers,
    dummyTx,
    counterContract,
    storages,
    cliqueManagerOpt = Some(cliqueManager),
    misbehaviorManagerOpt = Some(misbehaviorManager)
  )
  lazy val blocksExporter = new BlocksExporter(node.blockFlow, rootPath)
  val walletConfig: WalletConfig = WalletConfig(
    None,
    (new java.io.File("")).toPath,
    Duration.ofMinutesUnsafe(0),
    apiConfig.apiKey,
    apiConfig.enableHttpMetrics,
    WalletConfig.BlockFlow(
      "host",
      0,
      0,
      Duration.ofMinutesUnsafe(0),
      apiConfig.apiKey.shuffle().headOption
    )
  )

  lazy val walletApp = new WalletApp(walletConfig)

  implicit lazy val blockflowFetchMaxAge: Duration = Duration.ofHoursUnsafe(1)

  private def buildPeer(id: Int): (PeerInfo, ApiConfig) = {
    val peerPort = generatePort()

    val address = new InetSocketAddress("127.0.0.1", peerPort)
    // all same port as only `restPort` is used
    val peer = PeerInfo.unsafe(
      id,
      groupNumPerBroker = config.broker.groupNumPerBroker,
      publicAddress = None,
      privateAddress = address,
      restPort = peerPort,
      wsPort = peerPort,
      minerApiPort = peerPort
    )

    val peerConf = ApiConfig(
      networkInterface = address.getAddress,
      blockflowFetchMaxAge = blockflowFetchMaxAge,
      askTimeout = Duration.ofMinutesUnsafe(1),
      apiConfig.apiKey,
      ALPH.oneAlph,
      utxosLimit,
      maxFormBufferedBytes,
      enableHttpMetrics = true
    )

    (peer, peerConf)
  }

  def blockflowFromTo(from: Long, to: Long): String = {
    s"/blockflow/blocks?fromTs=$from&toTs=$to"
  }

  def blocksWithEvents(from: Long, to: Long): String = {
    s"/blockflow/blocks-with-events?fromTs=$from&toTs=$to"
  }

  private def buildServers(nb: Int) = {
    val peers = (0 until nb).map(buildPeer)

    val intraCliqueInfo = IntraCliqueInfo.unsafe(
      dummyIntraCliqueInfo.id,
      AVector.from(peers.map(_._1)),
      groupNumPerBroker = config.broker.groupNumPerBroker,
      dummyIntraCliqueInfo.priKey
    )

    AVector.from(peers.zipWithIndex.map { case ((peer, peerConf), id) =>
      val serverConfig = config.copy(broker = config.broker.copy(brokerId = id))
      val nodeDummy = new NodeDummy(
        intraCliqueInfo,
        dummyNeighborPeers,
        dummyBlock,
        blockFlowProbe.ref,
        allHandlers,
        dummyTx,
        dummyContract,
        storages,
        cliqueManagerOpt = Some(cliqueManager),
        misbehaviorManagerOpt = Some(misbehaviorManager)
      )(serverConfig)

      new RestServer(
        nodeDummy,
        peer.restPort,
        miner,
        blocksExporter,
        Some(walletApp.walletServer)
      )(
        serverConfig.broker,
        peerConf,
        scala.concurrent.ExecutionContext.Implicits.global
      )
    })
  }

  lazy val servers = buildServers(nbOfNodes)

  override lazy val port        = servers.sample().port
  override lazy val maybeApiKey = apiKeys.shuffle().headOption.map(_.value)

  def getPort(group: GroupIndex): Int =
    servers.find(_.node.config.broker.contains(group)).get.port

  def reverseAddressTruncation(openApiJson: String): String = {
    openApiJson.replaceAll(
      OpenAPIWriters.address.toBase58.dropRight(2),
      OpenAPIWriters.address.toBase58
    )
  }

  // scalastyle:off no.equal
  def removeField(name: String, json: ujson.Value): ujson.Value = {
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def rec(json: ujson.Value): ujson.Value = {
      json match {
        case obj: ujson.Obj =>
          ujson.Obj.from(
            obj.value.filterNot { case (key, _) => key == name }.map { case (key, value) =>
              key -> rec(value)
            }
          )

        case arr: ujson.Arr =>
          val newValues = arr.value.map { value =>
            rec(value)
          }
          ujson.Arr.from(newValues)

        case x => x
      }
    }
    json match {
      case ujson.Null => ujson.Null
      case other      => rec(other)
    }
  }
}

class RestServerSpec1Node  extends RestServerSpec(1)
class RestServerSpec3Nodes extends RestServerSpec(3)
class RestServerSpecApiKey
    extends RestServerSpec(
      3,
      AVector(
        ApiKey.unsafe("74beb7e20967727763f3c88a1ef596e7b22049047cc6fa8ea27358b32c68377"),
        ApiKey.unsafe("88a1ef596e7b67727763f3c220ea27349047cc6fa858b32c6837774beb7e209"),
        ApiKey.unsafe("f5988a1e6e7b63c227727763f0ea2734904837774beb7e2097cc6fa858b32c6")
      ),
      true
    )
class RestServerSpecApiKeyDisableWithoutApiKey extends RestServerApiKeyDisableSpec(AVector.empty)
class RestServerWithZeroUtxosLimit             extends RestServerSpec(nbOfNodes = 1, utxosLimit = 0)
