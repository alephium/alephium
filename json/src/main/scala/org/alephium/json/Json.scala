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

package org.alephium.json

import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

import ujson.Readable.fromTransformer
import ujson.StringParser

// scalastyle:off null
object Json extends upickle.AttributeTagged {
  override val tagName = "type"

  private val isWindows = System.getProperty("os.name").contains("Windows")

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitConversion"))
  implicit def fromString(s: String): fromTransformer[String] = {
    val cleanS = if (isWindows) {
      s.replaceAll("\r", "")
    } else {
      s
    }
    new fromTransformer(cleanS, StringParser)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  implicit override def OptionWriter[T: Writer]: Writer[Option[T]] =
    implicitly[Writer[T]].comap[Option[T]] {
      case None    => null.asInstanceOf[T]
      case Some(x) => x
    }

  implicit override def OptionReader[T: Reader]: Reader[Option[T]] = {
    new Reader.Delegate[Any, Option[T]](implicitly[Reader[T]].map(Some(_))) {
      override def visitNull(index: Int) = None
    }
  }

  def readOpt[A: Reader](json: => ujson.Value): Option[A] = {
    Try(read[A](json)) match {
      case Success(a)                                   => Some(a)
      case Failure(_: java.util.NoSuchElementException) => None
      case Failure(error)                               => throw error
    }
  }

  def dropNullValues(json: ujson.Value): ujson.Value = {
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def rec(json: ujson.Value): Option[ujson.Value] = {
      json match {
        case ujson.Null =>
          None
        case obj: ujson.Obj =>
          val newValues = obj.value.flatMap { case (key, value) =>
            rec(value).map(key -> _)
          }
          Some(ujson.Obj.from(newValues))

        case arr: ujson.Arr =>
          val newValues = arr.value.flatMap { value =>
            rec(value)
          }
          Some(ujson.Arr.from(newValues))

        case x => Some(x)
      }
    }
    json match {
      case ujson.Null => ujson.Null
      case other =>
        rec(other) match {
          case Some(res) => res
          case None      => ujson.Null
        }
    }
  }
}
