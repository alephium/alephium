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

import java.math.BigInteger
import java.net.{InetAddress, InetSocketAddress}

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.EitherValues

import org.alephium.api.{model => api}
import org.alephium.api.UtilJson._
import org.alephium.api.model.{Address => _, _}
import org.alephium.json.Json._
import org.alephium.protocol._
import org.alephium.protocol.model.{AssetOutput => _, Balance => _, ContractOutput => _, _}
import org.alephium.protocol.vm.{
  GasBox,
  GasPrice,
  LockupScript,
  PublicKeyLike,
  StatefulContract,
  UnlockScript
}
import org.alephium.ralph.{Compiler, TypeSignatureFixture}
import org.alephium.serde.serialize
import org.alephium.util._
import org.alephium.util.Hex.HexStringSyntax

//scalastyle:off file.size.limit
class ApiModelSpec extends JsonFixture with ApiModelFixture with EitherValues with NumericHelpers {
  val defaultUtxosLimit: Int = 1024

  val zeroHash: String = BlockHash.zero.toHexString
  val ghostUncleHash: BlockHash =
    BlockHash.unsafe(Hex.unsafe("bdaf9dc514ce7d34b6474b8ca10a3dfb93ba997cb9d5ff1ea724ebe2af48abe5"))
  val lockupScript = LockupScript.asset("1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n").rightValue
  def entryDummy(i: Int): BlockEntry =
    BlockEntry(
      BlockHash.zero,
      TimeStamp.unsafe(i.toLong),
      i,
      i,
      i,
      AVector(BlockHash.zero),
      AVector.empty,
      ByteString.empty,
      1.toByte,
      Hash.zero,
      Hash.zero,
      ByteString.empty,
      AVector(GhostUncleBlockEntry(ghostUncleHash, Address.Asset(lockupScript)))
    )
  val dummyAddress = new InetSocketAddress("127.0.0.1", 9000)
  val dummyCliqueInfo =
    CliqueInfo.unsafe(
      CliqueId.generate,
      AVector(Option(dummyAddress)),
      AVector(dummyAddress),
      1,
      priKey
    )
  val dummyPeerInfo = BrokerInfo.unsafe(CliqueId.generate, 1, 3, dummyAddress)

  val apiKey = Hash.generate.toHexString

  val inetAddress = InetAddress.getByName("127.0.0.1")

  val compilerOptions = CompilerOptions(ignoreUnusedConstantsWarnings = Some(true))

  val contractState = ContractState(
    generateContractAddress(),
    StatefulContract.forSMT.toContract().rightValue,
    codeHash = Hash.zero,
    initialStateHash = Some(Hash.zero),
    immFields = AVector.empty,
    mutFields = AVector.empty,
    AssetState.from(ALPH.alph(1), AVector.empty)
  )

  def generateAddress(): Address.Asset = Address.p2pkh(PublicKey.generate)
  def generateContractAddress(): Address.Contract =
    Address.Contract(LockupScript.p2c("uomjgUz6D4tLejTkQtbNJMY8apAjTm1bgQf7em1wDV7S").rightValue)

