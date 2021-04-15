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

package org.alephium.rpc.model

import scala.concurrent.Future
import scala.util.{Success, Try}

import org.scalatest.{Assertion, EitherValues, Inside}

import org.alephium.json.Json._
import org.alephium.util.AlephiumSpec

class JsonRPCSpec extends AlephiumSpec with EitherValues with Inside {

  val dummy = Future.successful(JsonRPC.Response.Success(ujson.Null, 0))

  def handler(method: String): JsonRPC.Handler = Map((method, (_: JsonRPC.Request) => dummy))

  def parseNotification(jsonRaw: String): JsonRPC.Notification =
    read[JsonRPC.Notification](jsonRaw)

  def parseNotificationUnsafe(jsonRaw: String): JsonRPC.NotificationUnsafe = {
    read[JsonRPC.NotificationUnsafe](jsonRaw)
  }

  def parseRequest(jsonRaw: String): JsonRPC.RequestUnsafe =
    read[JsonRPC.RequestUnsafe](jsonRaw)

  def requestRunFailure(request: JsonRPC.RequestUnsafe, error: JsonRPC.Error): Assertion = {
    val result = request.runWith(handler("foobar"))
    result.value is Some(Success(JsonRPC.Response.Failure(error, Some(1))))
  }

  def notificationFailure(notif: JsonRPC.NotificationUnsafe, error: JsonRPC.Error): Assertion =
    notif.asNotification.left.value is error

  val jsonObjectEmpty   = ujson.Obj()
  val jsonObjectWihNull = ujson.Obj("foo" -> ujson.Null, "bar" -> ujson.Num(42))
  val jsonObject        = ujson.Obj("foo" -> ujson.Num(42))

  it should "encode request" in {
    val request = JsonRPC.Request("foobar", jsonObjectEmpty, 1)
    write(request) is """{"method":"foobar","params":{},"id":1,"jsonrpc":"2.0"}"""
  }

  it should "encode request - drop nulls " in {
    val request = JsonRPC.Request("foobar", jsonObjectWihNull, 1)
    write(request) is """{"method":"foobar","params":{"bar":42},"id":1,"jsonrpc":"2.0"}"""
  }

  it should "encode notification" in {
    val notification = JsonRPC.Notification("foobar", jsonObjectEmpty)
    write(notification) is """{"method":"foobar","params":{},"jsonrpc":"2.0"}"""
  }

  it should "encode notification - drop nulls" in {
    val notification = JsonRPC.Notification("foobar", jsonObjectWihNull)
    write(notification) is """{"method":"foobar","params":{"bar":42},"jsonrpc":"2.0"}"""
  }

  it should "encode response - success" in {
    val success: JsonRPC.Response = JsonRPC.Response.Success(ujson.Num(42), 1)
    write(success) is """{"result":42,"id":1,"jsonrpc":"2.0"}"""
  }

  it should "encode response - success - drop nulls" in {
    val success: JsonRPC.Response = JsonRPC.Response.Success(ujson.Null, 1)
    write(success) is """{"id":1,"jsonrpc":"2.0"}"""
  }

  it should "encode response - failure" in {
    val failure: JsonRPC.Response = JsonRPC.Response.Failure(JsonRPC.Error.InvalidRequest, Some(1))
    write(
      failure
    ) is """{"error":{"code":-32600,"message":"Invalid Request"},"id":1,"jsonrpc":"2.0"}"""
  }

  it should "encode response - failure - no id" in {
    val failure: JsonRPC.Response = JsonRPC.Response.Failure(JsonRPC.Error.InvalidRequest, None)
    write(failure) is """{"error":{"code":-32600,"message":"Invalid Request"},"jsonrpc":"2.0"}"""
  }

  it should "parse notification" in {
    val notification =
      parseNotification("""{ "method": "foobar", "params": {"foo": 42},"jsonrpc": "2.0"}""")
    notification.params is jsonObject
    notification.method is "foobar"
  }

