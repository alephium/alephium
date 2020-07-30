package org.alephium.appserver

import java.net.{InetAddress, InetSocketAddress}

import io.circe.{Codec, Decoder, Encoder}
import io.circe.parser._
import io.circe.syntax._
import org.scalacheck.Gen
import org.scalatest.{Assertion, EitherValues}

import org.alephium.appserver.ApiModel._
import org.alephium.crypto.{ED25519PublicKey, ED25519Signature}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.protocol.vm.LockupScript
import org.alephium.rpc.CirceUtils
import org.alephium.serde.serialize
import org.alephium.util._

class ApiModelSpec extends AlephiumSpec with EitherValues with NumericHelpers {
  def show[T](t: T)(implicit encoder: Encoder[T]): String = {
    CirceUtils.print(t.asJson)
  }

  def entryDummy(i: Int): BlockEntry =
    BlockEntry(i.toString, TimeStamp.unsafe(i.toLong), i, i, i, AVector(i.toString), None)
  val dummyAddress    = new InetSocketAddress("127.0.0.1", 9000)
  val dummyCliqueInfo = CliqueInfo.unsafe(CliqueId.generate, AVector(dummyAddress), 1)

  val blockflowFetchMaxAge = Duration.unsafe(1000)

  val apiKey = Hash.generate.toHexString

  implicit val apiConfig: ApiConfig =
    ApiConfig(dummyAddress.getAddress,
              blockflowFetchMaxAge,
              askTimeout = Duration.zero,
              apiKeyHash = Hash.hash(apiKey))
  implicit val fetchRequestCodec = FetchRequest.codec

  def generateKeyHash(): String = {
    val address = ED25519PublicKey.generate
    Hex.toHexString(address.bytes)
  }

  def generateP2pkh(): Address = {
    LockupScript.p2pkh(ED25519PublicKey.generate)
  }

  def parseAs[A](jsonRaw: String)(implicit A: Decoder[A]): A = {
    val json = parse(jsonRaw).toOption.get
    json.as[A].toOption.get
  }

  def checkData[T](data: T, jsonRaw: String)(implicit codec: Codec[T]): Assertion = {
    show(data) is jsonRaw
    parseAs[T](jsonRaw) is data
  }

  def parseFail[A](jsonRaw: String)(implicit A: Decoder[A]): String = {
    parse(jsonRaw).toOption.get.as[A].left.value.message
  }

  it should "encode/decode TimeStamp" in {
    import TimeStampCodec._

    checkData(TimeStamp.unsafe(0), "0")

    forAll(Gen.posNum[Long]) { long =>
      val timestamp = TimeStamp.unsafe(long)
      checkData(timestamp, s"$long")
    }

    forAll(Gen.negNum[Long]) { long =>
      parseFail[TimeStamp](s"$long") is "expect positive timestamp"
    }
  }

  it should "encode/decode FetchRequest" in {
    val request =
      FetchRequest(TimeStamp.unsafe(1L), TimeStamp.unsafe(42L))
    val jsonRaw = """{"fromTs":1,"toTs":42}"""
    checkData(request, jsonRaw)
  }

  it should "validate FetchRequest" in {
    parseFail[FetchRequest]("""{"fromTs":42,"toTs":1}""") is "`toTs` cannot be before `fromTs`"
    parseFail[FetchRequest]("""{"fromTs":1,"toTs":100000}""") is s"interval cannot be greater than $blockflowFetchMaxAge"
    parseFail[FetchRequest]("""{}""") is s"Attempt to decode value on failed cursor"
  }

  it should "encode/decode empty FetchResponse" in {
    val response = FetchResponse(Seq.empty)
    val jsonRaw =
      """{"blocks":[]}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode FetchResponse" in {
    val response = FetchResponse((0 to 1).map(entryDummy))
    val jsonRaw =
      """{"blocks":[{"hash":"0","timestamp":0,"chainFrom":0,"chainTo":0,"height":0,"deps":["0"]},{"hash":"1","timestamp":1,"chainFrom":1,"chainTo":1,"height":1,"deps":["1"]}]}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode SelfClique" in {
    val cliqueId    = CliqueId.generate
    val peerAddress = PeerAddress(InetAddress.getByName("127.0.0.1"), Some(9000), Some(9001))
    val selfClique  = SelfClique(cliqueId, AVector(peerAddress), 1)
    val jsonRaw =
      s"""{"cliqueId":"${cliqueId.toHexString}","peers":[{"address":"127.0.0.1","rpcPort":9000,"wsPort":9001}],"groupNumPerBroker":1}"""
    checkData(selfClique, jsonRaw)
  }

  it should "encode/decode NeighborCliques" in {
    val neighborCliques = NeighborCliques(AVector(dummyCliqueInfo))
    val cliqueIdString  = dummyCliqueInfo.id.toHexString
    def jsonRaw(cliqueId: String) =
      s"""{"cliques":[{"id":"$cliqueId","peers":[{"addr":"127.0.0.1","port":9000}],"groupNumPerBroker":1}]}"""
    checkData(neighborCliques, jsonRaw(cliqueIdString))

    parseFail[NeighborCliques](jsonRaw("OOPS")) is "invalid clique id"
  }

  it should "encode/decode GetBalance" in {
    val address    = generateP2pkh()
    val addressStr = Base58.encode(serialize(address))
    val request    = GetBalance(address)
    val jsonRaw    = s"""{"address":"$addressStr"}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode GetGroup" in {
    val address    = generateP2pkh()
    val addressStr = Base58.encode(serialize(address))
    val request    = GetGroup(address)
    val jsonRaw    = s"""{"address":"$addressStr"}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode Balance" in {
    val response = Balance(100, 1)
    val jsonRaw  = """{"balance":100,"utxoNum":1}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode Group" in {
    val response = Group(42)
    val jsonRaw  = """{"group":42}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode TxResult" in {
    val result  = TxResult("txId", 0, 1)
    val jsonRaw = """{"txId":"txId","fromGroup":0,"toGroup":1}"""
    checkData(result, jsonRaw)
  }

  it should "encode/decode CreateTransaction" in {
    val fromKey   = ED25519PublicKey.generate
    val toKey     = ED25519PublicKey.generate
    val toAddress = LockupScript.p2pkh(toKey)
    val transfer  = CreateTransaction(fromKey, toAddress, 1)
    val jsonRaw =
      s"""{"fromKey":"${fromKey.toHexString}","toAddress":"${toAddress.toBase58}","value":1}"""
    checkData(transfer, jsonRaw)
  }

  it should "encode/decode CreateTransactionResult" in {
    val result  = CreateTransactionResult("tx", "txHash")
    val jsonRaw = """{"unsignedTx":"tx","hash":"txHash"}"""
    checkData(result, jsonRaw)
  }

  it should "encode/decode SendTransaction" in {
    val signature = ED25519Signature.generate
    val transfer  = SendTransaction("tx", signature)
    val jsonRaw =
      s"""{"tx":"tx","signature":"${signature.toHexString}"}"""
    checkData(transfer, jsonRaw)
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
      parseFail[ApiKey](s""""$invaildApiKey"""") is s"Api key must have at least 32 characters"
    }
  }
}
