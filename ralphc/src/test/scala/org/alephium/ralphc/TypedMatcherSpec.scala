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

import org.alephium.util.AlephiumSpec

class TypedMatcherSpec extends AlephiumSpec {
  val contract =
    s"""
       |Contract Foo(a: Bool, b: I256, c: U256, d: ByteVec, e: Address) {
       |  pub fn foo() -> (Bool, I256, U256, ByteVec, Address) {
       |    return a, b, c, d, e
       |  }
       |}
       |""".stripMargin

  val script =
    s"""
       |TxScript Main(x: U256, y: U256) {
       |  assert!(x != y, 0)
       |}
       |""".stripMargin

  val interface =
    s"""
       |Interface Foo {
       |  pub fn foo() -> ()
       |}
       |""".stripMargin

  val abstractContract =
    s"""
       |Abstract Contract Foo() {
       |  pub fn foo() -> ()
       |}
       |""".stripMargin

  val text =
    s"""
       |  assert!(x != y, 0)
       |""".stripMargin

  it should "match Contract Foo" in {
    TypedMatcher.matcher(contract) is Some("Foo")
  }

  it should "match TxScript Main" in {
    TypedMatcher.matcher(script) is Some("Main")
  }

  it should "match Interface Foo" in {
    TypedMatcher.matcher(interface) is Some("Foo")
  }

  it should "match Abstract Contract Foo" in {
    TypedMatcher.matcher(abstractContract) is Some("Foo")
  }

  it should "match None" in {
    TypedMatcher.matcher(text) is None
  }
}
