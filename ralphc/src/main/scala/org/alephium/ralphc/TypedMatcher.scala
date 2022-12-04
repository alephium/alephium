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
  val abstractContractMatcher: Regex =
    """[\S\s]*Abstract\s+Contract\s+([A-Z][a-zA-Z0-9]*)[\S\s]*""".r
  val contractMatcher: Regex  = """[\S\s]*Contract\s+([A-Z][a-zA-Z0-9]*)[\S\s]*""".r
  val interfaceMatcher: Regex = """[\S\s]*Interface\s+([A-Z][a-zA-Z0-9]*)[\S\s]*""".r
  val scriptMatcher: Regex    = """[\S\s]*TxScript\s+([A-Z][a-zA-Z0-9]*)[\S\s]*""".r

  def matcher(input: String): Option[String] = {
    input match {
      case abstractContractMatcher(name) => Some(name)
      case contractMatcher(name)         => Some(name)
      case interfaceMatcher(name)        => Some(name)
      case scriptMatcher(name)           => Some(name)
      case _                             => None
    }
  }
}
