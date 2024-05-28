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

import org.alephium.macros.EnumerationMacros

sealed trait Keyword extends Product {

  def name: String =
    productPrefix

  override def toString: String =
    name
}
// scalastyle:off number.of.methods
object Keyword {

  implicit val ordering: Ordering[Used] = Ordering.by(_.name)

  sealed trait Used   extends Keyword
  sealed trait Unused extends Keyword

  case object Contract    extends Used
  case object AssetScript extends Used
  case object TxScript    extends Used
  case object Interface   extends Used
  // scalastyle:off object.name
  case object struct     extends Used
  case object let        extends Used
  case object mut        extends Used
  case object fn         extends Used
  case object `return`   extends Used
  case object `true`     extends Used
  case object `false`    extends Used
  case object `if`       extends Used
  case object `else`     extends Used
  case object `while`    extends Used
  case object `for`      extends Used
  case object pub        extends Used
  case object event      extends Used
  case object emit       extends Used
  case object `extends`  extends Used
  case object `embeds`   extends Used
  case object implements extends Used
  case object alph       extends Used
  case object const      extends Used
  case object `enum`     extends Used
  case object Abstract   extends Used
  case object ALPH_CAPS extends Used {
    override def name: String =
      "ALPH"
  }
  case object `mapping` extends Used

  case object `@unused` extends Unused
  // scalastyle:on object.name

  object Used {
    val all: TreeSet[Used] =
      EnumerationMacros.sealedInstancesOf[Keyword.Used]

    def exists(keyword: String): Boolean =
      all.exists(_.name == keyword)

    def apply(string: String): Option[Keyword.Used] =
      all.find(_.name == string)
  }
}
