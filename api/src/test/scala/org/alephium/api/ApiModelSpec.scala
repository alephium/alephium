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

package org.alephium.api

import java.net.{InetAddress, InetSocketAddress}

import org.scalatest.{Assertion, EitherValues}

import org.alephium.api.UtilJson._
import org.alephium.api.model._
import org.alephium.json.Json._
import org.alephium.protocol._
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util._
import org.alephium.util.Hex.HexStringSyntax

class ApiModelSpec extends AlephiumSpec with ApiModelCodec with EitherValues with NumericHelpers {

  val zeroHash: String = BlockHash.zero.toHexString
  def entryDummy(i: Int): BlockEntry =
    BlockEntry(BlockHash.zero, TimeStamp.unsafe(i.toLong), i, i, i, AVector(BlockHash.zero), None)
  val dummyAddress     = new InetSocketAddress("127.0.0.1", 9000)
  val (priKey, pubKey) = SignatureSchema.secureGeneratePriPub()
  val dummyCliqueInfo =
    CliqueInfo.unsafe(
      CliqueId.generate,
      AVector(Option(dummyAddress)),
      AVector(dummyAddress),
      1,
      priKey
    )
  val dummyPeerInfo = BrokerInfo.unsafe(CliqueId.generate, 1, 3, dummyAddress)

  val blockflowFetchMaxAge = Duration.unsafe(1000)

  val networkType = NetworkType.Mainnet

  val inetAddress = InetAddress.getByName("127.0.0.1")

  def generateAddress(): Address = Address.p2pkh(networkType, PublicKey.generate)

  def checkData[T: Reader: Writer](data: T, jsonRaw: String): Assertion = {
    writeJs(data) is read[ujson.Value](jsonRaw)
    read[T](jsonRaw) is data
  }

  def parseFail[A: Reader](jsonRaw: String): String = {
    scala.util.Try(read[A](jsonRaw)).toEither.swap.toOption.get.getMessage
  }

  it should "encode/decode TimeStamp" in {
    checkData(TimeStamp.unsafe(0), "0")
    checkData(TimeStamp.unsafe(43850028L), "43850028")
    checkData(TimeStamp.unsafe(4385002872679507624L), "\"4385002872679507624\"")

    forAll(negLongGen) { long =>
      parseFail[TimeStamp](s"$long") is "expect positive timestamp at index 0"
    }
  }

  it should "encode/decode FetchRequest" in {
    val request =
      FetchRequest(TimeStamp.unsafe(1L), TimeStamp.unsafe(42L))
    val jsonRaw = """{"fromTs":1,"toTs":42}"""
    checkData(request, jsonRaw)
  }

  it should "validate FetchRequest" in {
    parseFail[FetchRequest](
      """{"fromTs":42,"toTs":1}"""
    ) is "`toTs` cannot be before `fromTs` at index 21"
    parseFail[FetchRequest](
      """{"fromTs":1,"toTs":100000}"""
    ) is s"interval cannot be greater than $blockflowFetchMaxAge at index 25"
    parseFail[FetchRequest]("""{}""") is s"missing keys in dictionary: fromTs, toTs at index 1"
  }

  it should "encode/decode empty FetchResponse" in {
    val response = FetchResponse(AVector.empty)
    val jsonRaw =
      """{"blocks":[]}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode FetchResponse" in {
    val response = FetchResponse(AVector.tabulate(2)(entryDummy))
    val jsonRaw =
      s"""{"blocks":[{"hash":"$zeroHash","timestamp":0,"chainFrom":0,"chainTo":0,"height":0,"deps":["$zeroHash"]},{"hash":"$zeroHash","timestamp":1,"chainFrom":1,"chainTo":1,"height":1,"deps":["$zeroHash"]}]}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode NodeInfo" in {
    val nodeInfo = NodeInfo(isMining = true)
    val jsonRaw =
      s"""{"isMining":true}"""
    checkData(nodeInfo, jsonRaw)
  }

