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

package org.alephium.ralphc

import scala.util.matching.Regex

object TypedMatcher {
  val contractMatcher: Regex  = """Contract\s+([A-Z][a-zA-Z0-9]*)""".r
  val interfaceMatcher: Regex = """Interface\s+([A-Z][a-zA-Z0-9]*)""".r
  val scriptMatcher: Regex    = """TxScript\s+([A-Z][a-zA-Z0-9]*)""".r

  def matcher(input: String): Array[String] = {
    val ret = contractMatcher.findAllMatchIn(input).map(f => f.group(1)) ++
      interfaceMatcher.findAllMatchIn(input).map(f => f.group(1)) ++
      scriptMatcher.findAllMatchIn(input).map(f => f.group(1))
    ret.toArray
  }
}
