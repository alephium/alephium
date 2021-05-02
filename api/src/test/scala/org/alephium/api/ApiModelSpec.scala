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
import org.alephium.protocol.{BlockHash, Hash, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.model.{Address, BrokerInfo, CliqueId, CliqueInfo, NetworkType, Target}
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

  def generateAddress(): Address = Address.p2pkh(networkType, PublicKey.generate)

  def checkData[T: Reader: Writer](data: T, jsonRaw: String): Assertion = {
    write(data) is jsonRaw
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

  it should "encode/decode SelfClique" in {
    val cliqueId = CliqueId.generate
    val peerAddress =
      PeerAddress(InetAddress.getByName("127.0.0.1"), 9001, 9002)
    val selfClique = SelfClique(cliqueId, NetworkType.Mainnet, 18, AVector(peerAddress), true, 1, 2)
    val jsonRaw =
      s"""{"cliqueId":"${cliqueId.toHexString}","networkType":"mainnet","numZerosAtLeastInHash":18,"nodes":[{"address":"127.0.0.1","restPort":9001,"wsPort":9002}],"synced":true,"groupNumPerBroker":1,"groups":2}"""
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

    {
      val request = Output(amount, address, None)
      val jsonRaw = s"""{"amount":"$amountStr","address":"$addressStr"}"""
      checkData(request, jsonRaw)
    }

    {
      val request = Output(amount, address, Some(TimeStamp.unsafe(1234)))
      val jsonRaw = s"""{"amount":"$amountStr","address":"$addressStr","lockTime":1234}"""
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
      val transfer = BuildTransaction(fromKey, toAddress, None, 1)
      val jsonRaw =
        s"""{"fromKey":"${fromKey.toHexString}","toAddress":"${toAddress.toBase58}","value":"1"}"""
      checkData(transfer, jsonRaw)
    }

    {
      val transfer = BuildTransaction(fromKey, toAddress, Some(TimeStamp.unsafe(1234)), 1)
      val jsonRaw =
        s"""{"fromKey":"${fromKey.toHexString}","toAddress":"${toAddress.toBase58}","lockTime":1234,"value":"1"}"""
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

  it should "encode/decode BlockCandidate" in {
    val blockHash    = BlockHash.generate
    val depStateHash = Hash.generate
    val target       = Target.onePhPerBlock
    val ts           = TimeStamp.unsafe(1L)
    val txsHash      = Hash.generate

    val blockCandidate = BlockCandidate(
      AVector(blockHash),
      depStateHash,
      target.bits,
      ts,
      txsHash,
      AVector.empty
    )
    val jsonRaw =
      s"""{"deps":["${blockHash.toHexString}"],"depStateHash":"${depStateHash.toHexString}","target":"${Hex
        .toHexString(
          target.bits
        )}","blockTs":${ts.millis},"txsHash":"${txsHash.toHexString}","transactions":[]}"""
    checkData(blockCandidate, jsonRaw)
  }

  it should "encode/decode BlockSolution" in {
    val blockHash    = BlockHash.generate
    val depStateHash = Hash.generate
    val target       = Target.onePhPerBlock
    val ts           = TimeStamp.unsafe(1L)
    val txsHash      = Hash.generate

    val blockSolution = BlockSolution(
      AVector(blockHash),
      depStateHash,
      ts,
      1,
      1,
      U256.One,
      target.bits,
      U256.One,
      txsHash,
      AVector.empty
    )
    val jsonRaw =
      s"""{"blockDeps":["${blockHash.toHexString}"],"depStateHash":"${depStateHash.toHexString}","timestamp":${ts.millis},"fromGroup":1,"toGroup":1,"miningCount":"1","target":"${Hex
        .toHexString(
          target.bits
        )}","nonce":"1","txsHash":"${txsHash.toHexString}","transactions":[]}"""
    checkData(blockSolution, jsonRaw)
  }
}
