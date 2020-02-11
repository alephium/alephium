package org.alephium.appserver

import io.circe.{Codec, Decoder, Encoder}
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.{Assertion, EitherValues}

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.ED25519PublicKey
import org.alephium.util.{AlephiumSpec, Hex, TimeStamp}

class RPCModelSpec extends AlephiumSpec with EitherValues {
  val printer = org.alephium.rpc.JsonRPCHandler.printer
  def show[T](t: T)(implicit encoder: Encoder[T]): String = {
    printer.print(t.asJson)
  }

  def entryDummy(i: Int): FetchEntry =
    FetchEntry(i.toString, TimeStamp.fromMillis(i.toLong), i, i, i, List(i.toString))

  def parseAs[A](jsonRaw: String)(implicit A: Decoder[A]): A = {
    val json = parse(jsonRaw).right.value
    json.as[A].right.value
  }

  def check[T](obj: T, jsonRaw: String)(implicit codec: Codec[T]): Assertion = {
    show(obj) is jsonRaw
    parseAs[T](jsonRaw) is obj
  }

  it should "encode/decode empty request" in {
    val request = FetchRequest(None)
    val jsonRaw = """{}"""
    check(request, jsonRaw)
  }

  it should "encode/decode request" in {
    val request = FetchRequest(Some(TimeStamp.fromMillis(42L)))
    val jsonRaw = """{"from":42}"""
    check(request, jsonRaw)
  }

  it should "encode/decode empty FetchResponse" in {
    val response = FetchResponse(Seq.empty)
    val jsonRaw  = """{"blocks":[]}"""
    check(response, jsonRaw)
  }

  it should "encode/decode FetchResponse" in {
    val response = FetchResponse((0 to 1).map(entryDummy))
    val jsonRaw =
      """{"blocks":[{"hash":"0","timestamp":0,"chainFrom":0,"chainTo":0,"height":0,"deps":["0"]},{"hash":"1","timestamp":1,"chainFrom":1,"chainTo":1,"height":1,"deps":["1"]}]}"""
    check(response, jsonRaw)
  }

  it should "encode/decode GetBalance" in {
    val address    = ED25519PublicKey.generate
    val addressHex = Hex.toHexString(address.bytes)
    val request    = GetBalance(addressHex, GetBalance.pkh)
    val jsonRaw    = s"""{"address":"$addressHex","type":"${GetBalance.pkh}"}"""
    check(request, jsonRaw)
  }

  it should "encode/decode Balance" in {
    val response = Balance(100, 1)
    val jsonRaw  = """{"balance":100,"utxoNum":1}"""
    check(response, jsonRaw)
  }
}
