package org.alephium.appserver

import java.net.{InetAddress, InetSocketAddress}

import io.circe.{Codec, Decoder, Encoder}
import io.circe.parser._
import io.circe.syntax._
import org.scalacheck.Gen
import org.scalatest.{Assertion, EitherValues}

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.rpc.CirceUtils
import org.alephium.util.{AlephiumSpec, AVector, Duration, Hex, TimeStamp}

class RPCModelSpec extends AlephiumSpec with EitherValues {
  def show[T](t: T)(implicit encoder: Encoder[T]): String = {
    CirceUtils.print(t.asJson)
  }

  def entryDummy(i: Int): BlockEntry =
    BlockEntry(i.toString, TimeStamp.unsafe(i.toLong), i, i, i, AVector(i.toString))
  val dummyAddress    = new InetSocketAddress("127.0.0.1", 9000)
  val dummyCliqueInfo = CliqueInfo.unsafe(CliqueId.generate, AVector(dummyAddress), 1)

  val blockflowFetchMaxAge = Duration.unsafe(1000)

  implicit val rpcConfig: RPCConfig =
    RPCConfig(dummyAddress.getAddress, blockflowFetchMaxAge, askTimeout = Duration.zero)
  implicit val fetchRequestCodec = FetchRequest.codec

  def generateKeyHash(): String = {
    val address = ED25519PublicKey.generate
    Hex.toHexString(address.bytes)
  }

  def parseAs[A](jsonRaw: String)(implicit A: Decoder[A]): A = {
    val json = parse(jsonRaw).right.value
    json.as[A].right.value
  }

  def checkData[T](data: T, jsonRaw: String)(implicit codec: Codec[T]): Assertion = {
    show(data) is jsonRaw
    parseAs[T](jsonRaw) is data
  }

  def parseFail[A](jsonRaw: String)(implicit A: Decoder[A]): String = {
    parse(jsonRaw).right.value.as[A].left.value.message
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
    val peerAddress = PeerAddress(InetAddress.getByName("127.0.0.1"), Some(9000), Some(9001))
    val selfClique  = SelfClique(AVector(peerAddress), 1)
    val jsonRaw =
      s"""{"peers":[{"address":"127.0.0.1","rpcPort":9000,"wsPort":9001}],"groupNumPerBroker":1}"""
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
    val addressHex = generateKeyHash
    val request    = GetBalance(addressHex, GetBalance.pkh)
    val jsonRaw    = s"""{"address":"$addressHex","type":"${GetBalance.pkh}"}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode GetGroup" in {
    val addressHex = generateKeyHash
    val request    = GetGroup(addressHex)
    val jsonRaw    = s"""{"address":"$addressHex"}"""
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

  it should "encode/decode Transfer" in {
    val transfer = Transfer("from", "pkh", "to", "pkh", 1, "key")
    val jsonRaw =
      """{"fromAddress":"from","fromType":"pkh","toAddress":"to","toType":"pkh","value":1,"fromPrivateKey":"key"}"""
    checkData(transfer, jsonRaw)
  }

  it should "encode/decode TransferResult" in {
    val result  = TransferResult("txId", 0, 1)
    val jsonRaw = """{"txId":"txId","fromGroup":0,"toGroup":1}"""
    checkData(result, jsonRaw)
  }

  it should "encode/decode CreateTransaction" in {
    val transfer = CreateTransaction("from", "pkh", "to", "pkh", 1)
    val jsonRaw =
      """{"fromAddress":"from","fromType":"pkh","toAddress":"to","toType":"pkh","value":1}"""
    checkData(transfer, jsonRaw)
  }

  it should "encode/decode CreateTransactionResult" in {
    val result  = CreateTransactionResult("tx", "txHash")
    val jsonRaw = """{"unsignedTx":"tx","hash":"txHash"}"""
    checkData(result, jsonRaw)
  }
}