  it should "parse notification with empty params" in {
    val notification = parseNotification("""{ "method": "foobar", "params": {},"jsonrpc": "2.0"}""")
    notification.params is jsonObjectEmpty
    notification.method is "foobar"
  }

  it should "parse notification - fail on wrong rpc version" in {
    val jsonRaw = """{ "method": "foobar", "params": {},"jsonrpc": "1.0"}"""
    val error   = parseNotificationUnsafe(jsonRaw).asNotification.left.value
    error is JsonRPC.Error.InvalidRequest
  }

  it should "parse notification - fail with no params" in {
    val jsonRaw = """{ "method": "foobar","jsonrpc": "2.0"}"""
    val error   = parseNotificationUnsafe(jsonRaw).asNotification.left.value
    error is JsonRPC.Error.InvalidParams
  }

  it should "parse notification - fail with null params" in {
    notificationFailure(
      parseNotificationUnsafe("""{ "method": "foobar", "params": null,"jsonrpc": "2.0"}"""),
      JsonRPC.Error.InvalidParams
    )
  }

  def checkRequestParams(jsonRaw: String, params: ujson.Value): Assertion = {
    val request = read[JsonRPC.RequestUnsafe](jsonRaw)

    request.jsonrpc is JsonRPC.version
    request.method is "foobar"
    request.params is params
    request.id is 1

    request.runWith(handler("foobar")) is dummy
  }

  it should "parse request with params" in {
    checkRequestParams(
      """{ "method": "foobar", "id": 1, "params": {"foo": 42},"jsonrpc": "2.0"}""",
      jsonObject
    )
  }

  it should "parse request with empty params" in {
    checkRequestParams(
      """{ "method": "foobar", "id": 1, "params": {},"jsonrpc": "2.0"}""",
      jsonObjectEmpty
    )
  }

  it should "parse request - fail on wrong rpc version" in {
    val request =
      parseRequest("""{ "method": "foobar", "id": 1, "params": {"foo": 42},"jsonrpc": "1.0"}""")
    request.jsonrpc is "1.0"
    requestRunFailure(request, JsonRPC.Error.InvalidRequest)
  }

  it should "parse request - fail with null params" in {
    requestRunFailure(
      parseRequest("""{ "method": "foobar", "id": 1, "params": null,"jsonrpc": "2.0"}"""),
      JsonRPC.Error.InvalidParams
    )
  }

  it should "parse request - fail with params as value" in {
    requestRunFailure(
      parseRequest("""{ "method": "foobar", "id": 1, "params": 42,"jsonrpc": "2.0"}"""),
      JsonRPC.Error.InvalidParams
    )
  }

  it should "parse response - success" in {
    val jsonRaw  = """{"result": 42, "id": 1,"jsonrpc": "2.0"}"""
    val response = read[JsonRPC.Response](jsonRaw)

    inside(response) { case JsonRPC.Response.Success(result, id) =>
      result is ujson.Num(42)
      id is 1
    }
  }

  it should "parse response - failure" in {
    val jsonRaw  = """{"error":{"code":42,"message":"foo"},"id":1,"jsonrpc":"2.0"}"""
    val response = read[JsonRPC.Response](jsonRaw)

    inside(response) { case JsonRPC.Response.Failure(error, id) =>
      error is JsonRPC.Error.apply(42, "foo")
      id is Some(1L)
    }
  }

  it should "parse success" in {
    val jsonRaw = """{"result": 42, "id": 1,"jsonrpc": "2.0"}"""
    val success = read[JsonRPC.Response.Success](jsonRaw)

    success.result is ujson.Num(42)
    success.id is 1
  }

  it should "parse success - fail on wrong rpc version" in {
    val jsonRaw = """{"result": 42, "id": 1,"jsonrpc": "1.0"}"""
    val error   = Try(read[JsonRPC.Response](jsonRaw)).toEither.left.value
    error.getMessage is "Invalid JSON-RPC version '1.0' at index 39"
  }
}
