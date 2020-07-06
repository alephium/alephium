package org.alephium.rpc.model

import scala.concurrent.Future
import scala.util.Success

import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.{Assertion, EitherValues, Inside}

import org.alephium.rpc.CirceUtils
import org.alephium.util.AlephiumSpec

class JsonRPCSpec extends AlephiumSpec with EitherValues with Inside {
  def show[T](data: T)(implicit encoder: Encoder[T]): String = {
    CirceUtils.print(data.asJson)
  }

  val dummy = Future.successful(JsonRPC.Response.Success(Json.Null, 0))

  def handler(method: String): JsonRPC.Handler = Map((method, (_: JsonRPC.Request) => dummy))

  def parseAs[A: Decoder](jsonRaw: String): A = {
    val json = parse(jsonRaw).toOption.get
    json.as[A].toOption.get
  }

  def parseNotification(jsonRaw: String): JsonRPC.Notification =
    parseNotificationUnsafe(jsonRaw).asNotification.toOption.get

  def parseNotificationUnsafe(jsonRaw: String): JsonRPC.NotificationUnsafe =
    parseAs[JsonRPC.NotificationUnsafe](jsonRaw)

  def parseRequest(jsonRaw: String): JsonRPC.RequestUnsafe =
    parseAs[JsonRPC.RequestUnsafe](jsonRaw)

  def requestRunFailure(request: JsonRPC.RequestUnsafe, error: JsonRPC.Error): Assertion = {
    val result = request.runWith(handler("foobar"))
    result.value is Some(Success(JsonRPC.Response.Failure(error, Some(1))))
  }

  def notificationFailure(notif: JsonRPC.NotificationUnsafe, error: JsonRPC.Error): Assertion =
    notif.asNotification.left.value is error

  val jsonObjectEmpty   = JsonObject.empty.asJson
  val jsonObjectWihNull = JsonObject(("foo", Json.Null), ("bar", Json.fromInt(42))).asJson
  val jsonObject        = JsonObject(("foo", Json.fromInt(42))).asJson

  it should "encode request" in {
    val request = JsonRPC.Request("foobar", jsonObjectEmpty, 1)
    show(request) is """{"jsonrpc":"2.0","method":"foobar","params":{},"id":1}"""
  }

  it should "encode request - drop nulls " in {
    val request = JsonRPC.Request("foobar", jsonObjectWihNull, 1)
    show(request) is """{"jsonrpc":"2.0","method":"foobar","params":{"bar":42},"id":1}"""
  }

  it should "encode notification" in {
    val notification = JsonRPC.Notification("foobar", jsonObjectEmpty)
    show(notification) is """{"jsonrpc":"2.0","method":"foobar","params":{}}"""
  }

  it should "encode notification - drop nulls" in {
    val notification = JsonRPC.Notification("foobar", jsonObjectWihNull)
    show(notification) is """{"jsonrpc":"2.0","method":"foobar","params":{"bar":42}}"""
  }

  it should "encode response - success" in {
    val success: JsonRPC.Response = JsonRPC.Response.Success(Json.fromInt(42), 1)
    show(success) is """{"jsonrpc":"2.0","result":42,"id":1}"""
  }

  it should "encode response - success - drop nulls" in {
    val success: JsonRPC.Response = JsonRPC.Response.Success(Json.Null, 1)
    show(success) is """{"jsonrpc":"2.0","id":1}"""
  }

  it should "encode response - failure" in {
    val failure: JsonRPC.Response = JsonRPC.Response.Failure(JsonRPC.Error.InvalidRequest, Some(1))
    show(failure) is """{"jsonrpc":"2.0","error":{"code":-32600,"message":"Invalid Request"},"id":1}"""
  }

  it should "encode response - failure - no id" in {
    val failure: JsonRPC.Response = JsonRPC.Response.Failure(JsonRPC.Error.InvalidRequest, None)
    show(failure) is """{"jsonrpc":"2.0","error":{"code":-32600,"message":"Invalid Request"}}"""
  }

  it should "parse notification" in {
    val notification =
      parseNotification("""{"jsonrpc": "2.0", "method": "foobar", "params": {"foo": 42}}""")
    notification.params is jsonObject
    notification.method is "foobar"
  }

