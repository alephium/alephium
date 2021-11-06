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

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.{Assertion, EitherValues}

import org.alephium.api.UtilJson._
import org.alephium.api.model._
import org.alephium.json.Json._
import org.alephium.protocol._
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript}
import org.alephium.util._
import org.alephium.util.Hex.HexStringSyntax

class ApiModelSpec extends AlephiumSpec with ApiModelCodec with EitherValues with NumericHelpers {

  val zeroHash: String = BlockHash.zero.toHexString
  def entryDummy(i: Int): BlockEntry =
    BlockEntry(
      BlockHash.zero,
      TimeStamp.unsafe(i.toLong),
      i,
      i,
      i,
      AVector(BlockHash.zero),
      AVector.empty
    )
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

  val apiKey = Hash.generate.toHexString

  val inetAddress = InetAddress.getByName("127.0.0.1")

  def generateAddress(): Address.Asset = Address.p2pkh(PublicKey.generate)
  def generateContractAddress(): Address.Contract =
    Address.Contract(LockupScript.p2c("uomjgUz6D4tLejTkQtbNJMY8apAjTm1bgQf7em1wDV7S").get)
  def checkData[T: Reader: Writer](
      data: T,
      jsonRaw: String,
      dropWhiteSpace: Boolean = true
  ): Assertion = {
    write(data) is jsonRaw.filterNot(v => dropWhiteSpace && v.isWhitespace)
    read[T](jsonRaw) is data
  }

  def parseFail[A: Reader](jsonRaw: String): String = {
    scala.util.Try(read[A](jsonRaw)).toEither.swap.rightValue.getMessage
  }

  it should "encode/decode TimeStamp" in {
    checkData(TimeStamp.unsafe(0), "0")
    checkData(TimeStamp.unsafe(43850028L), "43850028")
    checkData(TimeStamp.unsafe(4385002872679507624L), "\"4385002872679507624\"")

    forAll(negLongGen) { long =>
      parseFail[TimeStamp](s"$long") is "expect positive timestamp at index 0"
    }
  }

