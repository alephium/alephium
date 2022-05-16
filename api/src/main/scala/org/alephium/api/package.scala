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

package org.alephium

import scala.util.{Failure, Success, Try}

import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.Codec.JsonCodec
import sttp.tapir.DecodeResult.{Error, Value}

import org.alephium.io.{IOError, IOResult}
import org.alephium.json.Json._
import org.alephium.protocol.vm.ExeResult

//we need to redefine this, because `tapir-upickle` is depening only on `upickle.default`
package object api {
  type Try[T] = Either[ApiError[_ <: StatusCode], T]

  def notFound(error: String): ApiError[_ <: StatusCode]   = ApiError.NotFound(error)
  def badRequest(error: String): ApiError[_ <: StatusCode] = ApiError.BadRequest(error)
  def failed(error: String): ApiError[_ <: StatusCode] =
    ApiError.InternalServerError(error)
  val failedInIO: ApiError[_ <: StatusCode] =
    ApiError.InternalServerError("Failed in IO")
  def failedInIO(error: IOError): ApiError[_ <: StatusCode] =
    ApiError.InternalServerError(s"Failed in IO: $error")
  def failed[T](error: IOError): Try[T] = Left(failedInIO(error))

  def wrapResult[T](result: IOResult[T]): Try[T] = {
    result.left.map(failedInIO(_))
  }
  def wrapExeResult[T](result: ExeResult[T]): Try[T] = result match {
    case Left(Left(ioFailure))   => Left(failedInIO(ioFailure.error))
    case Left(Right(exeFailure)) => Left(failed(exeFailure.name))
    case Right(t)                => Right(t)
  }

  def alphJsonBody[T: ReadWriter: Schema]: EndpointIO.Body[String, T] =
    stringBodyUtf8AnyFormat(readWriterCodec[T])

  implicit def readWriterCodec[T: ReadWriter: Schema]: JsonCodec[T] =
    Codec.json[T] { s =>
      Try(read[T](s)) match {
        case Success(v) => Value(v)
        case Failure(e) => Error("decoding failure", e)
      }
    } { t => write(t) }

  def alphPlainTextBody: EndpointIO.Body[String, String] = {
    stringBodyUtf8AnyFormat(Codec.string)
  }
}