  def blockEntryJson(blockEntry: BlockEntry): String = {
    s"""
       |{
       |  "hash":"${blockEntry.hash.toHexString}",
       |  "timestamp":${blockEntry.timestamp.millis},
       |  "chainFrom":${blockEntry.chainFrom},
       |  "chainTo":${blockEntry.chainTo},
       |  "height":${blockEntry.height},
       |  "deps":${write(blockEntry.deps.map(_.toHexString))},
       |  "transactions":${write(blockEntry.transactions)},
       |  "nonce":"${Hex.toHexString(blockEntry.nonce)}",
       |  "version":${blockEntry.version},
       |  "depStateHash":"${blockEntry.depStateHash.toHexString}",
       |  "txsHash":"${blockEntry.txsHash.toHexString}",
       |  "target":"${Hex.toHexString(blockEntry.target)}",
       |  "ghostUncles":${write(blockEntry.ghostUncles)}
       |}""".stripMargin
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

  it should "encode/decode GhostUncleBlockEntry" in {
    val entry = GhostUncleBlockEntry(ghostUncleHash, Address.Asset(lockupScript))
    val jsonRaw =
      """{"blockHash":"bdaf9dc514ce7d34b6474b8ca10a3dfb93ba997cb9d5ff1ea724ebe2af48abe5","miner":"1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n"}"""
    checkData(entry, jsonRaw)
  }

  it should "encode/decode empty BlocksPerTimeStampRange" in {
    val response = BlocksPerTimeStampRange(AVector.empty)
    val jsonRaw =
      """{"blocks":[]}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode BlocksPerTimeStampRange" in {
    val entries  = AVector.tabulate(2)(entryDummy)
    val response = BlocksPerTimeStampRange(AVector(entries))
    val jsonRaw =
      s"""{"blocks":[[${blockEntryJson(entries.head)},${blockEntryJson(entries.last)}]]}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode NodeInfo" in {
    val nodeInfo =
      NodeInfo(
        NodeInfo.BuildInfo("1.2.3", "07b7f3e044"),
        true,
        Some(dummyAddress)
      )
    val jsonRaw = {
      s"""
         |{
         |  "buildInfo": { "releaseVersion": "1.2.3", "commit": "07b7f3e044" },
         |  "upnp": true,
         |  "externalAddress": { "addr": "127.0.0.1", "port": 9000 }
         |}""".stripMargin
    }
    checkData(nodeInfo, jsonRaw)
  }

  it should "encode/decode NodeVersion" in {
    val nodeVersion = NodeVersion(ReleaseVersion(0, 0, 0))
    val jsonRaw =
      s"""
         |{
         |  "version": "v0.0.0"
         |}""".stripMargin
    checkData(nodeVersion, jsonRaw)
  }

  it should "encode/decode ChainParams" in {
    val chainParams = ChainParams(NetworkId.AlephiumMainNet, 18, 1, 2)
    val jsonRaw =
      s"""
         |{
         |  "networkId": 0,
         |  "numZerosAtLeastInHash": 18,
         |  "groupNumPerBroker": 1,
         |  "groups": 2
         |}""".stripMargin
    checkData(chainParams, jsonRaw)
  }

  it should "encode/decode SelfClique" in {
    val cliqueId = CliqueId.generate
    val peerAddress =
      PeerAddress(inetAddress, 9001, 9002, 9003)
    val selfClique =
      SelfClique(cliqueId, AVector(peerAddress), true, false)
    val jsonRaw =
      s"""
         |{
         |  "cliqueId": "${cliqueId.toHexString}",
         |  "nodes": [{"address":"127.0.0.1","restPort":9001,"wsPort":9002,"minerApiPort":9003}],
         |  "selfReady": true,
         |  "synced": false
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
    val request    = GetBalance(address)
    val jsonRaw    = s"""{"address":"$addressStr"}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode AssetInput" in {
    val key       = Hash.generate
    val outputRef = OutputRef(1234, key)
    val data      = AssetInput(outputRef, hex"abcd")
    val jsonRaw =
      s"""{"outputRef":{"hint":1234,"key":"${key.toHexString}"},"unlockScript":"abcd"}"""
    checkData(data, jsonRaw)
  }

  it should "encode/decode Token" in {
    val id     = TokenId.generate
    val amount = ALPH.oneAlph

    val token: Token = Token(id, amount)
    val jsonRaw =
      s"""{"id":"${id.toHexString}","amount":"${amount}"}"""

    checkData(token, jsonRaw)

    parseFail[Token](s"""{"id":"${id.toHexString}","amount":"1 ALPH"}""")
  }

  it should "encode/decode Output with big amount" in {
    val amount    = Amount(U256.unsafe(15).mulUnsafe(U256.unsafe(Number.quintillion)))
    val amountStr = "15000000000000000000"
    val tokenId1  = TokenId.hash("token1")
    val tokenId2  = TokenId.hash("token2")
    val tokens =
      AVector(Token(tokenId1, U256.unsafe(42)), Token(tokenId2, U256.unsafe(1000)))
    val hint = 1234
    val key  = hashGen.sample.get

    {
      val address         = generateContractAddress()
      val addressStr      = address.toBase58
      val request: Output = ContractOutput(hint, key, amount, address, tokens)
      val jsonRaw = s"""
                       |{
                       |  "type": "ContractOutput",
                       |  "hint": $hint,
                       |  "key": "${key.toHexString}",
                       |  "attoAlphAmount": "$amountStr",
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
      val address    = generateAddress()
      val addressStr = address.toBase58
      val request: Output =
        AssetOutput(
          hint,
          key,
          amount,
          address,
          AVector.empty,
          TimeStamp.unsafe(1234),
          ByteString.empty
        )
      val jsonRaw = s"""
                       |{
                       |  "type": "AssetOutput",
                       |  "hint": $hint,
                       |  "key": "${key.toHexString}",
                       |  "attoAlphAmount": "$amountStr",
                       |  "address": "$addressStr",
                       |  "tokens": [],
                       |  "lockTime": 1234,
                       |  "message": ""
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
    val amount = Amount(ALPH.alph(100))
    val locked = Amount(ALPH.alph(50))

    {
      info("with token balances")
      val tokenId1 = TokenId.hash("token1")
      val tokenId2 = TokenId.hash("token2")
      val tokens =
        AVector(Token(tokenId1, U256.unsafe(42)), Token(tokenId2, U256.unsafe(1000)))
      val tokenId3     = TokenId.hash("token3")
      val lockedTokens = AVector(Token(tokenId3, U256.unsafe(1)))
      val response =
        Balance(amount, amount.hint, locked, locked.hint, Some(tokens), Some(lockedTokens), 1)
      val jsonRaw =
        s"""{"balance":"100000000000000000000","balanceHint":"100 ALPH","lockedBalance":"50000000000000000000","lockedBalanceHint":"50 ALPH","tokenBalances":[{"id":"${tokenId1.toHexString}","amount":"42"},{"id":"${tokenId2.toHexString}","amount":"1000"}],"lockedTokenBalances":[{"id":"${tokenId3.toHexString}","amount":"1"}],"utxoNum":1}"""
      checkData(response, jsonRaw, dropWhiteSpace = false)
    }

    {
      info("without token balances")
      val response = Balance(amount, amount.hint, locked, locked.hint, None, None, 1)
      val jsonRaw =
        s"""{"balance":"100000000000000000000","balanceHint":"100 ALPH","lockedBalance":"50000000000000000000","lockedBalanceHint":"50 ALPH","utxoNum":1}"""
      checkData(response, jsonRaw, dropWhiteSpace = false)
    }
  }

  it should "encode/decode Group" in {
    val response = Group(42)
    val jsonRaw  = """{"group":42}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode TxResult" in {
    val hash    = TransactionId.generate
    val result  = SubmitTxResult(hash, 0, 1)
    val jsonRaw = s"""{"txId":"${hash.toHexString}","fromGroup":0,"toGroup":1}"""
    checkData(result, jsonRaw)
  }

  it should "encode/decode BuildTransfer" in {
    val fromPublicKey = PublicKey.generate
    val toKey         = PublicKey.generate
    val toAddress     = Address.p2pkh(toKey)

    {
      val transfer =
        BuildTransferTx(
          fromPublicKey.bytes,
          None,
          AVector(Destination(toAddress, Some(Amount(1))))
        )
      val jsonRaw = s"""
                       |{
                       |  "fromPublicKey": "${fromPublicKey.toHexString}",
                       |  "destinations": [
                       |    {
                       |      "address": "${toAddress.toBase58}",
                       |      "attoAlphAmount": "1"
                       |    }
                       |  ]
                       |}
        """.stripMargin

      checkData(transfer, jsonRaw)
    }

    {
      val transfer = BuildTransferTx(
        fromPublicKey.bytes,
        None,
        AVector(Destination(toAddress, Some(Amount(1)), None, Some(TimeStamp.unsafe(1234)))),
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
                       |      "attoAlphAmount": "1",
                       |      "lockTime": 1234
                       |    }
                       |  ],
                       |  "gasAmount": 1,
                       |  "gasPrice": "1"
                       |}
        """.stripMargin
      checkData(transfer, jsonRaw)
    }

    {
      val tokenId1 = TokenId.hash("tokenId1")

      val transfer = BuildTransferTx(
        fromPublicKey.bytes,
        None,
        AVector(
          Destination(
            toAddress,
            Some(Amount(1)),
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
                       |      "attoAlphAmount": "1",
                       |      "tokens": [
                       |        {
                       |          "id": "${tokenId1.toHexString}",
                       |          "amount": "10"
                       |        }
                       |      ],
                       |      "lockTime": 1234
                       |    }
                       |  ],
                       |  "gasAmount": 1,
                       |  "gasPrice": "1"
                       |}
        """.stripMargin
      checkData(transfer, jsonRaw)
    }

    {
      val tokenId1 = TokenId.hash("tokenId1")

      val transfer = BuildTransferTx(
        fromPublicKey.bytes,
        Some(BuildTxCommon.Default),
        AVector(
          Destination(
            toAddress,
            Some(Amount(1)),
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
                       |  "fromPublicKeyType": "default",
                       |  "destinations": [
                       |    {
                       |      "address": "${toAddress.toBase58}",
                       |      "attoAlphAmount": "1",
                       |      "tokens": [
                       |        {
                       |          "id": "${tokenId1.toHexString}",
                       |          "amount": "10"
                       |        }
                       |      ],
                       |      "lockTime": 1234
                       |    }
                       |  ],
                       |  "gasAmount": 1,
                       |  "gasPrice": "1"
                       |}
        """.stripMargin
      checkData(transfer, jsonRaw)
    }

    {
      val tokenId1 = TokenId.hash("tokenId1")
      val utxoKey1 = Hash.hash("utxo1")

      val transfer = BuildTransferTx(
        fromPublicKey.bytes,
        Some(BuildTxCommon.BIP340Schnorr),
        AVector(
          Destination(
            toAddress,
            Some(Amount(1)),
            Some(AVector(Token(tokenId1, U256.Ten))),
            Some(TimeStamp.unsafe(1234))
          )
        ),
        Some(AVector(OutputRef(1, utxoKey1))),
        Some(GasBox.unsafe(1)),
        Some(GasPrice(1))
      )
      val jsonRaw = s"""
                       |{
                       |  "fromPublicKey": "${fromPublicKey.toHexString}",
                       |  "fromPublicKeyType": "bip340-schnorr",
                       |  "destinations": [
                       |    {
                       |      "address": "${toAddress.toBase58}",
                       |      "attoAlphAmount": "1",
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
                       |      "key": "${utxoKey1.toHexString}"
                       |    }
                       |  ],
                       |  "gasAmount": 1,
                       |  "gasPrice": "1"
                       |}
        """.stripMargin
      checkData(transfer, jsonRaw)
    }
  }

  it should "encode/decode BuildChainedTx" in {
    val fromPublicKey = PublicKey.generate
    val toKey         = PublicKey.generate
    val toAddress     = Address.p2pkh(toKey)

    val transfer = BuildChainedTransferTx(
      BuildTransferTx(
        fromPublicKey.bytes,
        None,
        AVector(Destination(toAddress, Some(Amount(1))))
      )
    )
    val transferJson = s"""
                          |{
                          |  "type": "Transfer",
                          |  "value": {
                          |    "fromPublicKey": "${fromPublicKey.toHexString}",
                          |    "destinations": [
                          |      {
                          |        "address": "${toAddress.toBase58}",
                          |        "attoAlphAmount": "1"
                          |      }
                          |    ]
                          |  }
                          |}
        """.stripMargin

    checkData(transfer, transferJson)

    val deploy = BuildChainedDeployContractTx(
      BuildDeployContractTx(
        fromPublicKey = fromPublicKey.bytes,
        bytecode = ByteString(0, 0),
        issueTokenAmount = Some(Amount(1)),
        gasAmount = Some(GasBox.unsafe(1)),
        gasPrice = Some(GasPrice(1))
      )
    )
    val deployJson = s"""
                        |{
                        |  "type": "DeployContract",
                        |  "value": {
                        |    "fromPublicKey": "${fromPublicKey.toHexString}",
                        |    "bytecode": "0000",
                        |    "issueTokenAmount": "1",
                        |    "gasAmount": 1,
                        |    "gasPrice": "1"
                        |  }
                        |}
                        |""".stripMargin

    checkData(deploy, deployJson)

    val execute = BuildChainedExecuteScriptTx(
      BuildExecuteScriptTx(
        fromPublicKey = fromPublicKey.bytes,
        bytecode = ByteString(0, 0),
        gasAmount = Some(GasBox.unsafe(1)),
        gasPrice = Some(GasPrice(1))
      )
    )
    val executeJson = s"""
                         |{
                         |  "type": "ExecuteScript",
                         |  "value": {
                         |    "fromPublicKey": "${fromPublicKey.toHexString}",
                         |    "bytecode": "0000",
                         |    "gasAmount": 1,
                         |    "gasPrice": "1"
                         |  }
                         |}
                         |""".stripMargin

    checkData(execute, executeJson)

    val allBuildTxs = AVector[BuildChainedTx](transfer, deploy, execute)
    val allBuildTxsJson = s"""
                             |[
                             |  $transferJson,
                             |  $deployJson,
                             |  $executeJson
                             |]
                             |""".stripMargin
    checkData(allBuildTxs, allBuildTxsJson)
  }

  it should "encode/decode BuildChainedTxResult" in {
    val txId       = TransactionId.generate
    val gas        = GasBox.unsafe(1)
    val gasPrice   = GasPrice(1)
    val contractId = ContractId.generate

    val transfer = BuildChainedTransferTxResult(
      BuildSimpleTransferTxResult("tx", gas, gasPrice, txId, 1, 2)
    )
    val transferJson = s"""
                          |{
                          |  "type": "Transfer",
                          |  "value": {
                          |    "type": "BuildSimpleTransferTxResult",
                          |    "unsignedTx":"tx",
                          |    "gasAmount": 1,
                          |    "gasPrice": "1",
                          |    "txId":"${txId.toHexString}",
                          |    "fromGroup":1,
                          |    "toGroup":2
                          |  }
                          |}""".stripMargin

    checkData(transfer, transferJson)

    val deploy = BuildChainedDeployContractTxResult(
      BuildSimpleDeployContractTxResult(
        fromGroup = 2,
        toGroup = 2,
        unsignedTx = "0000",
        gasAmount = GasBox.unsafe(1),
        gasPrice = GasPrice(1),
        txId = txId,
        contractAddress = Address.contract(contractId)
      )
    )
    val deployJson =
      s"""
         |{
         |  "type": "DeployContract",
         |  "value": {
         |    "type": "BuildSimpleDeployContractTxResult",
         |    "fromGroup": 2,
         |    "toGroup": 2,
         |    "unsignedTx": "0000",
         |    "gasAmount":1,
         |    "gasPrice":"1",
         |    "txId": "${txId.toHexString}",
         |    "contractAddress": "${Address.contract(contractId).toBase58}"
         |  }
         |}
         |""".stripMargin

    checkData(deploy, deployJson)

    val address         = generateAddress()
    val contractAddress = generateContractAddress()
    val simulationResult = SimulationResult(
      AVector(
        AddressAssetState(
          contractAddress,
          model.minimalAlphInContract,
          Some(AVector(Token(TokenId.hash("token1"), ALPH.oneAlph)))
        )
      ),
      AVector(
        AddressAssetState(
          contractAddress,
          model.minimalAlphInContract,
          Some(AVector(Token(TokenId.hash("token2"), ALPH.oneAlph)))
        ),
        AddressAssetState(
          address,
          model.dustUtxoAmount,
          Some(AVector(Token(TokenId.hash("token1"), ALPH.oneAlph)))
        )
      )
    )
    val execute = BuildChainedExecuteScriptTxResult(
      BuildSimpleExecuteScriptTxResult(
        fromGroup = 1,
        toGroup = 1,
        unsignedTx = "0000",
        gasAmount = GasBox.unsafe(1),
        gasPrice = GasPrice(1),
        txId = txId,
        simulationResult
      )
    )
    val executeJson =
      s"""
         |{
         |  "type": "ExecuteScript",
         |  "value": {
         |    "type": "BuildSimpleExecuteScriptTxResult",
         |    "fromGroup": 1,
         |    "toGroup": 1,
         |    "unsignedTx": "0000",
         |    "gasAmount":1,
         |    "gasPrice":"1",
         |    "txId": "${txId.toHexString}",
         |    "simulationResult": {
         |     "contractInputs":[
         |       {
         |         "address": "$contractAddress",
         |         "attoAlphAmount":"100000000000000000",
         |         "tokens":[
         |            {
         |              "id":"2d11fd6c12435ffb07aaed4d190a505b621b927a5f6e51b61ce0ebe186397bdd",
         |              "amount":"1000000000000000000"
         |            }
         |         ]
         |       }
         |     ],
         |     "generatedOutputs":[
         |       {
         |         "address": "$contractAddress",
         |         "attoAlphAmount":"100000000000000000",
         |         "tokens":[
         |            {
         |              "id":"bd165d20bd063c7a023d22232a1e75bf46e904067f92b49323fe89fa0fd586bf",
         |              "amount":"1000000000000000000"
         |            }
         |          ]
         |       },
         |       {
         |         "address": "$address",
         |         "attoAlphAmount":"1000000000000000",
         |         "tokens":[
         |            {
         |              "id":"2d11fd6c12435ffb07aaed4d190a505b621b927a5f6e51b61ce0ebe186397bdd",
         |              "amount":"1000000000000000000"
         |            }
         |          ]
         |       }
         |     ]
         |   }
         |  }
         |}
         |""".stripMargin

    checkData(execute, executeJson)

    val allBuildTxResults = AVector[BuildChainedTxResult](transfer, deploy, execute)
    val allBuildTxResultsJson = s"""
                                   |[
                                   |  $transferJson,
                                   |  $deployJson,
                                   |  $executeJson
                                   |]
                                   |""".stripMargin
    checkData(allBuildTxResults, allBuildTxResultsJson)
  }

  it should "encode/decode BuildMultiAddressesTransaction" in {
    forAll(Gen.option(Gen.const(GasPrice(1))), Gen.option(Gen.const(BlockHash.generate))) {
      case (gasPrice, targetBlockHash) =>
        val fromPublicKeys = AVector.fill(10)(PublicKey.generate)
        val toKeys         = AVector.fill(10)(PublicKey.generate)
        val toAddresses    = toKeys.map(Address.p2pkh)
        val utxoKey1       = Hash.hash("utxo1")

        // `Destination` is already well tested, so we use simple data
        val destinations = toAddresses.map { toAddress =>
          Destination(toAddress, Some(Amount(1)))
        }

        val sources = fromPublicKeys.map { publicKey =>
          BuildMultiAddressesTransaction.Source(
            publicKey.bytes,
            destinations,
            Gen
              .option(Gen.oneOf(Seq(BuildTxCommon.Default, BuildTxCommon.BIP340Schnorr)))
              .sample
              .get,
            Gen.option(GasBox.unsafe(1)).sample.get,
            Gen.option(Gen.const(AVector(OutputRef(1, utxoKey1)))).sample.get
          )
        }

        val destinationsJson = destinations
          .map { dest =>
            s"""
               |{
               |  "address": "${dest.address.toBase58}",
               |  "attoAlphAmount": "${dest.attoAlphAmount.value}"
               |}
        """.stripMargin
          }
          .mkString("[", ",", "]")

        val sourcesJson = sources
          .map { source =>
            s"""
               |{
               |  "fromPublicKey": "${Hex.toHexString(source.fromPublicKey)}",
               |  "destinations": ${destinationsJson}
               |  ${source.fromPublicKeyType
                .map(keyType => s""","fromPublicKeyType": ${write(keyType)}""")
                .getOrElse("")}
               | ${source.gasAmount
                .map(gasAmount => s""","gasAmount": ${gasAmount.value}""")
                .getOrElse("")}
               |  ${source.utxos
                .map(utxos => s""","utxos": ${write(utxos)}""")
                .getOrElse("")}
               |}
        """.stripMargin
          }
          .mkString("[", ",", "]")

        val transfer =
          BuildMultiAddressesTransaction(sources, gasPrice, targetBlockHash)

        val jsonRaw =
          s"""
             |{
             |  "from": ${sourcesJson}
             |  ${gasPrice
              .map(price => s""","gasPrice": ${write(price)}""")
              .getOrElse("")}
             |  ${targetBlockHash
              .map(target => s""","targetBlockHash": ${write(target)}""")
              .getOrElse("")}
             |}
        """.stripMargin

        checkData(transfer, jsonRaw)
    }
  }

  it should "encode/decode BuildSweepMultisig" in {
    val fromAddress        = generateAddress()
    val fromPublicKeys     = AVector(PublicKey.generate, PublicKey.generate)
    val toAddress          = generateAddress()
    val maxAttoAlphPerUTXO = Amount(ALPH.oneAlph)
    val lockTime           = TimeStamp.now()
    val gasAmount          = GasBox.unsafe(1)
    val gasPrice           = GasPrice(1)
    val utxosLimit         = 3
    val targetBlockHash    = BlockHash.generate

    val buildSweep = BuildSweepMultisig(
      api.Address.fromProtocol(fromAddress),
      fromPublicKeys.map(_.bytes),
      None,
      None,
      toAddress,
      Some(maxAttoAlphPerUTXO),
      Some(lockTime),
      Some(gasAmount),
      Some(gasPrice),
      Some(utxosLimit),
      Some(targetBlockHash)
    )

    val jsonRaw = s"""
                     |{
                     |  "fromAddress": "${fromAddress.toBase58}",
                     |  "fromPublicKeys": ${write(fromPublicKeys.map(_.toHexString))},
                     |  "toAddress": "${toAddress.toBase58}",
                     |  "maxAttoAlphPerUTXO": "1000000000000000000",
                     |  "lockTime": ${lockTime.millis},
                     |  "gasAmount": 1,
                     |  "gasPrice": "1",
                     |  "utxosLimit": 3,
                     |  "targetBlockHash": "${targetBlockHash.toHexString}"
                     |}
        """.stripMargin
    checkData(buildSweep, jsonRaw)
  }

  it should "encode/decode BuildTransferTxResult" in {
    val txId     = TransactionId.generate
    val gas      = GasBox.unsafe(1)
    val gasPrice = GasPrice(1)
    val result   = BuildSimpleTransferTxResult("tx", gas, gasPrice, txId, 1, 2)
    val jsonRaw = s"""
                     |{
                     |  "type": "BuildSimpleTransferTxResult",
                     |  "unsignedTx":"tx",
                     |  "gasAmount": 1,
                     |  "gasPrice": "1",
                     |  "txId":"${txId.toHexString}",
                     |  "fromGroup":1,
                     |  "toGroup":2
                     |}""".stripMargin

    checkData(result, jsonRaw)
  }

  it should "encode/decode SweepAddressTransaction" in {
    val txId     = TransactionId.generate
    val gas      = GasBox.unsafe(1)
    val gasPrice = GasPrice(1)
    val result   = SweepAddressTransaction(txId, "tx", gas, gasPrice)
    val jsonRaw =
      s"""{"txId":"${txId.toHexString}","unsignedTx":"tx", "gasAmount": 1, "gasPrice": "1"}"""
    checkData(result, jsonRaw)
  }

  it should "encode/decode SubmitTransaction" in {
    val signature = Signature.generate
    val transfer  = SubmitTransaction("tx", signature)
    val jsonRaw =
      s"""{"unsignedTx":"tx","signature":"${signature.toHexString}"}"""
    checkData(transfer, jsonRaw)
  }

  it should "encode/decode PeerStatus" in {
    val blockHash         = BlockHash.generate
    val status0: TxStatus = Confirmed(blockHash, 0, 1, 2, 3)
    val jsonRaw0 =
      s"""{"type":"Confirmed","blockHash":"${blockHash.toHexString}","txIndex":0,"chainConfirmations":1,"fromGroupConfirmations":2,"toGroupConfirmations":3}"""
    checkData(status0, jsonRaw0)

    checkData[PeerStatus](PeerStatus.Penalty(10), s"""{"type":"Penalty","value":10}""")
    checkData[PeerStatus](
      PeerStatus.Banned(TimeStamp.unsafe(1L)),
      s"""{"type":"Banned","until":1}"""
    )
  }

  it should "encode/decode TxStatus" in {
    checkData(MemPooled(): TxStatus, s"""{"type":"MemPooled"}""")
    checkData(TxNotFound(): TxStatus, s"""{"type":"TxNotFound"}""")
  }

  it should "encode/decode MisbehaviorAction" in {
    checkData(
      MisbehaviorAction.Ban(AVector(inetAddress)),
      s"""{"type":"Ban","peers":["127.0.0.1"]}"""
    )
    checkData(
      MisbehaviorAction.Unban(AVector(inetAddress)),
      s"""{"type":"Unban","peers":["127.0.0.1"]}"""
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

  it should "encode/decode CompilerOptions" in {
    val options0 = CompilerOptions()
    checkData(options0, "{}")
    options0.toLangCompilerOptions().skipTests is false
    val options1 = CompilerOptions(skipTests = Some(true))
    checkData(options1, s"""{ "skipTests": true }""")
    options1.toLangCompilerOptions().skipTests is true
  }

  it should "encode/decode Compile.Script" in {
    {
      info("Without CompilerOptions")
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

    {
      info("With CompilerOptions")
      val compile =
        Compile.Script(code = "0000", compilerOptions = Some(compilerOptions))
      val jsonRaw =
        s"""
           |{
           |  "code": "0000",
           |  "compilerOptions": { "ignoreUnusedConstantsWarnings": true }
           |}
           |""".stripMargin
      checkData(compile, jsonRaw)
    }
  }

  it should "encode/decode Compile.Contract" in {
    {
      info("Without CompilerOptions")
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

    {
      info("With CompilerOptions")
      val compile =
        Compile.Contract(code = "0000", compilerOptions = Some(compilerOptions))
      val jsonRaw =
        s"""
           |{
           |  "code": "0000",
           |  "compilerOptions": { "ignoreUnusedConstantsWarnings": true }
           |}
           |""".stripMargin
      checkData(compile, jsonRaw)
    }
  }

  it should "encode/decode Compile.Project" in {
    {
      info("Without CompilerOptions")
      val project = Compile.Project(code = "0000")
      val jsonRaw =
        s"""
           |{
           |  "code": "0000"
           |}
           |""".stripMargin
      checkData(project, jsonRaw)
    }

    {
      info("With CompilerOptions")
      val project = Compile.Project(code = "0000", compilerOptions = Some(compilerOptions))
      val jsonRaw =
        s"""
           |{
           |  "code": "0000",
           |  "compilerOptions": { "ignoreUnusedConstantsWarnings": true }
           |}
           |""".stripMargin
      checkData(project, jsonRaw)
    }
  }

  it should "encode/decode BuildDeployContractTx" in {
    val publicKey = PublicKey.generate
    val buildDeployContractTx = BuildDeployContractTx(
      fromPublicKey = publicKey.bytes,
      bytecode = ByteString(0, 0),
      issueTokenAmount = Some(Amount(1)),
      gasAmount = Some(GasBox.unsafe(1)),
      gasPrice = Some(GasPrice(1))
    )
    val jsonRaw = s"""
                     |{
                     |  "fromPublicKey": "${publicKey.toHexString}",
                     |  "bytecode": "0000",
                     |  "issueTokenAmount": "1",
                     |  "gasAmount": 1,
                     |  "gasPrice": "1"
                     |}
                     |""".stripMargin

    checkData(buildDeployContractTx, jsonRaw)
  }

  it should "encode/decode BuildDeployContractTxResult" in {
    val txId       = TransactionId.generate
    val contractId = ContractId.generate
    val buildDeployContractTxResult = BuildSimpleDeployContractTxResult(
      fromGroup = 2,
      toGroup = 2,
      unsignedTx = "0000",
      gasAmount = GasBox.unsafe(1),
      gasPrice = GasPrice(1),
      txId = txId,
      contractAddress = Address.contract(contractId)
    )
    val jsonRaw =
      s"""
         |{
         |  "type": "BuildSimpleDeployContractTxResult",
         |  "fromGroup": 2,
         |  "toGroup": 2,
         |  "unsignedTx": "0000",
         |  "gasAmount":1,
         |  "gasPrice":"1",
         |  "txId": "${txId.toHexString}",
         |  "contractAddress": "${Address.contract(contractId).toBase58}"
         |}
         |""".stripMargin

    checkData(buildDeployContractTxResult, jsonRaw)
  }

  it should "encode/decode BuildExecuteScriptTx" in {
    val publicKey = PublicKey.generate
    val buildExecuteScriptTx = BuildExecuteScriptTx(
      fromPublicKey = publicKey.bytes,
      bytecode = ByteString(0, 0),
      gasAmount = Some(GasBox.unsafe(1)),
      gasPrice = Some(GasPrice(1))
    )
    val jsonRaw =
      s"""
         |{
         |  "fromPublicKey": "${publicKey.toHexString}",
         |  "bytecode": "0000",
         |  "gasAmount": 1,
         |  "gasPrice": "1"
         |}
         |""".stripMargin

    checkData(buildExecuteScriptTx, jsonRaw)
  }

  it should "encode/decode BuildExecuteScriptTxResult" in {
    val txId            = TransactionId.generate
    val address         = Address.p2pkh(PublicKey.generate)
    val contractAddress = generateContractAddress()
    val simulationResult = SimulationResult(
      AVector(
        AddressAssetState(
          contractAddress,
          model.minimalAlphInContract,
          Some(AVector(Token(TokenId.hash("token1"), U256.Two)))
        )
      ),
      AVector(
        AddressAssetState(
          contractAddress,
          model.minimalAlphInContract.addUnsafe(ALPH.oneAlph),
          Some(AVector(Token(TokenId.hash("token1"), U256.One)))
        ),
        AddressAssetState(
          address,
          model.dustUtxoAmount,
          Some(AVector(Token(TokenId.hash("token1"), U256.One)))
        )
      )
    )
    val buildExecuteScriptTxResult = BuildSimpleExecuteScriptTxResult(
      fromGroup = 1,
      toGroup = 1,
      unsignedTx = "0000",
      gasAmount = GasBox.unsafe(1),
      gasPrice = GasPrice(1),
      txId = txId,
      simulationResult
    )
    val jsonRaw =
      s"""
         |{
         |  "type": "BuildSimpleExecuteScriptTxResult",
         |  "fromGroup": 1,
         |  "toGroup": 1,
         |  "unsignedTx": "0000",
         |  "gasAmount":1,
         |  "gasPrice":"1",
         |  "txId": "${txId.toHexString}",
         |  "simulationResult": {
         |     "contractInputs":[
         |       {
         |         "address": "$contractAddress",
         |         "attoAlphAmount":"100000000000000000",
         |         "tokens":[
         |            {
         |              "id":"2d11fd6c12435ffb07aaed4d190a505b621b927a5f6e51b61ce0ebe186397bdd",
         |              "amount":"2"
         |            }
         |          ]
         |       }
         |     ],
         |     "generatedOutputs":[
         |       {
         |         "address": "$contractAddress",
         |         "attoAlphAmount":"1100000000000000000",
         |         "tokens":[
         |            {
         |              "id":"2d11fd6c12435ffb07aaed4d190a505b621b927a5f6e51b61ce0ebe186397bdd",
         |              "amount":"1"
         |            }
         |          ]
         |       },
         |       {
         |         "address": "$address",
         |         "attoAlphAmount":"1000000000000000",
         |         "tokens":[
         |            {
         |              "id":"2d11fd6c12435ffb07aaed4d190a505b621b927a5f6e51b61ce0ebe186397bdd",
         |              "amount":"1"
         |            }
         |          ]
         |       }
         |     ]
         |   }
         |}
         |""".stripMargin

    checkData(buildExecuteScriptTxResult, jsonRaw)
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

  it should "encode/decode TargetToHashrate" in {
    val target = Hash.generate.bytes

    val targetToHashrate =
      TargetToHashrate(target)
    val jsonRaw = s"""
                     |{
                     |  "target": "${Hex.toHexString(target)}"
                     |}
        """.stripMargin
    checkData(targetToHashrate, jsonRaw)
  }

  it should "encode/decode TargetToHashrate.Result" in {
    val hashrate = new BigInteger("10000000000000")

    val targetToHashrateResult =
      TargetToHashrate.Result(hashrate)
    val jsonRaw = s"""
                     |{
                     |  "hashrate": "${hashrate}"
                     |}
        """.stripMargin
    checkData(targetToHashrateResult, jsonRaw)
  }

  it should "encode/decode AssetState" in {
    val asset1   = AssetState(U256.unsafe(100))
    val jsonRaw1 = s"""{"attoAlphAmount": "100"}"""
    checkData(asset1, jsonRaw1)

    val asset2 = AssetState.from(U256.unsafe(100), AVector(Token(TokenId.zero, U256.unsafe(123))))
    val jsonRaw2 =
      s"""
         |{
         |  "attoAlphAmount": "100",
         |  "tokens":[{"id": "0000000000000000000000000000000000000000000000000000000000000000","amount":"123"}]
         |}""".stripMargin
    checkData(asset2, jsonRaw2)
  }

  it should "encode/decode ContractState" in {
    val u256     = ValU256(U256.MaxValue)
    val i256     = ValI256(I256.MaxValue)
    val bool     = Val.True
    val byteVec  = ValByteVec(U256.MaxValue.toBytes)
    val address1 = ValAddress(generateContractAddress())
    val state = ContractState(
      generateContractAddress(),
      StatefulContract.forSMT.toContract().rightValue,
      codeHash = Hash.zero,
      initialStateHash = Some(Hash.zero),
      immFields = AVector(u256, i256, bool),
      mutFields = AVector(byteVec, address1),
      AssetState.from(ALPH.alph(1), AVector(Token(TokenId.zero, ALPH.alph(2))))
    )
    val jsonRaw =
      s"""
         |{
         |  "address": "uomjgUz6D4tLejTkQtbNJMY8apAjTm1bgQf7em1wDV7S",
         |  "bytecode": "00010700000000000118",
         |  "codeHash": "0000000000000000000000000000000000000000000000000000000000000000",
         |  "initialStateHash": "0000000000000000000000000000000000000000000000000000000000000000",
         |  "immFields": [
         |    {
         |      "type": "U256",
         |      "value": "115792089237316195423570985008687907853269984665640564039457584007913129639935"
         |    },
         |    {
         |      "type": "I256",
         |      "value": "57896044618658097711785492504343953926634992332820282019728792003956564819967"
         |    },
         |    {
         |      "type": "Bool",
         |      "value": true
         |    }
         |  ],
         |  "mutFields": [
         |    {
         |      "type": "ByteVec",
         |      "value": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
         |    },
         |    {
         |      "type": "Address",
         |      "value": "uomjgUz6D4tLejTkQtbNJMY8apAjTm1bgQf7em1wDV7S"
         |    }
         |  ],
         |  "asset": {
         |    "attoAlphAmount": "1000000000000000000",
         |    "tokens": [
         |      {
         |        "id": "0000000000000000000000000000000000000000000000000000000000000000",
         |        "amount": "2000000000000000000"
         |      }
         |    ]
         |  }
         |}
         |""".stripMargin
    checkData(state, jsonRaw)
  }

  it should "compute diff of bytecode and debug bytecode" in new TypeSignatureFixture {
    CompileProjectResult.diffPatch("", "").value is ""
    CompileProjectResult.diffPatch("Hello", "Hello").value is ""

    val bytecode = Hex.toHexString(serialize(compiledContract.code))
    bytecode is "0901404201010707061dd38471018705ce00a000a001ce0261b413c40de0b6b3a7640000a9ce05ce0618180c17010ca100140017031400a10116011602160316041605160602"
    val debugBytecode = Hex.toHexString(serialize(compiledContract.debugCode))
    debugBytecode is "0901404701010707061ed38471018705ce00a000a001ce02617e01027878b413c40de0b6b3a7640000a9ce05ce0618180c17010ca100140017031400a10116011602160316041605160602"
    val diff = CompileProjectResult.diffPatch(bytecode, debugBytecode)
    diff.value is "=7-1+7=11-1+e=30+7e01027878=90"
    val patchedCode = CompileProjectResult.applyPatchUnsafe(bytecode, diff)
    patchedCode is debugBytecode
  }

  it should "encode/decode CompilerResult" in new TypeSignatureFixture {
    val result0 = CompileContractResult.from(compiledContract)
    val jsonRaw0 =
      s"""
         |{
         |  "version": "${ReleaseVersion.current}",
         |  "name": "Foo",
         |  "bytecode": "0901404201010707061dd38471018705ce00a000a001ce0261b413c40de0b6b3a7640000a9ce05ce0618180c17010ca100140017031400a10116011602160316041605160602",
         |  "bytecodeDebugPatch": "=7-1+7=11-1+e=30+7e01027878=90",
         |  "codeHash": "e9e468d5a564d0ae0b4595da0a6ae8557f3c32d696dab77430c51c540b20c23f",
         |  "codeHashDebug":"d855a602f75d5def58a531d0231f5debfd4c31292e28dc4b96f3be84498263a1",
         |  "fields": {
         |    "names": ["aa","bb","cc","dd","ee","ff", "gg"],
         |    "types": ["Bool", "U256", "I256", "ByteVec", "Address", "[[Bool;1];2]", "Account"],
         |    "isMutable": [false, true, false, true, false, false, false]
         |  },
         |  "functions": [
         |    {
         |      "name": "bar",
         |      "usePreapprovedAssets": true,
         |      "useAssetsInContract": true,
         |      "isPublic": true,
         |      "paramNames": ["a","b","c","d","e","f"],
         |      "paramTypes": ["Bool", "U256", "I256", "ByteVec", "Address", "[[Bool;1];2]"],
         |      "paramIsMutable": [false, true, false, true, false, false],
         |      "returnTypes": ["U256", "I256", "ByteVec", "Address", "[[Bool;1];2]"]
         |    }
         |  ],
         |  "constants": [
         |    {
         |      "name": "A",
         |      "value": {"type": "Bool", "value": true}
         |    }
         |  ],
         |  "enums": [
         |    {
         |      "name": "Color",
         |      "fields": [
         |        {
         |          "name": "Red",
         |          "value": {"type": "U256", "value": "0"}
         |        },
         |        {
         |          "name": "Blue",
         |          "value": {"type": "U256", "value": "1"}
         |        }
         |      ]
         |    }
         |  ],
         |  "events": [
         |    {
         |      "name": "Bar",
         |      "fieldNames":["a","b","d","e"],
         |      "fieldTypes": ["Bool", "U256", "ByteVec", "Address"]
         |    }
         |  ],
         |  "warnings": [
         |    "Found unused variable in Foo: bar.a",
         |    "Found unused map in Foo: map",
         |    "Found unused constant in Foo: A",
         |    "Found unused constant in Foo: Color.Blue",
         |    "Found unused constant in Foo: Color.Red",
         |    "Found unused field in Foo: cc",
         |    "Found unused field in Foo: ff"
         |  ],
         |  "maps": { "names": ["map"], "types": ["Map[U256,U256]"] }
         |}
         |""".stripMargin
    val jsonString = write(result0)
    jsonString.filter(!_.isWhitespace) is jsonRaw0.filter(!_.isWhitespace)
    jsonString.contains("\"maps\"") is true
    write(result0.copy(maps = None)).contains("\"maps\"") is false

    val result1 = CompileScriptResult.from(compiledScript)
    val jsonRaw1 =
      s"""
         |{
         |  "version": "${ReleaseVersion.current}",
         |  "name": "Foo",
         |  "bytecodeTemplate": "020103000000010201000909060f0c17011400170316071608181816011602160316041605160602",
         |  "bytecodeDebugPatch": "=26+1=1-1+7e01027878=52",
         |  "fields": {
         |    "names": ["aa","bb","cc","dd","ee"],
         |    "types": ["Bool", "U256", "I256", "ByteVec", "Address"],
         |    "isMutable": [false, false, false, false, false]
         |  },
         |  "functions": [
         |    {
         |      "name": "main",
         |      "usePreapprovedAssets": true,
         |      "useAssetsInContract": false,
         |      "isPublic": true,
         |      "paramNames": [],
         |      "paramTypes": [],
         |      "paramIsMutable": [],
         |      "returnTypes": []
         |    },
         |    {
         |      "name": "bar",
         |      "usePreapprovedAssets": false,
         |      "useAssetsInContract": false,
         |      "isPublic": true,
         |      "paramNames": ["a","b","c","d","e","f","g"],
         |      "paramTypes": ["Bool", "U256", "I256", "ByteVec", "Address", "[[Bool;1];2]", "Account"],
         |      "paramIsMutable": [false, true, false, true, false, false, false],
         |      "returnTypes": ["U256", "I256", "ByteVec", "Address", "[[Bool;1];2]"]
         |    }
         |  ],
         |  "warnings": [
         |    "Found unused variable in Foo: bar.a",
         |    "Found unused field in Foo: aa",
         |    "Found unused field in Foo: bb",
         |    "Found unused field in Foo: cc",
         |    "Found unused field in Foo: dd",
         |    "Found unused field in Foo: ee"
         |  ]
         |}
         |""".stripMargin
    write(result1).filter(!_.isWhitespace) is jsonRaw1.filter(!_.isWhitespace)

    val struct = CompileResult.StructSig.from(compiledStruct)
    val structJson =
      s"""
         |{
         |  "name": "Account",
         |  "fieldNames": ["amount", "id"],
         |  "fieldTypes": ["U256", "ByteVec"],
         |  "isMutable": [false, false]
         |}
         |""".stripMargin
    write(struct).filter(!_.isWhitespace) is structJson.filter(!_.isWhitespace)

    val result2 = CompileProjectResult(AVector(result0), AVector(result1), Some(AVector(struct)))
    val jsonRaw2 =
      s"""
         |{
         |  "contracts": [$jsonRaw0],
         |  "scripts": [$jsonRaw1],
         |  "structs": [$structJson]
         |}
         |""".stripMargin
    write(result2).filter(!_.isWhitespace) is jsonRaw2.filter(!_.isWhitespace)

    val result3 = CompileProjectResult(AVector(result0), AVector(result1), None)
    val jsonRaw3 =
      s"""
         |{
         |  "contracts": [$jsonRaw0],
         |  "scripts": [$jsonRaw1]
         |}
         |""".stripMargin
    write(result3).filter(!_.isWhitespace) is jsonRaw3.filter(!_.isWhitespace)
  }

  behavior of "TimeInterval"

  it should "validate fromTs and toTs" in {
    val ts0 = TimeStamp.unsafe(0)
    val ts1 = TimeStamp.unsafe(1)
    TimeInterval.validator(TimeInterval(ts0, ts0)).isEmpty is false
    TimeInterval.validator(TimeInterval(ts0, ts1)).isEmpty is true
    TimeInterval.validator(TimeInterval(ts1, ts0)).isEmpty is false
    TimeInterval.validator(TimeInterval(ts1, ts1)).isEmpty is false
  }

  it should "validate the time span" in {
    val timestamp = TimeStamp.now()
    val timespan  = Duration.ofHoursUnsafe(1)
    TimeInterval(timestamp, timestamp.plusMinutesUnsafe(61))
      .validateTimeSpan(timespan) is Left(
      ApiError.BadRequest(s"Time span cannot be greater than ${timespan}")
    )
    TimeInterval(timestamp, timestamp.plusMinutesUnsafe(60)).validateTimeSpan(timespan) isE ()
  }

  it should "encode/decode FixedAssetOutput" in {
    val jsonRaw =
      s"""
         |{
         |  "hint": -383063803,
         |  "key": "9f0e444c69f77a49bd0be89db92c38fe713e0963165cca12faf5712d7657120f",
         |  "attoAlphAmount": "1000000000000000000",
         |  "address": "111111111111111111111111111111111",
         |  "tokens": [],
         |  "lockTime": 0,
         |  "message": ""
         |}
         |""".stripMargin
    checkData(FixedAssetOutput.fromProtocol(assetOutput, TransactionId.zero, 0), jsonRaw)
  }

  it should "endcode/decode Output" in {
    val assetOutputJson =
      s"""
         |{
         |  "type": "AssetOutput",
         |  "hint": -383063803,
         |  "key": "9f0e444c69f77a49bd0be89db92c38fe713e0963165cca12faf5712d7657120f",
         |  "attoAlphAmount": "1000000000000000000",
         |  "address": "111111111111111111111111111111111",
         |  "tokens": [],
         |  "lockTime": 0,
         |  "message": ""
         |}
         |""".stripMargin
    checkData[Output](Output.from(assetOutput, TransactionId.zero, 0), assetOutputJson)

    val contractOutputJson =
      s"""
         |{
         |  "type": "ContractOutput",
         |  "hint": -383063804,
         |  "key": "9f0e444c69f77a49bd0be89db92c38fe713e0963165cca12faf5712d7657120f",
         |  "attoAlphAmount": "1000000000000000000",
         |  "address": "tgx7VNFoP9DJiFMFgXXtafQZkUvyEdDHT9ryamHJYrjq",
         |  "tokens": []
         |}
         |""".stripMargin
    checkData[Output](Output.from(contractOutput, TransactionId.zero, 0), contractOutputJson)
  }

  it should "encode/decode UnsignedTx" in {
    val unsignedTx = UnsignedTx.fromProtocol(unsignedTransaction)
    val jsonRaw =
      s"""
         |{
         |  "txId": "${unsignedTransaction.id.toHexString}",
         |  "version": ${unsignedTransaction.version},
         |  "networkId": ${unsignedTransaction.networkId.id},
         |  "scriptOpt": ${write(unsignedTransaction.scriptOpt.map(Script.fromProtocol))},
         |  "gasAmount": ${minimalGas.value},
         |  "gasPrice": "${nonCoinbaseMinGasPrice.value}",
         |  "inputs": ${write(unsignedTx.inputs)},
         |  "fixedOutputs": ${write(unsignedTx.fixedOutputs)}
         |}""".stripMargin

    checkData(unsignedTx, jsonRaw)
  }

  it should "encode/decode Transaction" in {
    val tx = api.Transaction.fromProtocol(transaction)
    val jsonRaw = s"""
                     |{
                     |  "unsigned": ${write(tx.unsigned)},
                     |  "scriptExecutionOk": ${tx.scriptExecutionOk},
                     |  "contractInputs": ${write(tx.contractInputs)},
                     |  "generatedOutputs": ${write(tx.generatedOutputs)},
                     |  "inputSignatures": ${write(tx.inputSignatures)},
                     |  "scriptSignatures": ${write(tx.scriptSignatures)}
                     |}""".stripMargin

    checkData(tx, jsonRaw)
  }

  it should "calc output ref correctly" in {
    val tx = api.Transaction.fromProtocol(transaction)
    tx.unsigned.fixedOutputs.zipWithIndex.foreach { case (output, index) =>
      output.key is transaction.fixedOutputRefs(index).key.value
    }
    tx.generatedOutputs.length is 2
    tx.generatedOutputs.zipWithIndex.foreach { case (output, index) =>
      val key = TxOutputRef.key(transaction.id, index + transaction.unsigned.fixedOutputs.length)
      output.key is key.value
    }
  }

  it should "encode/decode TransactionTemplate" in {
    val now = TimeStamp.now()
    val tx  = api.TransactionTemplate.fromProtocol(transactionTemplate, now)
    val jsonRaw = s"""
                     |{
                     |  "unsigned": ${write(tx.unsigned)},
                     |  "inputSignatures": ${write(tx.inputSignatures)},
                     |  "scriptSignatures": ${write(tx.scriptSignatures)},
                     |  "seenAt": ${now.millis}
                     |}""".stripMargin

    checkData(tx, jsonRaw)
  }

  it should "encode/decode UTXO" in {
    val tokenId = TokenId.generate
    val amount  = Amount(123)

    {
      val utxo = UTXO(OutputRef(1, Hash.zero), amount)
      val jsonRaw =
        s"""
           |{
           |  "ref": {"hint":1,"key":"0000000000000000000000000000000000000000000000000000000000000000"},
           |  "amount":"123"
           |}""".stripMargin
      checkData(utxo, jsonRaw)
    }

    {
      val utxo = UTXO.from(
        OutputRef(1, Hash.zero),
        amount,
        AVector(Token(tokenId, amount.value)),
        TimeStamp.zero,
        Hex.unsafe("FFFF")
      )
      val jsonRaw =
        s"""
           |{
           |  "ref": {"hint":1,"key":"0000000000000000000000000000000000000000000000000000000000000000"},
           |  "amount":"123",
           |  "tokens":[{"id":"${tokenId.toHexString}","amount":"123"}],
           |  "lockTime":0,
           |  "additionalData":"ffff"
           |}""".stripMargin
      checkData(utxo, jsonRaw)
    }
  }

  it should "encode/decode MultipleCallContractResult" in {
    val callResults = MultipleCallContractResult(
      AVector(
        CallContractSucceeded(
          AVector(ValU256(1)),
          100000,
          AVector(contractState),
          AVector.empty,
          AVector.empty,
          AVector.empty,
          AVector.empty
        ),
        CallContractFailed("InvalidContractMethodIndex")
      )
    )

    val jsonRaw =
      s"""
         |{
         |  "results": [
         |    {
         |      "type": "CallContractSucceeded",
         |      "returns": [{"type": "U256","value": "1"}],
         |      "gasUsed": 100000,
         |      "contracts": [
         |        {
         |          "address": "uomjgUz6D4tLejTkQtbNJMY8apAjTm1bgQf7em1wDV7S",
         |          "bytecode": "00010700000000000118",
         |          "codeHash": "0000000000000000000000000000000000000000000000000000000000000000",
         |          "initialStateHash": "0000000000000000000000000000000000000000000000000000000000000000",
         |          "immFields": [],
         |          "mutFields": [],
         |          "asset": {"attoAlphAmount": "1000000000000000000","tokens": []}
         |        }
         |      ],
         |      "txInputs": [],
         |      "txOutputs": [],
         |      "events": [],
         |      "debugMessages": []
         |    },
         |    {
         |      "type": "CallContractFailed",
         |      "error": "InvalidContractMethodIndex"
         |    }
         |  ]
         |}
         |""".stripMargin

    checkData(callResults, jsonRaw)
  }

  it should "encode/decode CallTxScript" in {
    val params = CallTxScript(
      group = 0,
      bytecode = Hex.unsafe("0011"),
      callerAddress = Some(Address.Asset(lockupScript)),
      worldStateBlockHash = Some(ghostUncleHash),
      txId = Some(TransactionId.zero),
      inputAssets = Some(AVector(TestInputAsset(Address.Asset(lockupScript), AssetState(1)))),
      interestedContracts = Some(AVector(generateContractAddress()))
    )

    val jsonRaw =
      s"""
         |{
         |  "group": 0,
         |  "bytecode": "0011",
         |  "callerAddress": "1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n",
         |  "worldStateBlockHash": "bdaf9dc514ce7d34b6474b8ca10a3dfb93ba997cb9d5ff1ea724ebe2af48abe5",
         |  "txId": "0000000000000000000000000000000000000000000000000000000000000000",
         |  "inputAssets": [
         |    {
         |      "address": "1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n",
         |      "asset": {
         |        "attoAlphAmount": "1"
         |      }
         |    }
         |  ],
         |  "interestedContracts": [
         |    "uomjgUz6D4tLejTkQtbNJMY8apAjTm1bgQf7em1wDV7S"
         |  ]
         |}
         |""".stripMargin

    checkData(params, jsonRaw)
  }

  it should "encode/decode CallTxScriptResult" in {
    val callResult = CallTxScriptResult(
      returns = AVector[Val](ValU256(1), ValBool(false)),
      gasUsed = 100000,
      contracts = AVector(contractState),
      txInputs = AVector.empty,
      txOutputs = AVector.empty,
      events = AVector.empty,
      debugMessages = AVector.empty
    )

    val jsonRaw =
      s"""
         |{
         |  "returns": [
         |    {
         |      "type": "U256",
         |      "value": "1"
         |    },
         |    {
         |      "type": "Bool",
         |      "value": false
         |    }
         |  ],
         |  "gasUsed": 100000,
         |  "contracts": [
         |    {
         |      "address": "uomjgUz6D4tLejTkQtbNJMY8apAjTm1bgQf7em1wDV7S",
         |      "bytecode": "00010700000000000118",
         |      "codeHash": "0000000000000000000000000000000000000000000000000000000000000000",
         |      "initialStateHash": "0000000000000000000000000000000000000000000000000000000000000000",
         |      "immFields": [],
         |      "mutFields": [],
         |      "asset": {
         |        "attoAlphAmount": "1000000000000000000",
         |        "tokens": []
         |      }
         |    }
         |  ],
         |  "txInputs": [],
         |  "txOutputs": [],
         |  "events": [],
         |  "debugMessages": []
         |}
         |""".stripMargin

    checkData(callResult, jsonRaw)
  }

  it should "get alph and token amounts" in {
    BuildTxCommon
      .getAlphAndTokenAmounts(None, None) isE (None, AVector.empty[(TokenId, U256)])
    BuildTxCommon.getAlphAndTokenAmounts(Some(Amount(U256.One)), None) isE
      (Some(U256.One), AVector.empty[(TokenId, U256)])

    val tokenId = TokenId.generate
    BuildTxCommon
      .getAlphAndTokenAmounts(None, Some(AVector(Token(tokenId, U256.One)))) isE
      (None, AVector(tokenId -> U256.One))
    BuildTxCommon.getAlphAndTokenAmounts(
      Some(Amount(U256.One)),
      Some(AVector(Token(tokenId, U256.One)))
    ) isE (Some(U256.One), AVector(tokenId -> U256.One))
    BuildTxCommon.getAlphAndTokenAmounts(
      Some(Amount(U256.One)),
      Some(
        AVector(
          Token(tokenId, U256.One),
          Token(TokenId.alph, U256.One),
          Token(TokenId.alph, U256.One)
        )
      )
    ) isE (Some(U256.unsafe(3)), AVector(tokenId -> U256.One))
    BuildTxCommon.getAlphAndTokenAmounts(
      Some(Amount(U256.One)),
      Some(
        AVector(
          Token(tokenId, U256.One),
          Token(TokenId.alph, U256.One),
          Token(TokenId.alph, U256.One),
          Token(tokenId, U256.One)
        )
      )
    ) isE (Some(U256.unsafe(3)), AVector(tokenId -> U256.Two))

    BuildTxCommon
      .getAlphAndTokenAmounts(
        Some(Amount(U256.MaxValue)),
        Some(AVector(Token(TokenId.alph, U256.One)))
      )
      .leftValue is "ALPH amount overflow"
    BuildTxCommon
      .getAlphAndTokenAmounts(
        None,
        Some(AVector(Token(tokenId, U256.MaxValue), Token(tokenId, U256.One)))
      )
      .leftValue is s"Token $tokenId amount overflow"
  }

  it should "get alph and token amounts while ignoring tokens with zero amounts" in {
    val tokenId0 = TokenId.generate
    val tokenId1 = TokenId.generate
    val tokenId2 = TokenId.generate

    BuildTxCommon.getAlphAndTokenAmounts(
      None,
      Some(
        AVector(Token(tokenId0, U256.Zero), Token(tokenId1, U256.Zero), Token(tokenId2, U256.One))
      )
    ) isE (None, AVector(tokenId2 -> U256.One))
  }

  it should "check the minimal amount deposit for contract creation" in {
    BuildTxCommon.getInitialAttoAlphAmount(None, HardFork.Leman) isE minimalAlphInContractPreRhone
    BuildTxCommon.getInitialAttoAlphAmount(
      Some(minimalAlphInContractPreRhone),
      HardFork.Leman
    ) isE minimalAlphInContractPreRhone
    BuildTxCommon
      .getInitialAttoAlphAmount(Some(minimalAlphInContractPreRhone - 1), HardFork.Leman)
      .leftValue is "Expect 1 ALPH deposit to deploy a new contract"

    BuildTxCommon.getInitialAttoAlphAmount(None, HardFork.Rhone) isE minimalAlphInContract
    BuildTxCommon.getInitialAttoAlphAmount(
      Some(minimalAlphInContract),
      HardFork.Rhone
    ) isE minimalAlphInContract
    BuildTxCommon
      .getInitialAttoAlphAmount(Some(minimalAlphInContract - 1), HardFork.Rhone)
      .leftValue is "Expect 0.1 ALPH deposit to deploy a new contract"
  }

  trait GrouplessModelFixture {
    val fromPublicKey           = PublicKeyLike.SecP256K1(PublicKey.generate)
    val fromLockupScript        = LockupScript.p2pk(fromPublicKey, GroupIndex.unsafe(0))
    val fromAddressWithGroup    = api.Address.from(fromLockupScript)
    val fromAddressWithoutGroup = api.Address.from(fromPublicKey)
  }

  it should "getLockPair for BuildExecuteScriptTx" in new GrouplessModelFixture {
    {
      val script =
        s"""
           |TxScript Main {
           |  assert!(1 == 1, 0)
           |}
      """.stripMargin
      val compiled = Compiler.compileTxScript(script).rightValue
      val bytecode = serialize(compiled)
      val request = BuildExecuteScriptTx(
        fromPublicKey.publicKey.bytes,
        fromPublicKeyType = Some(BuildTxCommon.GLSecP256K1),
        bytecode,
        group = None
      )
      request
        .getLockPair()
        .leftValue
        .detail is s"Can not determine group: request has no explicit group and no contract address can be derived from TxScript"
    }

    {
      val groupIndex = fromLockupScript.groupIndex
      val request = BuildExecuteScriptTx(
        fromPublicKey.publicKey.bytes,
        fromPublicKeyType = Some(BuildTxCommon.GLSecP256K1),
        ByteString(0, 0),
        group = Some(groupIndex)
      )
      request.getLockPair().rightValue is (fromLockupScript, UnlockScript.P2PK)
    }

    {
      val txId            = TransactionId.generate
      val contractId      = ContractId.from(txId, 0, GroupIndex.unsafe(0))
      val contractAddress = Address.contract(contractId)
      val script =
        s"""
           |TxScript Main() {
           |  Bar(#${contractId.toHexString}).bar()
           |}
           |
           |Contract Bar() {
           |  pub fn bar() -> () {}
           |}
    """.stripMargin
      val compiled = Compiler.compileTxScript(script).rightValue
      val bytecode = serialize(compiled)
      val request = BuildExecuteScriptTx(
        fromPublicKey.publicKey.bytes,
        fromPublicKeyType = Some(BuildTxCommon.GLSecP256K1),
        bytecode,
        group = None
      )
      request.getLockPair().rightValue is (LockupScript.p2pk(
        fromPublicKey,
        contractAddress.groupIndex
      ), UnlockScript.P2PK)
    }

    {
      val groupIndex = LockupScript.p2pkh(fromPublicKey.publicKey).groupIndex
      val request = BuildExecuteScriptTx(
        fromPublicKey.publicKey.bytes,
        fromPublicKeyType = None,
        ByteString(0, 0),
        group = Some(groupIndex)
      )
      request.getLockPair().rightValue is (LockupScript.p2pkh(fromPublicKey.publicKey), UnlockScript
        .p2pkh(fromPublicKey.publicKey))
      val invalidGroup = (groupIndex.value + 1) % groupConfig.groups
      request
        .copy(group = Some(GroupIndex.unsafe(invalidGroup)))
        .getLockPair()
        .leftValue
        .detail is s"Mismatch between group in request ($invalidGroup) and SecP256K1 public key: ${Hex
          .toHexString(fromPublicKey.publicKey.bytes)}"
    }
  }

  it should "getLockPair for BuildDeployContractTx" in new GrouplessModelFixture {
    {
      val request = BuildDeployContractTx(
        fromPublicKey.publicKey.bytes,
        fromPublicKeyType = Some(BuildTxCommon.GLSecP256K1),
        ByteString(0, 0),
        group = None
      )
      request
        .getLockPair()
        .leftValue
        .detail is s"Contract deployment using groupless address requires explicit group information"
    }

    {
      val groupIndex = fromLockupScript.groupIndex
      val request = BuildDeployContractTx(
        fromPublicKey.publicKey.bytes,
        fromPublicKeyType = Some(BuildTxCommon.GLSecP256K1),
        ByteString(0, 0),
        group = Some(groupIndex)
      )
      request.getLockPair().rightValue is (fromLockupScript, UnlockScript.P2PK)
    }

    {
      val groupIndex = LockupScript.p2pkh(fromPublicKey.publicKey).groupIndex
      val request = BuildDeployContractTx(
        fromPublicKey.publicKey.bytes,
        fromPublicKeyType = None,
        ByteString(0, 0),
        group = Some(groupIndex)
      )
      request.getLockPair().rightValue is (LockupScript.p2pkh(fromPublicKey.publicKey), UnlockScript
        .p2pkh(fromPublicKey.publicKey))
      val invalidGroup = (groupIndex.value + 1) % groupConfig.groups
      request
        .copy(group = Some(GroupIndex.unsafe(invalidGroup)))
        .getLockPair()
        .leftValue
        .detail is s"Mismatch between group in request ($invalidGroup) and SecP256K1 public key: ${Hex
          .toHexString(fromPublicKey.publicKey.bytes)}"
    }
  }

  it should "encode/decode groupless address" in {
    val publicKey = p2pkLockupGen(GroupIndex.unsafe(0)).sample.get.publicKey
    (0 until groupConfig.groups).foreach { groupIndex =>
      val lockupScript = LockupScript.p2pk(publicKey, GroupIndex.unsafe(groupIndex))
      val address      = Address.Asset(lockupScript)
      checkData[Val](
        ValAddress(address),
        s"""{"type": "Address", "value": "${address.toBase58}"}"""
      )
    }

    val defaultGroupIndex = publicKey.defaultGroup
    val address           = Address.Asset(LockupScript.p2pk(publicKey, defaultGroupIndex))
    read[Val](
      s"""{"type": "Address", "value": "${address.toBase58.dropRight(2)}"}"""
    ) is ValAddress(address)
    write(ValAddress(address)) is s"""{"type":"Address","value":"${address.toBase58}"}"""
  }

  it should "decode address from base58 string" in {
    import api.Address._
    import scala.reflect.ClassTag
    def pass[T: ClassTag](str: String) = {
      fromBase58(str).value.lockupScript is a[T]
    }

    def fail(str: String, error: String) = {
      fromBase58(str).leftValue.startsWith(error) is true
    }

    pass[CompleteLockupScript]("1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3")
    pass[CompleteLockupScript]("je9CrJD444xMSGDA2yr1XMvugoHuTc6pfYEaPYrKLuYa")
    pass[CompleteLockupScript]("22sTaM5xer7h81LzaGA2JiajRwHwECpAv9bBuFUH5rrnr")
    fail("1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1", "Invalid address")

    pass[HalfDecodedP2PK]("3ccJ8aEBYKBPJKuk6b9yZ1W1oFDYPesa3qQeM8v9jhaJtbSaueJ3L")
    pass[CompleteLockupScript]("3ccJ8aEBYKBPJKuk6b9yZ1W1oFDYPesa3qQeM8v9jhaJtbSaueJ3L:0")
    fail("bMEQ1jQgijED5jmCgunSNs5G1W87VX33ueywrpqemY7KVCEaDys", "Invalid p2pk address")

    pass[HalfDecodedP2HMPK]("Ce1C6bXL68C474bJY7DKYihKPAoM6GZaoCtSidBmMWCE4JGzdU")
    pass[CompleteLockupScript]("Ce1C6bXL68C474bJY7DKYihKPAoM6GZaoCtSidBmMWCE4JGzdU:0")
    fail("2iMUVF9XEf7TkCK1gAvfv9HrG4B7qWSDa93p5Xa8D6A85", "Invalid p2hmpk address")
    fail("Ce1C6bXL68C474bJY7DKYihKPAoM6GZaoCtSidBmMWCE4JGzdU1:0", "Invalid groupless address")
  }
}
