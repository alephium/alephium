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

import sttp.tapir._
import sttp.tapir.Codec.JsonCodec
import sttp.tapir.DecodeResult.{Error, Value}

import org.alephium.json.Json._

//we need to redefine this, because `tapir-upickle` is depening only on `upickle.default`
package object api {
  def alphJsonBody[T: ReadWriter: Schema]: EndpointIO.Body[String, T] =
    anyFromUtf8StringBody(readWriterCodec[T])

  implicit def readWriterCodec[T: ReadWriter: Schema]: JsonCodec[T] =
    Codec.json[T] { s =>
      Try(read[T](s)) match {
        case Success(v) => Value(v)
        case Failure(e) => Error("decoding failure", e)
      }
    } { t => write(t) }

  def alphPlainTextBody: EndpointIO.Body[String, String] = {
    anyFromUtf8StringBody(Codec.string)
  }
}