  it should "encode/decode SelfClique" in {
    val cliqueId = CliqueId.generate
    val peerAddress =
      PeerAddress(inetAddress, 9001, 9002, 9003)
    val selfClique = SelfClique(cliqueId, NetworkType.Mainnet, 18, AVector(peerAddress), true, 1, 2)
    val jsonRaw =
      s"""{"cliqueId":"${cliqueId.toHexString}","networkType":"mainnet","numZerosAtLeastInHash":18,"nodes":[{"address":"127.0.0.1","restPort":9001,"wsPort":9002,"minerApiPort":9003}],"synced":true,"groupNumPerBroker":1,"groups":2}"""
    checkData(selfClique, jsonRaw)
  }

  it should "encode/decode NeighborPeers" in {
    val neighborCliques = NeighborPeers(AVector(dummyPeerInfo))
    val cliqueIdString  = dummyPeerInfo.cliqueId.toHexString
    def jsonRaw(cliqueId: String) =
      s"""{"peers":[{"cliqueId":"$cliqueId","brokerId":1,"groupNumPerBroker":3,"address":{"addr":"127.0.0.1","port":9000}}]}"""
    checkData(neighborCliques, jsonRaw(cliqueIdString))

    parseFail[NeighborPeers](jsonRaw("OOPS")) is "invalid clique id at index 106"
  }

  it should "encode/decode GetBalance" in {
    val address    = generateAddress()
    val addressStr = address.toBase58
    val request    = GetBalance(address)
    val jsonRaw    = s"""{"address":"$addressStr"}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode Input" in {
    val key       = Hash.generate
    val outputRef = OutputRef(1234, key)

    {
      val data    = Input(outputRef, None)
      val jsonRaw = s"""{"outputRef":{"scriptHint":1234,"key":"${key.toHexString}"}}"""
      checkData(data, jsonRaw)
    }

    {
      val data = Input(outputRef, Some(hex"abcd"))
      val jsonRaw =
        s"""{"outputRef":{"scriptHint":1234,"key":"${key.toHexString}"},"unlockScript":"abcd"}"""
      checkData(data, jsonRaw)
    }
  }

  it should "encode/decode Output with big amount" in {
    val address    = generateAddress()
    val addressStr = address.toBase58
    val amount     = U256.unsafe(15).mulUnsafe(U256.unsafe(Number.quintillion))
    val amountStr  = "15000000000000000000"
    val tokenId1   = Hash.hash("token1")
    val tokenId2   = Hash.hash("token2")
    val tokens     = AVector(Token(tokenId1, U256.unsafe(42)), Token(tokenId2, U256.unsafe(1000)))

    {
      val request = Output(amount, address, tokens, None)
      val jsonRaw = s"""
        |{
        |  "amount": "$amountStr",
        |  "address": "$addressStr",
        |  "tokens": [
        |    {
        |      "id": "${tokenId1.toHexString}",
        |      "amount": "42"
        |    },
        |    {
        |      "id": "${tokenId2.toHexString}",
        |      "amount": "1000"
        |    }
        |  ]
        |}
        """.stripMargin
      checkData(request, jsonRaw)
    }

    {
      val request = Output(amount, address, AVector.empty, Some(TimeStamp.unsafe(1234)))
      val jsonRaw = s"""
        |{
        |  "amount": "$amountStr",
        |  "address": "$addressStr",
        |  "tokens": [],
        |  "lockTime": 1234
        |}
        """.stripMargin
      checkData(request, jsonRaw)
    }
  }

  it should "encode/decode GetGroup" in {
    val address    = generateAddress()
    val addressStr = address.toBase58
    val request    = GetGroup(address)
    val jsonRaw    = s"""{"address":"$addressStr"}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode Balance" in {
    val response = Balance(100, 50, 1)
    val jsonRaw  = """{"balance":"100","lockedBalance":"50","utxoNum":1}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode Group" in {
    val response = Group(42)
    val jsonRaw  = """{"group":42}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode TxResult" in {
    val hash    = Hash.generate
    val result  = TxResult(hash, 0, 1)
    val jsonRaw = s"""{"txId":"${hash.toHexString}","fromGroup":0,"toGroup":1}"""
    checkData(result, jsonRaw)
  }