  it should "encode/decode Amount.Hint" in {
    checkData(Amount.Hint(ALPH.oneAlph), """"1 ALPH"""", dropWhiteSpace = false)
    read[Amount.Hint](""""1000000000000000000"""") is Amount.Hint(ALPH.oneAlph)

    val alph = ALPH.alph(1234) / 1000
    checkData(Amount.Hint(alph), """"1.234 ALPH"""", dropWhiteSpace = false)
    read[Amount.Hint](""""1234000000000000000"""") is Amount.Hint(alph)

    val small = ALPH.alph(1234) / 1000000000
    checkData(Amount.Hint(small), """"0.000001234 ALPH"""", dropWhiteSpace = false)
    read[Amount.Hint](""""1234000000000"""") is Amount.Hint(small)

    parseFail[Amount.Hint](""""1 alph"""")
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
    val response = FetchResponse(AVector(AVector.tabulate(2)(entryDummy)))
    val jsonRaw =
      s"""{"blocks":[[{"hash":"$zeroHash","timestamp":0,"chainFrom":0,"chainTo":0,"height":0,"deps":["$zeroHash"],"transactions":[]},{"hash":"$zeroHash","timestamp":1,"chainFrom":1,"chainTo":1,"height":1,"deps":["$zeroHash"],"transactions":[]}]]}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode NodeInfo" in {
    val nodeInfo =
      NodeInfo(ReleaseVersion(0, 0, 0), NodeInfo.BuildInfo("1.2.3", "07b7f3e044"))
    val jsonRaw =
      s"""{"version":"v0.0.0","buildInfo":{"releaseVersion":"1.2.3","commit":"07b7f3e044"}}"""
    checkData(nodeInfo, jsonRaw)
  }

  it should "encode/decode SelfClique" in {
    val cliqueId = CliqueId.generate
    val peerAddress =
      PeerAddress(inetAddress, 9001, 9002, 9003)
    val selfClique =
      SelfClique(cliqueId, NetworkId.AlephiumMainNet, 18, AVector(peerAddress), true, false, 1, 2)
    val jsonRaw =
      s"""
         |{
         |  "cliqueId": "${cliqueId.toHexString}",
         |  "networkId": 0,
         |  "numZerosAtLeastInHash": 18,
         |  "nodes": [{"address":"127.0.0.1","restPort":9001,"wsPort":9002,"minerApiPort":9003}],
         |  "selfReady": true,
         |  "synced": false,
         |  "groupNumPerBroker": 1,
         |  "groups": 2
         |}""".stripMargin
    checkData(selfClique, jsonRaw)
  }

  it should "encode/decode NeighborPeers" in {
    val neighborCliques = NeighborPeers(AVector(dummyPeerInfo))
    val cliqueIdString  = dummyPeerInfo.cliqueId.toHexString
    def jsonRaw(cliqueId: String) =
      s"""{"peers":[{"cliqueId":"$cliqueId","brokerId":1,"brokerNum":3,"address":{"addr":"127.0.0.1","port":9000}}]}"""
    checkData(neighborCliques, jsonRaw(cliqueIdString))

    parseFail[NeighborPeers](jsonRaw("OOPS")) is "invalid clique id at index 98"
  }

  it should "encode/decode GetBalance" in {
    val address    = generateAddress()
    val addressStr = address.toBase58
    val request    = GetBalance(address, None)
    val jsonRaw    = s"""{"address":"$addressStr"}"""
    checkData(request, jsonRaw)

    val request2 = GetBalance(address, Some(10))
    val jsonRaw2 = s"""{"address":"$addressStr","utxosLimit":10}"""
    checkData(request2, jsonRaw2)
  }

  it should "encode/decode Input" in {
    val key       = Hash.generate
    val outputRef = OutputRef(1234, key)

    {
      val data: Input = Input.Contract(outputRef)
      val jsonRaw =
        s"""{"type":"contract","outputRef":{"hint":1234,"key":"${key.toHexString}"}}"""
      checkData(data, jsonRaw)
    }

    {
      val data: Input = Input.Asset(outputRef, hex"abcd")
      val jsonRaw =
        s"""{"type":"asset","outputRef":{"hint":1234,"key":"${key.toHexString}"},"unlockScript":"abcd"}"""
      checkData(data, jsonRaw)
    }
  }

  it should "encode/decode Token" in {
    val id     = Hash.generate
    val amount = ALPH.oneAlph

    val token: Token = Token(id, amount)
    val jsonRaw =
      s"""{"id":"${id.toHexString}","amount":"${amount}"}"""

    checkData(token, jsonRaw)

    parseFail[Token](s"""{"id":"${id.toHexString}","amount":"1 ALPH"}""")
  }

  it should "encode/decode Output with big amount" in {
    val address    = generateAddress()
    val addressStr = address.toBase58
    val amount     = Amount(U256.unsafe(15).mulUnsafe(U256.unsafe(Number.quintillion)))
    val amountStr  = "15000000000000000000"
    val tokenId1   = Hash.hash("token1")
    val tokenId2   = Hash.hash("token2")
    val tokens =
      AVector(Token(tokenId1, U256.unsafe(42)), Token(tokenId2, U256.unsafe(1000)))

    {
      val request: Output = Output.Contract(amount, address, tokens)
      val jsonRaw         = s"""
        |{
        |  "type": "contract",
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
      val request: Output =
        Output.Asset(amount, address, AVector.empty, TimeStamp.unsafe(1234), ByteString.empty)
      val jsonRaw = s"""
        |{
        |  "type": "asset",
        |  "amount": "$amountStr",
        |  "address": "$addressStr",
        |  "tokens": [],
        |  "lockTime": 1234,
        |  "additionalData": ""
        |}
        """.stripMargin
      checkData(request, jsonRaw)
    }
  }

  it should "encode/decode Tx" in {
    val hash = Hash.generate
    val tx   = Tx(hash, AVector.empty, AVector.empty, 1, U256.unsafe(100))
    val jsonRaw =
      s"""{"id":"${hash.toHexString}","inputs":[],"outputs":[],"gasAmount":1,"gasPrice":"100"}"""
    checkData(tx, jsonRaw)
  }

  it should "encode/decode GetGroup" in {
    val address    = generateAddress()
    val addressStr = address.toBase58
    val request    = GetGroup(address)
    val jsonRaw    = s"""{"address":"$addressStr"}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode Balance" in {
    val amount   = Amount(ALPH.alph(100))
    val locked   = Amount(ALPH.alph(50))
    val response = Balance(amount, amount.hint, locked, locked.hint, 1)
    val jsonRaw =
      """{"balance":"100000000000000000000","balanceHint":"100 ALPH","lockedBalance":"50000000000000000000","lockedBalanceHint":"50 ALPH","utxoNum":1}"""
    checkData(response, jsonRaw, dropWhiteSpace = false)
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
    val fromPublicKey = PublicKey.generate
    val toKey         = PublicKey.generate
    val toAddress     = Address.p2pkh(toKey)

    {
      val transfer =
        BuildTransaction(fromPublicKey, AVector(Destination(toAddress, Amount(1))))
      val jsonRaw = s"""
        |{
        |  "fromPublicKey": "${fromPublicKey.toHexString}",
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
        fromPublicKey,
        AVector(Destination(toAddress, Amount(1), None, Some(TimeStamp.unsafe(1234)))),
        None,
        Some(GasBox.unsafe(1)),
        Some(GasPrice(1))
      )
      val jsonRaw = s"""
        |{
        |  "fromPublicKey": "${fromPublicKey.toHexString}",
        |  "destinations": [
        |    {
        |      "address": "${toAddress.toBase58}",
        |      "amount": "1",
        |      "lockTime": 1234
        |    }
        |  ],
        |  "gas": 1,
        |  "gasPrice": "1"
        |}
        """.stripMargin
      checkData(transfer, jsonRaw)
    }

    {
      val tokenId1 = Hash.hash("tokenId1")

      val transfer = BuildTransaction(
        fromPublicKey,
        AVector(
          Destination(
            toAddress,
            Amount(1),
            Some(AVector(Token(tokenId1, U256.Ten))),
            Some(TimeStamp.unsafe(1234))
          )
        ),
        None,
        Some(GasBox.unsafe(1)),
        Some(GasPrice(1))
      )
      val jsonRaw = s"""
        |{
        |  "fromPublicKey": "${fromPublicKey.toHexString}",
        |  "destinations": [
        |    {
        |      "address": "${toAddress.toBase58}",
        |      "amount": "1",
        |      "tokens": [
        |        {
        |          "id": "${tokenId1.toHexString}",
        |          "amount": "10"
        |        }
        |      ],
        |      "lockTime": 1234
        |    }
        |  ],
        |  "gas": 1,
        |  "gasPrice": "1"
        |}
        """.stripMargin
      checkData(transfer, jsonRaw)
    }

    {
      val tokenId1 = Hash.hash("tokenId1")

      val transfer = BuildTransaction(
        fromPublicKey,
        AVector(
          Destination(
            toAddress,
            Amount(1),
            Some(AVector(Token(tokenId1, U256.Ten))),
            Some(TimeStamp.unsafe(1234))
          )
        ),
        None,
        Some(GasBox.unsafe(1)),
        Some(GasPrice(1))
      )
      val jsonRaw = s"""
        |{
        |  "fromPublicKey": "${fromPublicKey.toHexString}",
        |  "destinations": [
        |    {
        |      "address": "${toAddress.toBase58}",
        |      "amount": "1",
        |      "tokens": [
        |        {
        |          "id": "${tokenId1.toHexString}",
        |          "amount": "10"
        |        }
        |      ],
        |      "lockTime": 1234
        |    }
        |  ],
        |  "gas": 1,
        |  "gasPrice": "1"
        |}
        """.stripMargin
      checkData(transfer, jsonRaw)
    }

    {
      val tokenId1 = Hash.hash("tokenId1")
      val otxoKey1 = Hash.hash("utxo1")

      val transfer = BuildTransaction(
        fromPublicKey,
        AVector(
          Destination(
            toAddress,
            Amount(1),
            Some(AVector(Token(tokenId1, U256.Ten))),
            Some(TimeStamp.unsafe(1234))
          )
        ),
        Some(AVector(OutputRef(1, otxoKey1))),
        Some(GasBox.unsafe(1)),
        Some(GasPrice(1))
      )
      val jsonRaw = s"""
        |{
        |  "fromPublicKey": "${fromPublicKey.toHexString}",
        |  "destinations": [
        |    {
        |      "address": "${toAddress.toBase58}",
        |      "amount": "1",
        |      "tokens": [
        |        {
        |          "id": "${tokenId1.toHexString}",
        |          "amount": "10"
        |        }
        |      ],
        |      "lockTime": 1234
        |    }
        |  ],
        |  "utxos": [
        |    {
        |      "hint": 1,
        |      "key": "${otxoKey1.toHexString}"
        |    }
        |  ],
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

  it should "encode/decode SubmitTransaction" in {
    val signature = Signature.generate
    val transfer  = SubmitTransaction("tx", signature)
    val jsonRaw =
      s"""{"unsignedTx":"tx","signature":"${signature.toHexString}"}"""
    checkData(transfer, jsonRaw)
  }

  it should "encode/decode TxStatus" in {
    val blockHash         = BlockHash.generate
    val status0: TxStatus = Confirmed(blockHash, 0, 1, 2, 3)
    val jsonRaw0 =
      s"""{"type":"confirmed","blockHash":"${blockHash.toHexString}","txIndex":0,"chainConfirmations":1,"fromGroupConfirmations":2,"toGroupConfirmations":3}"""
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
    val target = Target.Max

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

  it should "encode/decode ApiKey" in {
    def alphaNumStrOfSizeGen(size: Int) = Gen.listOfN(size, Gen.alphaNumChar).map(_.mkString)
    val rawApiKeyGen = for {
      size      <- Gen.choose(32, 512)
      apiKeyStr <- alphaNumStrOfSizeGen(size)
    } yield apiKeyStr

    forAll(rawApiKeyGen) { rawApiKey =>
      val jsonApiKey = s""""$rawApiKey""""
      checkData(ApiKey.unsafe(rawApiKey), jsonApiKey)
    }

    val invalidRawApiKeyGen = for {
      size    <- Gen.choose(0, 31)
      invalid <- alphaNumStrOfSizeGen(size)
    } yield invalid

    forAll(invalidRawApiKeyGen) { invaildApiKey =>
      parseFail[ApiKey](
        s""""$invaildApiKey""""
      ) is s"Api key must have at least 32 characters at index 0"
    }
  }

  it should "encode/decode Compile.Script" in {
    val compile =
      Compile.Script(
        code = "0000"
      )
    val jsonRaw =
      s"""
         |{
         |  "code": "0000"
         |}
         |""".stripMargin
    checkData(compile, jsonRaw)
  }

  it should "encode/decode Compile.Contract" in {
    val compile =
      Compile.Contract(code = "0000")
    val jsonRaw =
      s"""
         |{
         |  "code": "0000"
         |}
         |""".stripMargin
    checkData(compile, jsonRaw)
  }

  it should "encode/decode BuildContract" in {
    val publicKey = PublicKey.generate
    val buildContract = BuildContract(
      fromPublicKey = publicKey,
      code = "0000",
      gas = Some(GasBox.unsafe(1)),
      gasPrice = Some(GasPrice(1)),
      state = Some("10u"),
      issueTokenAmount = Some(Amount(1))
    )
    val jsonRaw =
      s"""
         |{
         |  "fromPublicKey": "${publicKey.toHexString}",
         |  "code": "0000",
         |  "gas": 1,
         |  "gasPrice": "1",
         |  "state": "10u",
         |  "issueTokenAmount": "1"
         |}
         |""".stripMargin
    checkData(buildContract, jsonRaw)
  }

  it should "encode/decode BuildContractResult" in {
    val txId       = Hash.generate
    val contractId = Hash.generate
    val buildContractResult = BuildContractResult(
      unsignedTx = "0000",
      hash = txId,
      contractId = contractId,
      fromGroup = 2,
      toGroup = 3
    )
    val jsonRaw =
      s"""
         |{
         |  "unsignedTx": "0000",
         |  "hash": "${txId.toHexString}",
         |  "contractId": "${contractId.toHexString}",
         |  "fromGroup": 2,
         |  "toGroup": 3
         |}
         |""".stripMargin
    checkData(buildContractResult, jsonRaw)
  }

  it should "encode/decode BuildScript" in {
    val publicKey = PublicKey.generate
    val buildScript = BuildScript(
      fromPublicKey = publicKey,
      code = "0000",
      gas = Some(GasBox.unsafe(1)),
      gasPrice = Some(GasPrice(1))
    )
    val jsonRaw =
      s"""
         |{
         |  "fromPublicKey": "${publicKey.toHexString}",
         |  "code": "0000",
         |  "gas": 1,
         |  "gasPrice": "1"
         |}
         |""".stripMargin
    checkData(buildScript, jsonRaw)
  }

  it should "encode/decode BuildScriptResult" in {
    val txId = Hash.generate
    val buildScriptResult = BuildScriptResult(
      unsignedTx = "0000",
      hash = txId,
      fromGroup = 1,
      toGroup = 2
    )
    val jsonRaw =
      s"""
         |{
         |  "unsignedTx": "0000",
         |  "hash": "${txId.toHexString}",
         |  "fromGroup": 1,
         |  "toGroup": 2
         |}
         |""".stripMargin
    checkData(buildScriptResult, jsonRaw)
  }

  it should "encode/decode VerifySignature" in {
    val data      = Hash.generate.bytes
    val publicKey = PublicKey.generate
    val signature = Signature.generate

    val verifySignature =
      VerifySignature(data, signature, publicKey)
    val jsonRaw = s"""
        |{
        |  "data": "${Hex.toHexString(data)}",
        |  "signature": "${signature.toHexString}",
        |  "publicKey": "${publicKey.toHexString}"
        |}
        """.stripMargin
    checkData(verifySignature, jsonRaw)
  }

  it should "encode/decode Val.Bool" in {
    checkData[Val](Val.True, """{"type": "bool", "value": true}""")
    checkData[Val](Val.False, """{"type": "bool", "value": false}""")
  }

  it should "encode/decode Val.ByteVec" in {
    val bytes = Hash.generate.bytes
    checkData[Val](
      Val.ByteVec(bytes),
      s"""{"type": "bytevec", "value": "${Hex.toHexString(bytes)}"}"""
    )
  }

  it should "encode/decode Val.U256" in {
    checkData[Val](Val.U256(U256.MaxValue), s"""{"type": "u256", "value": "${U256.MaxValue}"}""")
  }

  it should "encode/decode Val.I256" in {
    checkData[Val](Val.I256(I256.MinValue), s"""{"type": "i256", "value": "${I256.MinValue}"}""")
  }

  it should "encode/decode Val.Address" in {
    val address = generateContractAddress()
    checkData[Val](
      Val.Address(address),
      s"""{"type": "address", "value": "${address.toBase58}"}"""
    )
  }

  it should "encode/decode ContractStateResult" in {
    val u256     = Val.U256(U256.MaxValue)
    val i256     = Val.I256(I256.MaxValue)
    val bool     = Val.True
    val byteVec  = Val.ByteVec(U256.MaxValue.toBytes)
    val address1 = Val.Address(generateContractAddress())
    val address2 = Val.Address(generateAddress())
    val jsonRaw  = s"""
        |{
        |  "fields": [
        |     {
        |       "type":"u256",
        |       "value": "${u256.value}"
        |     },
        |     {
        |       "type":"i256",
        |       "value": "${i256.value}"
        |     },
        |     {
        |       "type":"bool",
        |       "value": ${bool.value}
        |     },
        |     {
        |       "type":"bytevec",
        |       "value": "${Hex.toHexString(byteVec.value)}"
        |     },
        |     {
        |       "type":"address",
        |       "value": "${address1.value.toBase58}"
        |     },
        |     {
        |       "type":"address",
        |       "value": "${address2.value.toBase58}"
        |     }
        |   ]
        |}
        """.stripMargin
    checkData(ContractStateResult(AVector(u256, i256, bool, byteVec, address1, address2)), jsonRaw)
  }
}
