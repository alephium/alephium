package org.alephium.appserver

import io.circe.Decoder
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.EitherValues

import org.alephium.util.{AlephiumSpec, TimeStamp}

class RPCModelSpec extends AlephiumSpec with EitherValues {

  def entryDummy(i: Int): RPCModel.FetchEntry =
    RPCModel.FetchEntry(i.toString, TimeStamp.fromMillis(i.toLong), i, i, i, List(i.toString))

  def parseAs[A](jsonRaw: String)(implicit A: Decoder[A]): A = {
    val json = parse(jsonRaw).right.value
    json.as[A].right.value
  }

  it should "encode response - empty" in {
    val request = RPCModel.FetchResponse(Nil)
    request.asJson.noSpaces is """{"blocks":[]}"""
  }

  it should "encode response" in {
    val request = RPCModel.FetchResponse((0 to 1).map(entryDummy).toList)
    request.asJson.noSpaces is """{"blocks":[{"hash":"0","timestamp":0,"chainFrom":0,"chainTo":0,"height":0,"deps":["0"]},{"hash":"1","timestamp":1,"chainFrom":1,"chainTo":1,"height":1,"deps":["1"]}]}"""
  }

  it should "parse request - empty" in {
    val request = parseAs[RPCModel.FetchRequest]("""{}""")
    request.from is None
  }

  it should "parse request" in {
    val request = parseAs[RPCModel.FetchRequest]("""{"from": "42"}""")
    request.from.get.millis is 42L
  }
}
