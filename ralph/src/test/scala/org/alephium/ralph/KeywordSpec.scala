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

package org.alephium.ralph

import scala.collection.immutable.TreeSet

import org.alephium.util.AlephiumSpec

class KeywordSpec extends AlephiumSpec {

  val keywordStrings =
    TreeSet(
      "Contract",
      "AssetScript",
      "TxScript",
      "Interface",
      "struct",
      "let",
      "mut",
      "fn",
      "return",
      "true",
      "false",
      "if",
      "else",
      "while",
      "for",
      "pub",
      "event",
      "emit",
      "extends",
      "embeds",
      "implements",
      "alph",
      "const",
      "enum",
      "Abstract",
      "ALPH",
      "mapping"
    )

  it should "match string keyword" in {
    // convert all typed to String and compare
    Keyword.Used.all.map(_.name) is keywordStrings
  }

  it should "match typed keywords" in {
    // convert all String to types and compare
    keywordStrings.flatMap(Keyword.Used(_)) is Keyword.Used.all
  }

  it should "return false for non-existing String keywords" in {
    forAll { string: String =>
      Keyword.Used.exists(string) is false
    }
  }

  it should "return true for existing String keywords" in {
    keywordStrings foreach { string =>
      Keyword.Used.exists(string) is true
    }
  }

  it should "be ok with annotated class names" in {
    Keyword.`@unused`.name is "@unused"
  }
}
