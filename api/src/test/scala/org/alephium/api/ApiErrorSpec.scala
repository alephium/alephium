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

import org.scalatest.{Assertion, EitherValues}

import org.alephium.api.ApiError._
import org.alephium.json.Json._
import org.alephium.util._

class ApiErrorSpec extends AlephiumSpec with EitherValues {

  def checkData[T: ReadWriter](data: T, jsonRaw: String): Assertion = {
    write(data) is jsonRaw
    read[T](jsonRaw) is data
  }

  def parseFail[A: Reader](jsonRaw: String): String = {
    scala.util.Try(read[A](jsonRaw)).toEither.swap.toOption.get.getMessage
  }

  it should "encode/decode BadRequest" in {
    val detail = "detail"
    val badRequest =
      BadRequest(detail)
    val jsonRaw = s"""{"detail":"$detail"}"""
    checkData(badRequest, jsonRaw)
  }

  it should "encode/decode InternalServerError" in {
    val detail = "detail"
    val badRequest =
      InternalServerError(detail)
    val jsonRaw = s"""{"detail":"$detail"}"""
    checkData(badRequest, jsonRaw)
  }

  it should "encode/decode ServiceUnavailable" in {
    val detail = "detail"
    val badRequest =
      ServiceUnavailable(detail)
    val jsonRaw = s"""{"detail":"$detail"}"""
    checkData(badRequest, jsonRaw)
  }

  it should "encode/decode Unauthorized" in {
    val detail = "detail"
    val badRequest =
      Unauthorized(detail)
    val jsonRaw = s"""{"detail":"$detail"}"""
    checkData(badRequest, jsonRaw)
  }

  it should "encode/decode NotFound" in {
    val resource = "id"
    val badRequest =
      NotFound(resource)
    val jsonRaw = s"""{"resource":"$resource","detail":"$resource not found"}"""
    checkData(badRequest, jsonRaw)
  }
}
