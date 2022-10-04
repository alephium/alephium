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

package org.alephium.tools

import scala.collection.immutable

import upickle.default._
import upickle.default.{macroRW, ReadWriter => RW}

import org.alephium.protocol.vm.lang.BuiltIn

object BuiltInFunctions extends App {
  val allFunctions: immutable.Iterable[FunctionInfo] = BuiltIn.statefulFuncsSeq.map { case (_, f) =>
    FunctionInfo(f.name, f.category.toString, f.signature, f.doc, f.params, f.returns)
  }
  val json: String = write(allFunctions.toSeq.sorted, indent = 2)

  import java.io.PrintWriter
  new PrintWriter("../protocol/src/main/resources/ralph-built-in-functions.json") {
    write(json)
    close()
  }

  final case class FunctionInfo(
      name: String,
      category: String,
      signature: String,
      doc: String,
      params: Seq[String],
      returns: String
  )
  object FunctionInfo {
    @SuppressWarnings(Array("org.wartremover.warts.ToString"))
    implicit val rw: RW[FunctionInfo] = macroRW
    implicit val ordering: Ordering[FunctionInfo] = {
      import BuiltIn.Category._
      val orders = Seq[BuiltIn.Category](
        Contract,
        SubContract,
        Asset,
        Utils,
        Chain,
        Conversion,
        ByteVec,
        Cryptography
      ).map(_.toString)
      Ordering.by(f => orders.indexOf(f.category))
    }
  }
}