  it should "encode/decode BuildTransaction" in {
    val fromKey   = PublicKey.generate
    val toKey     = PublicKey.generate
    val toAddress = Address.p2pkh(networkType, toKey)

    {
      val transfer = BuildTransaction(fromKey, AVector(Destination(toAddress, 1)))
      val jsonRaw  = s"""
        |{
        |  "fromKey": "${fromKey.toHexString}",
        |  "destinations": [
        |    {
        |      "address": "${toAddress.toBase58}",
        |      "amount": "1"
        |    }
        |  ]
        |}
        """.stripMargin
      checkData(transfer, jsonRaw)
    }

    {
      val transfer = BuildTransaction(
        fromKey,
        AVector(Destination(toAddress, 1)),
        Some(TimeStamp.unsafe(1234)),
        Some(GasBox.unsafe(1)),
        Some(GasPrice(1))
      )
      val jsonRaw = s"""
        |{
        |  "fromKey": "${fromKey.toHexString}",
        |  "destinations": [
        |    {
        |      "address": "${toAddress.toBase58}",
        |      "amount": "1"
        |    }
        |  ],
        |  "lockTime": 1234,
        |  "gas": 1,
        |  "gasPrice": "1"
        |}
        """.stripMargin
      checkData(transfer, jsonRaw)
    }
  }

  it should "encode/decode BuildTransactionResult" in {
    val txId    = Hash.generate
    val result  = BuildTransactionResult("tx", txId, 1, 2)
    val jsonRaw = s"""{"unsignedTx":"tx","txId":"${txId.toHexString}","fromGroup":1,"toGroup":2}"""
    checkData(result, jsonRaw)
  }

  it should "encode/decode SendTransaction" in {
    val signature = Signature.generate
    val transfer  = SendTransaction("tx", signature)
    val jsonRaw =
      s"""{"unsignedTx":"tx","signature":"${signature.toHexString}"}"""
    checkData(transfer, jsonRaw)
  }

  it should "encode/decode TxStatus" in {
    val blockHash         = BlockHash.generate
    val status0: TxStatus = Confirmed(blockHash, 0, 1, 2, 3)
    val jsonRaw0 =
      s"""{"type":"confirmed","blockHash":"${blockHash.toHexString}","blockIndex":0,"chainConfirmations":1,"fromGroupConfirmations":2,"toGroupConfirmations":3}"""
    checkData(status0, jsonRaw0)

    checkData[PeerStatus](PeerStatus.Penalty(10), s"""{"type":"penalty","value":10}""")
    checkData[PeerStatus](
      PeerStatus.Banned(TimeStamp.unsafe(1L)),
      s"""{"type":"banned","until":1}"""
    )
  }

  it should "encode/decode PeerStatus" in {

    checkData(MemPooled: TxStatus, s"""{"type":"mem-pooled"}""")
    checkData(NotFound: TxStatus, s"""{"type":"not-found"}""")
  }

  it should "encode/decode MisbehaviorAction" in {
    checkData(
      MisbehaviorAction.Unban(AVector(inetAddress)),
      s"""{"type":"unban","peers":["127.0.0.1"]}"""
    )
  }

  it should "encode/decode BlockCandidate" in {
    val target = Target.onePhPerBlock

    val blockCandidate = BlockCandidate(
      1,
      0,
      hex"aaaa",
      target.value,
      hex"bbbbbbbbbb"
    )
    val jsonRaw =
      s"""{"fromGroup":1,"toGroup":0,"headerBlob":"aaaa","target":"${target.value}","txsBlob":"bbbbbbbbbb"}"""
    checkData(blockCandidate, jsonRaw)
  }

  it should "encode/decode BlockSolution" in {
    val blockSolution = BlockSolution(
      blockBlob = hex"bbbbbbbbbb",
      miningCount = U256.unsafe(1234)
    )
    val jsonRaw =
      s"""{"blockBlob":"bbbbbbbbbb","miningCount":"1234"}"""
    checkData(blockSolution, jsonRaw)
  }
}
