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
       |Interface Foot {
       |  pub fn foot() -> ()
       |}
       |""".stripMargin

  val abstractContract =
    s"""
       |Abstract Contract Cat() {
       |  pub fn cat() -> ()
       |}
       |""".stripMargin

  val multiContracts =
    s"""
       |Abstract Contract Cat() {
       |  pub fn cat() -> ()
       |}
       |
       |Contract Foo(a: Bool, b: I256, c: U256, d: ByteVec, e: Address) {
       |  pub fn foo() -> (Bool, I256, U256, ByteVec, Address) {
       |    return a, b, c, d, e
       |  }
       |}
       |
       |Interface Foot {
       |  pub fn foot() -> ()
       |}
       |
       |TxScript Main(x: U256, y: U256) {
       |  assert!(x != y, 0)
       |}
       |
       |""".stripMargin

  val text =
    s"""
       |  assert!(x != y, 0)
       |""".stripMargin

  it should "match Contract Foo" in {
    TypedMatcher.matcher(contract) is Array("Foo")
  }

  it should "match TxScript Main" in {
    TypedMatcher.matcher(script) is Array("Main")
  }

  it should "match Interface Foot" in {
    TypedMatcher.matcher(interface) is Array("Foot")
  }

  it should "match Abstract Contract Cat" in {
    TypedMatcher.matcher(abstractContract) is Array("Cat")
  }

  it should "match None" in {
    TypedMatcher.matcher(text).length is 0
  }

  it should "match multi contracts" in {
    TypedMatcher.matcher(multiContracts) is Array("Cat", "Foo", "Foot", "Main")
  }

}
