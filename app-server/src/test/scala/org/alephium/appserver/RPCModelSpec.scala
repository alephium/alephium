package org.alephium.appserver

import io.circe.{Codec, Decoder, Encoder}
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.{Assertion, EitherValues}

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.ED25519PublicKey
import org.alephium.util.{AlephiumSpec, AVector, Hex, TimeStamp}

class RPCModelSpec extends AlephiumSpec with EitherValues {
  val printer = org.alephium.rpc.CirceUtils.printer
  def show[T](t: T)(implicit encoder: Encoder[T]): String = {
    printer.print(t.asJson)
  }

  def entryDummy(i: Int): FetchEntry =
    FetchEntry(i.toString, TimeStamp.unsafe(i.toLong), i, i, i, AVector(i.toString))

  def parseAs[A](jsonRaw: String)(implicit A: Decoder[A]): A = {
    val json = parse(jsonRaw).right.value
    json.as[A].right.value
  }

  def checkData[T](data: T, jsonRaw: String)(implicit codec: Codec[T]): Assertion = {
    show(data) is jsonRaw
    parseAs[T](jsonRaw) is data
  }

  it should "encode/decode empty request" in {
    val request = FetchRequest(None)
    val jsonRaw = """{}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode request" in {
    val request = FetchRequest(Some(TimeStamp.unsafe(42L)))
    val jsonRaw = """{"from":42}"""
    checkData(request, jsonRaw)
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

  it should "encode/decode GetBalance" in {
    val address    = ED25519PublicKey.generate
    val addressHex = Hex.toHexString(address.bytes)
    val request    = GetBalance(addressHex, GetBalance.pkh)
    val jsonRaw    = s"""{"address":"$addressHex","type":"${GetBalance.pkh}"}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode Balance" in {
    val response = Balance(100, 1)
    val jsonRaw  = """{"balance":100,"utxoNum":1}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode Transfer" in {
    val transfer = Transfer("from", "pkh", "to", "pkh", 1, "key")
    val jsonRaw =
      """{"fromAddress":"from","fromType":"pkh","toAddress":"to","toType":"pkh","value":1,"fromPrivateKey":"key"}"""
    checkData(transfer, jsonRaw)
  }

  it should "encode/decode TransferResult" in {
    val result  = TransferResult("txId")
    val jsonRaw = """{"txId":"txId"}"""
    checkData(result, jsonRaw)
  }
}
