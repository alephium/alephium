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

import scala.io.Source
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import upickle.default._

class BuiltInFunctionsSpec extends AnyFlatSpecLike with Matchers {
  it should "check docs for built in functions are generated and formatted correctly" in {
    val builtInFunctionsDocPath = "../protocol/src/main/resources/ralph-built-in-functions.json"
    val builtinFunctionsDoc =
      Using(Source.fromFile(builtInFunctionsDocPath, "UTF-8"))(_.getLines().mkString("\n")).get

    val expectedBuiltinFunctionsDoc: String =
      write(BuiltInFunctions.buildAllFunctions(), indent = 2)

    assert(
      expectedBuiltinFunctionsDoc == builtinFunctionsDoc,
      "The built-in functions documentation is not up to date. Please run `sbt tools/runMain org.alephium.tools.BuiltInFunctions` to update it."
    )
  }
}
