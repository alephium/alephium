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

package org.alephium.macros

import scala.collection.immutable.TreeSet

import org.scalatest.flatspec.AnyFlatSpecLike

class EnumerationMacrosSpec extends AnyFlatSpecLike {
  import EnumerationMacrosSpec._
  implicit val ordering: Ordering[Foo] = Ordering.by(_.getClass.getSimpleName)

  it should "enumarate all the sub-classes" in {
    val enumerated = EnumerationMacros.sealedInstancesOf[Foo]
    val expected   = TreeSet[Foo](Bar, Baz)
    assume(enumerated equals expected)
  }
}

object EnumerationMacrosSpec {
  sealed trait Foo
  case object Bar extends Foo
  case object Baz extends Foo
}
