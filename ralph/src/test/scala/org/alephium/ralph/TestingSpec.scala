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

import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{BlockHash, TokenId}
import org.alephium.protocol.vm.{BlockHash => _, _}
import org.alephium.ralph.error.CompilerError
import org.alephium.util._

class TestingSpec extends AlephiumSpec with ContextGenerators with CompilerFixture {
  it should "compile unit tests" in {
    {
      info("Create simple contract")
      val code =
        s"""
           |Contract Foo(v0: U256, mut v1: U256) {
           |  pub fn foo() -> U256 {
           |    v1 = v1 + 1
           |    return v0 + v1
           |  }
           |  test "foo" with Self(0, 1) {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin
      val test = compileContractFull(code).rightValue.tests.tests.head
      test.settings.isEmpty is true
      test.contracts.length is 1
      val contract = test.contracts.head
      contract.typeId is Ast.TypeId("Foo")
      contract.tokens.isEmpty is true
      contract.immFields is AVector[Val](Val.U256(U256.Zero))
      contract.mutFields is AVector[Val](Val.U256(U256.One))
    }

    {
      info("Create contract with constants")
      val code =
        s"""
           |const Value = 0
           |Contract Foo(v: U256) {
           |  pub fn foo() -> U256 {
           |    return v
           |  }
           |  test "foo" with Self(Value) {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin
      val test = compileContractFull(code).rightValue.tests.tests.head
      test.settings.isEmpty is true
      test.contracts.length is 1
      val contract = test.contracts.head
      contract.typeId is Ast.TypeId("Foo")
      contract.tokens.isEmpty is true
      contract.immFields is AVector[Val](Val.U256(U256.Zero))
      contract.mutFields.isEmpty is true
    }

    {
      info("Create contract with tokens")
      val code =
        s"""
           |Contract Foo(v: U256) {
           |  pub fn foo() -> U256 {
           |    return v
           |  }
           |  test "foo" with Self{ ALPH: 1 alph }(0) {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin
      val test = compileContractFull(code).rightValue.tests.tests.head
      test.settings.isEmpty is true
      test.contracts.length is 1
      val contract = test.contracts.head
      contract.typeId is Ast.TypeId("Foo")
      contract.tokens is AVector(TokenId.alph -> ALPH.alph(1))
      contract.immFields is AVector[Val](Val.U256(U256.Zero))
      contract.mutFields.isEmpty is true
    }

    {
      info("Create contract with composite types")
      val code =
        s"""
           |struct Bar {
           |  mut a: U256,
           |  b: Bool
           |}
           |Contract Foo(@unused mut bars: [Bar; 2], @unused bar1: Bar) {
           |  pub fn foo() -> U256 {
           |    return 0
           |  }
           |  test "foo" with Self(
           |    [Bar { a: 0, b: false }, Bar { a: 1, b: true }],
           |    Bar { a: 2, b: false }
           |  ) {
           |    testCheck!(foo() == 0)
           |  }
           |}
           |""".stripMargin

      val test = compileContractFull(code).rightValue.tests.tests.head
      test.settings.isEmpty is true
      test.contracts.length is 1
      val contract = test.contracts.head
      contract.typeId is Ast.TypeId("Foo")
      contract.tokens.isEmpty is true
      contract.immFields is AVector[Val](Val.False, Val.True, Val.U256(U256.Two), Val.False)
      contract.mutFields is AVector[Val](Val.U256(U256.Zero), Val.U256(U256.One))
    }

    {
      info("Create multiple contracts")
      val code =
        s"""
           |Contract Foo(bar0: Bar, bar1: Bar) {
           |  pub fn add() -> U256 {
           |    return bar0.value() + bar1.value()
           |  }
           |  test "add"
           |  with
           |    Bar(10)@addr0
           |    Bar(20)@addr1
           |    Self(addr0, addr1)
           |  {
           |    testCheck!(add() == 30)
           |  }
           |}
           |Contract Bar(v: U256) {
           |  pub fn value() -> U256 {
           |    return v
           |  }
           |}
           |""".stripMargin

      val test = compileContractFull(code).rightValue.tests.tests.head
      test.settings.isEmpty is true
      test.contracts.length is 3

      val bar0 = test.contracts(0)
      bar0.immFields is AVector[Val](Val.U256(U256.unsafe(10)))
      bar0.mutFields.isEmpty is true

      val bar1 = test.contracts(1)
      bar1.immFields is AVector[Val](Val.U256(U256.unsafe(20)))
      bar1.mutFields.isEmpty is true

      val contract = test.contracts(2)
      contract.typeId is Ast.TypeId("Foo")
      contract.tokens.isEmpty is true
      contract.immFields is AVector[Val](
        Val.ByteVec(bar0.contractId.bytes),
        Val.ByteVec(bar1.contractId.bytes)
      )
      contract.mutFields.isEmpty is true
    }

    {
      info("Create a contract as an interface")
      val code =
        s"""
           |Contract Foo(bar: IBar) {
           |  pub fn foo() -> U256 {
           |    return bar.bar()
           |  }
           |  test "foo" with Bar(0)@addr Self(addr) {
           |    testCheck!(foo() == 0)
           |  }
           |}
           |Interface IBar {
           |  pub fn bar() -> U256
           |}
           |Contract Bar(v: U256) implements IBar {
           |  pub fn bar() -> U256 {
           |    return v
           |  }
           |}
           |""".stripMargin
      val contracts = compileContractFull(code).rightValue.tests.tests.head.contracts
      contracts.length is 2
      val bar = contracts.head
      bar.typeId is Ast.TypeId("Bar")
      bar.immFields is AVector[Val](Val.U256(U256.Zero))
      bar.mutFields.isEmpty is true
      val foo = contracts.last
      foo.typeId is Ast.TypeId("Foo")
      foo.immFields is AVector[Val](Val.ByteVec(bar.contractId.bytes))
      foo.mutFields.isEmpty is true
    }

    {
      info("Multiple tests")
      val code =
        s"""
           |Contract Foo(mut v: U256) {
           |  pub fn foo0() -> U256 {
           |    v = v + 1
           |    return v
           |  }
           |  test "foo0"
           |  with Self(0) {
           |    testCheck!(foo0() == 1)
           |  }
           |  with Self(1) {
           |    testCheck!(foo0() == 2)
           |  }
           |
           |  pub fn foo1() -> U256 {
           |    v = v - 1
           |    return v
           |  }
           |  test "foo1"
           |  with Self(2) {
           |    testCheck!(foo1() == 1)
           |  }
           |  with Self(3) {
           |    testCheck!(foo1() == 2)
           |  }
           |}
           |""".stripMargin

      val tests = compileContractFull(code).rightValue.tests.tests
      tests.length is 4
      tests.zipWithIndex.foreach { case (test, index) =>
        test.settings.isEmpty is true
        test.contracts.length is 1
        val contract = test.contracts.head
        contract.typeId is Ast.TypeId("Foo")
        contract.tokens.isEmpty is true
        contract.immFields.isEmpty is true
        contract.mutFields is AVector[Val](Val.U256(U256.unsafe(index)))
      }
    }

    {
      info("Test with settings")
      def code(settings: String) =
        s"""
           |Contract Foo(v0: U256, mut v1: U256) {
           |  pub fn foo() -> U256 {
           |    v1 = v1 + 1
           |    return v0 + v1
           |  }
           |  test "foo"
           |  with Settings($settings)
           |  with Self(0, 1) {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin

      def checkSettings(str: String, value: Testing.SettingsValue) = {
        val test = compileContractFull(code(str)).rightValue.tests.tests.head
        test.settings is Some(value)
      }

      val blockHash = BlockHash.generate
      val now       = TimeStamp.now()
      checkSettings("", Testing.SettingsValue(0, None, None))
      checkSettings("group = 0", Testing.SettingsValue(0, None, None))
      checkSettings(
        s"group = 1, blockHash = #${blockHash.toHexString}",
        Testing.SettingsValue(1, Some(blockHash), None)
      )
      checkSettings(
        s"group = 1, blockHash = #${blockHash.toHexString}, blockTimeStamp = ${now.millis}",
        Testing.SettingsValue(1, Some(blockHash), Some(now))
      )
      testContractError(
        code(s"group = 0, $$group = 1$$"),
        "These test settings are defined multiple times: group"
      )
    }

    {
      info("Test with approved assets")
      val address0 = addressStringGen.sample.get._1
      val address1 = addressStringGen.sample.get._1
      val tokenId  = TokenId.random
      val code =
        s"""
           |Contract Foo(v: U256) {
           |  pub fn foo() -> U256 {
           |    return v
           |  }
           |  test "foo"
           |  with
           |    Self(1)
           |    ApproveAssets{@$address0 -> ALPH: 1 alph; @$address1 -> ALPH: 2 alph, #${tokenId.toHexString}: 1 alph}
           |  {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin
      val test = compileContractFull(code).rightValue.tests.tests.head
      test.settings.isEmpty is true
      test.assets is Some(
        Testing.ApprovedAssetsValue(
          AVector(
            LockupScript.asset(address0).value -> AVector(TokenId.alph -> ALPH.alph(1)),
            LockupScript.asset(address1).value -> AVector(
              TokenId.alph -> ALPH.alph(2),
              tokenId      -> ALPH.alph(1)
            )
          )
        )
      )
    }
  }

  it should "throw an error if `testCheck!` is called in non-test code" in {
    val code =
      s"""
         |Contract Foo(v: U256) {
         |  pub fn foo() -> () {
         |    $$testCheck!(v == 0)$$
         |  }
         |}
         |""".stripMargin
    testContractError(code, "The `testCheck!` function can only be used in unit tests")
  }

  it should "get error" in {
    val code =
      s"""
         |Contract Foo(v: U256) {
         |  pub fn foo() -> U256 { return v  }
         |  test "foo" with Self(0) {
         |    testCheck!(foo() == 0)
         |  }
         |}
         |""".stripMargin
    val contract    = compileContractFull(code).rightValue
    val tests       = contract.tests
    val sourceIndex = contract.ast.unitTests.head.tests.head.body.head.sourceIndex
    tests.getError("foo", None, "error", None) is CompilerError.TestError(
      "Test failed: foo, detail: error",
      None,
      None
    )
    tests.getError("foo", Some(tests.errorCodes.keys.head), "error", None) is CompilerError
      .TestError(
        "Test failed: foo, detail: error",
        sourceIndex,
        None
      )
  }
}
