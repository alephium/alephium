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

import org.alephium.ralph.BuiltIn

object BuiltInFunctions extends App {
  val json: String = write(buildAllFunctions(), indent = 2)

  import java.io.PrintWriter
  new PrintWriter("../protocol/src/main/resources/ralph-built-in-functions.json") {
    write(json)
    close()
  }

  def buildAllFunctions(): Seq[FunctionInfo] = {
    val allFunctions: immutable.Iterable[FunctionInfo] = BuiltIn.statefulFuncsSeq.map {
      case (_, f) =>
        FunctionInfo(f.name, f.category.toString, f.signature, f.doc, f.params, f.returns)
    }
    ((FunctionInfo.encodeFields +: allFunctions.toSeq) ++ FunctionInfo.mapFunctions ++ FunctionInfo.testFunctions).sorted
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
        Map,
        Asset,
        Utils,
        Chain,
        Conversion,
        ByteVec,
        Cryptography
      ).map(_.toString)
      Ordering.by(f => orders.indexOf(f.category))
    }

    val encodeFields: FunctionInfo = FunctionInfo(
      name = "encodeFields",
      category = BuiltIn.Category.Contract.toString,
      signature = "fn <ContractName>.encodeFields!(...) -> (ByteVec, ByteVec)",
      doc = "Encode the fields for creating a contract",
      params = Seq("@param ... the fields of the to-be-created target contract"),
      returns =
        "@returns two ByteVecs: the first one is the encoded immutable fields, and the second one is the encoded mutable fields"
    )

    // scalastyle:off line.size.limit
    val mapInsert: FunctionInfo = FunctionInfo(
      name = "map.insert",
      category = BuiltIn.Category.Map.toString,
      signature =
        "fn <map>.insert!(depositorAddress?: Address, key: <Bool | U256 | I256 | Address | ByteVec>, value: Any) -> ()",
      doc =
        "Insert a key/value pair into the map. No brace syntax is required, as the minimal storage deposit will be deducted from the approved assets by the VM",
      params = Seq(
        "@param depositorAddress the address to pay the minimal storage deposit (0.1 ALPH) for the new map entry. If not provided, minimal storage deposit will be paid by the transaction caller",
        "@param key the key to insert",
        "@param value the value to insert"
      ),
      returns = "@returns "
    )

    val mapRemove: FunctionInfo = FunctionInfo(
      name = "map.remove",
      category = BuiltIn.Category.Map.toString,
      signature =
        "fn <map>.remove!(refundRecipient?: Address, key: <Bool | U256 | I256 | Address | ByteVec>) -> ()",
      doc = "Remove a key from the map",
      params = Seq(
        "@param refundRecipient the address to receive the redeemed minimal storage deposit. If not provided, minimal storage deposit will be received by the transaction caller",
        "@param key the key to remove"
      ),
      returns = "@returns "
    )
    // scalastyle:on line.size.limit

    val mapContains: FunctionInfo = FunctionInfo(
      name = "map.contains",
      category = BuiltIn.Category.Map.toString,
      signature = "fn <map>.contains!(key: <Bool | U256 | I256 | Address | ByteVec>) -> Bool",
      doc = "Check whether the map contains a bindiing for the key",
      params = Seq("@param key the key to check"),
      returns = "@returns true if there is a binding for key in this map, false otherwise"
    )

    val mapFunctions: Seq[FunctionInfo] = Seq(mapInsert, mapRemove, mapContains)

    val testCheck: FunctionInfo = FunctionInfo(
      name = "testCheck",
      category = BuiltIn.Category.Test.toString,
      signature = "fn testCheck!(condition:Bool) -> ()",
      doc = "Tests the condition or checks invariants.",
      params = Seq("@condition the condition to be checked"),
      returns = "@returns "
    )

    val testEqual: FunctionInfo = FunctionInfo(
      name = "testEqual",
      category = BuiltIn.Category.Test.toString,
      signature =
        "fn testEqual!(left: <Bool | U256 | I256 | Address | ByteVec>, right: <Bool | U256 | I256 | Address | ByteVec>) -> ()",
      doc = "Asserts that the given values are equal.",
      params = Seq(
        "@left the first value to compare",
        "@right the second value to compare; must be the same type as `left`"
      ),
      returns = "@returns "
    )

    val testFail: FunctionInfo = FunctionInfo(
      name = "testFail",
      category = BuiltIn.Category.Test.toString,
      signature = "fn testFail!(expr) -> ()",
      doc = "Asserts that the given expression throws an exception during execution.",
      params = Seq("@expr the expression to be executed"),
      returns = "@returns "
    )

    val testError: FunctionInfo = FunctionInfo(
      name = "testError",
      category = BuiltIn.Category.Test.toString,
      signature = "fn testError!(expr, errorCode: U256) -> ()",
      doc = "Asserts that the given expression throws an exception with the expected error code.",
      params = Seq(
        "@expr the expression to be executed",
        "@errorCode the expected error code"
      ),
      returns = "@returns "
    )

    val testFunctions: Seq[FunctionInfo] = Seq(testCheck, testEqual, testFail, testError)
  }
}
