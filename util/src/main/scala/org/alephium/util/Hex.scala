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

package org.alephium.util

import scala.collection.immutable.ArraySeq
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

import akka.util.ByteString
import org.bouncycastle.util.encoders.{Hex => BHex}

object Hex {
  def unsafe(s: String): ByteString = {
    ByteString.fromArrayUnsafe(BHex.decode(s))
  }

  def from(s: String): Option[ByteString] =
    try {
      Some(unsafe(s))
    } catch {
      case _: Throwable => None
    }

  def asArraySeq(s: String): Option[ArraySeq[Byte]] =
    try {
      Some(ArraySeq.unsafeWrapArray(BHex.decode(s)))
    } catch {
      case _: Throwable => None
    }

  def toHexString(input: IndexedSeq[Byte]): String = {
    BHex.toHexString(input.toArray)
  }

  implicit class HexStringSyntax(val sc: StringContext) extends AnyVal {
    def hex(): ByteString = macro hexImpl
  }

  def hexImpl(c: blackbox.Context)(): c.Expr[ByteString] = {
    import c.universe._
    c.prefix.tree match {
      case Apply(_, List(Apply(_, List(Literal(Constant(s: String)))))) =>
        val bs = BHex.decode(s)
        c.Expr(q"akka.util.ByteString($bs)")
    }
  }
}