  it should "parse notification with empty params" in {
    val notification = parseNotification("""{"jsonrpc": "2.0", "method": "foobar", "params": {}}""")
    notification.params is jsonObjectEmpty
    notification.method is "foobar"
  }

  it should "parse notification - fail on wrong rpc version" in {
    val jsonRaw = """{"jsonrpc": "1.0", "method": "foobar", "params": {}}"""
    val error   = parseNotificationUnsafe(jsonRaw).asNotification.left.value
    error is JsonRPC.Error.InvalidRequest
  }

  it should "parse notification - fail with no params" in {
    val jsonRaw = """{"jsonrpc": "2.0", "method": "foobar"}"""
    val error   = parseNotificationUnsafe(jsonRaw).asNotification.left.value
    error is JsonRPC.Error.InvalidParams
  }

  it should "parse notification - fail with null params" in {
    notificationFailure(
      parseNotificationUnsafe("""{"jsonrpc": "2.0", "method": "foobar", "params": null}"""),
      JsonRPC.Error.InvalidParams
    )
  }

  def checkRequestParams(jsonRaw: String, params: Json): Assertion = {
    val request = parseAs[JsonRPC.RequestUnsafe](jsonRaw)

    request.jsonrpc is JsonRPC.version
    request.method is "foobar"
    request.params is params
    request.id is 1

    request.runWith(handler("foobar")) is dummy
  }

  it should "parse request with params" in {
    checkRequestParams(
      """{"jsonrpc": "2.0", "method": "foobar", "id": 1, "params": {"foo": 42}}""",
      jsonObject
    )
  }

  it should "parse request with empty params" in {
    checkRequestParams(
      """{"jsonrpc": "2.0", "method": "foobar", "id": 1, "params": {}}""",
      jsonObjectEmpty
    )
  }

  it should "parse request - fail on wrong rpc version" in {
    val request =
      parseRequest("""{"jsonrpc": "1.0", "method": "foobar", "id": 1, "params": {"foo": 42}}""")
    request.jsonrpc is "1.0"
    requestRunFailure(request, JsonRPC.Error.InvalidRequest)
  }

  it should "parse request - fail with null params" in {
    requestRunFailure(
      parseRequest("""{"jsonrpc": "2.0", "method": "foobar", "id": 1, "params": null}"""),
      JsonRPC.Error.InvalidParams
    )
  }

  it should "parse request - fail with params as value" in {
    requestRunFailure(
      parseRequest("""{"jsonrpc": "2.0", "method": "foobar", "id": 1, "params": 42}"""),
      JsonRPC.Error.InvalidParams
    )
  }

  it should "parse response - success" in {
    val jsonRaw  = """{"jsonrpc": "2.0", "result": 42, "id": 1}"""
    val response = parse(jsonRaw).toOption.get.as[JsonRPC.Response].toOption.get

    inside(response) {
      case JsonRPC.Response.Success(result, id) =>
        result is Json.fromInt(42)
        id is 1
    }
  }

  it should "parse response - failure" in {
    val jsonRaw  = """{"jsonrpc":"2.0","error":{"code":42,"message":"foo"},"id":1}"""
    val response = parse(jsonRaw).toOption.get.as[JsonRPC.Response].toOption.get

    inside(response) {
      case JsonRPC.Response.Failure(error, id) =>
        error is JsonRPC.Error.apply(42, "foo")
        id is Some(1L)
    }
  }

  it should "parse success" in {
    val jsonRaw = """{"jsonrpc": "2.0", "result": 42, "id": 1}"""
    val success = parse(jsonRaw).toOption.get.as[JsonRPC.Response.Success].toOption.get

    success.result is Json.fromInt(42)
    success.id is 1
  }

  it should "parse success - fail on wrong rpc version" in {
    val jsonRaw = """{"jsonrpc": "1.0", "result": 42, "id": 1}"""
    val error   = parse(jsonRaw).toOption.get.as[JsonRPC.Response].left.value
    error.message is "Invalid JSON-RPC version '1.0'"
  }
}
