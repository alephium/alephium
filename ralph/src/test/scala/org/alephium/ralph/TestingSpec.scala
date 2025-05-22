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
import org.alephium.protocol.model.{BlockHash, ContractId, ContractOutput, TokenId}
import org.alephium.protocol.vm.{BlockHash => _, _}
import org.alephium.ralph.error.CompilerError
import org.alephium.util._

class TestingSpec extends AlephiumSpec with ContextGenerators with CompilerFixture {
  it should "compile unit tests" in {
    {
      info("Create contract with default values")
      val code =
        s"""
           |Contract Foo(v0: U256, mut v1: U256) {
           |  pub fn foo() -> U256 {
           |    v1 = v1 + 1
           |    return v0 + v1
           |  }
           |  test "foo" {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin
      val test = compileContractFull(code).rightValue.tests.value.tests.head
      test.settings.isEmpty is true
      test.before.length is 1
      val contract = test.before.head
      contract.typeId is Ast.TypeId("Foo")
      contract.tokens.isEmpty is true
      contract.immFields is AVector[(String, Val)](("v0", Val.U256(U256.Zero)))
      contract.mutFields is AVector[(String, Val)](("v1", Val.U256(U256.Zero)))
    }

    {
      info("Consider the stdId field")
      def code(enableStd: Boolean) =
        s"""
           |@std(enabled = $enableStd)
           |Contract Foo(@unused v0: U256, @unused mut v1: U256) implements FooBase {
           |  pub fn foo() -> U256 {
           |    return 0
           |  }
           |  test "foo" {
           |    testCheck!(foo() == 0)
           |  }
           |}
           |@std(id = #1234)
           |Interface FooBase {
           |  pub fn foo() -> U256
           |}
           |""".stripMargin
      val test0     = compileContractFull(code(true)).rightValue.tests.value.tests.head
      val contract0 = test0.before.head
      contract0.immFields is AVector[(String, Val)](
        ("v0", Val.U256(U256.Zero)),
        (Ast.stdArg.ident.name, Val.ByteVec(Ast.StdInterfaceIdPrefix ++ Hex.unsafe("1234")))
      )
      contract0.mutFields is AVector[(String, Val)](("v1", Val.U256(U256.Zero)))

      val test1     = compileContractFull(code(false)).rightValue.tests.value.tests.head
      val contract1 = test1.before.head
      contract1.immFields is AVector[(String, Val)](("v0", Val.U256(U256.Zero)))
      contract1.mutFields is AVector[(String, Val)](("v1", Val.U256(U256.Zero)))
    }

    {
      info("Create simple contract")
      val code =
        s"""
           |Contract Foo(v0: U256, mut v1: U256) {
           |  pub fn foo() -> U256 {
           |    v1 = v1 + 1
           |    return v0 + v1
           |  }
           |  test "foo" before Self(0, 1) {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin
      val test = compileContractFull(code).rightValue.tests.value.tests.head
      test.settings.isEmpty is true
      test.before.length is 1
      val contract = test.before.head
      contract.typeId is Ast.TypeId("Foo")
      contract.tokens.isEmpty is true
      contract.immFields is AVector[(String, Val)](("v0", Val.U256(U256.Zero)))
      contract.mutFields is AVector[(String, Val)](("v1", Val.U256(U256.One)))
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
           |  test "foo" before Self(Value) {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin
      val test = compileContractFull(code).rightValue.tests.value.tests.head
      test.settings.isEmpty is true
      test.before.length is 1
      val contract = test.before.head
      contract.typeId is Ast.TypeId("Foo")
      contract.tokens.isEmpty is true
      contract.immFields is AVector[(String, Val)](("v", Val.U256(U256.Zero)))
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
           |  test "foo" before Self{ ALPH: 1 alph }(0) {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin
      val test = compileContractFull(code).rightValue.tests.value.tests.head
      test.settings.isEmpty is true
      test.before.length is 1
      val contract = test.before.head
      contract.typeId is Ast.TypeId("Foo")
      contract.tokens is AVector(TokenId.alph -> ALPH.alph(1))
      contract.immFields is AVector[(String, Val)](("v", Val.U256(U256.Zero)))
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
           |  test "foo" before Self(
           |    [Bar { a: 0, b: false }, Bar { a: 1, b: true }],
           |    Bar { a: 2, b: false }
           |  ) {
           |    testCheck!(foo() == 0)
           |  }
           |}
           |""".stripMargin

      val test = compileContractFull(code).rightValue.tests.value.tests.head
      test.settings.isEmpty is true
      test.before.length is 1
      val contract = test.before.head
      contract.typeId is Ast.TypeId("Foo")
      contract.tokens.isEmpty is true
      contract.immFields is AVector[(String, Val)](
        ("bars[0].b", Val.False),
        ("bars[1].b", Val.True),
        ("bar1.a", Val.U256(U256.Two)),
        ("bar1.b", Val.False)
      )
      contract.mutFields is AVector[(String, Val)](
        ("bars[0].a", Val.U256(U256.Zero)),
        ("bars[1].a", Val.U256(U256.One))
      )
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
           |  before
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

      val test = compileContractFull(code).rightValue.tests.value.tests.head
      test.settings.isEmpty is true
      test.before.length is 3

      val bar0 = test.before(0)
      bar0.immFields is AVector[(String, Val)](("v", Val.U256(U256.unsafe(10))))
      bar0.mutFields.isEmpty is true

      val bar1 = test.before(1)
      bar1.immFields is AVector[(String, Val)](("v", Val.U256(U256.unsafe(20))))
      bar1.mutFields.isEmpty is true

      val contract = test.before(2)
      contract.typeId is Ast.TypeId("Foo")
      contract.tokens.isEmpty is true
      contract.immFields is AVector[(String, Val)](
        ("bar0", Val.ByteVec(bar0.contractId.bytes)),
        ("bar1", Val.ByteVec(bar1.contractId.bytes))
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
           |  test "foo" before Bar(0)@addr Self(addr) {
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
      val contracts = compileContractFull(code).rightValue.tests.value.tests.head.before
      contracts.length is 2
      val bar = contracts.head
      bar.typeId is Ast.TypeId("Bar")
      bar.immFields is AVector[(String, Val)](("v", Val.U256(U256.Zero)))
      bar.mutFields.isEmpty is true
      val foo = contracts.last
      foo.typeId is Ast.TypeId("Foo")
      foo.immFields is AVector[(String, Val)](("bar", Val.ByteVec(bar.contractId.bytes)))
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
           |  before Self(0) {
           |    testCheck!(foo0() == 1)
           |  }
           |  before Self(1) {
           |    testCheck!(foo0() == 2)
           |  }
           |
           |  pub fn foo1() -> U256 {
           |    v = v - 1
           |    return v
           |  }
           |  test "foo1"
           |  before Self(2) {
           |    testCheck!(foo1() == 1)
           |  }
           |  before Self(3) {
           |    testCheck!(foo1() == 2)
           |  }
           |}
           |""".stripMargin

      val tests = compileContractFull(code).rightValue.tests.value.tests
      tests.length is 4
      tests.zipWithIndex.foreach { case (test, index) =>
        test.settings.isEmpty is true
        test.before.length is 1
        val contract = test.before.head
        contract.typeId is Ast.TypeId("Foo")
        contract.tokens.isEmpty is true
        contract.immFields.isEmpty is true
        contract.mutFields is AVector[(String, Val)](("v", Val.U256(U256.unsafe(index))))
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
           |  before Self(0, 1) {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin

      def checkSettings(str: String, value: Testing.SettingsValue) = {
        val test = compileContractFull(code(str)).rightValue.tests.value.tests.head
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
      testContractError(
        code(s"$$invalidKey = 0$$"),
        "Invalid setting key invalidKey, it must be one of [group, blockHash, blockTimeStamp]"
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
           |  before Self(1)
           |  approve{@$address0 -> ALPH: 1 alph; @$address1 -> ALPH: 2 alph, #${tokenId.toHexString}: 1 alph}
           |  {
           |    testCheck!(foo() == 1)
           |  }
           |}
           |""".stripMargin
      val test = compileContractFull(code).rightValue.tests.value.tests.head
      test.settings.isEmpty is true
      val assets = test.assets.value.assets
      assets.length is 2
      assets(0) is LockupScript.asset(address0).value -> AVector(TokenId.alph -> ALPH.alph(1))
      assets(1)._1 is LockupScript.asset(address1).value
      assets(1)._2.toSet is Set(TokenId.alph -> ALPH.alph(2), tokenId -> ALPH.alph(1))
    }

    {
      info("Duplicate tests")
      val code =
        s"""
           |Contract Foo(v: U256) {
           |  pub fn foo() -> U256 { return v }
           |  test "foo" before Self(1) {
           |    testCheck!(foo() == 1)
           |  }
           |  $$test "foo" before Self(2) {
           |    testCheck!(foo() == 2)
           |  }$$
           |}
           |""".stripMargin
      testContractError(code, "These tests are defined multiple times: Foo:foo")
    }

    {
      info("Use default values")
      val code =
        s"""
           |struct Foo {
           |  mut a: U256,
           |  b: U256
           |}
           |Contract Bar(@unused mut foos: [Foo; 2], @unused baz: Baz, mut b: U256, a: U256) extends Base(a, b) {
           |  pub fn update(v: U256) -> () { b = v }
           |}
           |Contract Baz(v: U256) {
           |  pub fn baz() -> U256 { return v }
           |}
           |Abstract Contract Base(a: U256, mut b: U256) {
           |  pub fn sum() -> U256 { return a + b }
           |  test "sum" before Self(10, 20) {
           |    testCheck!(sum() == 30)
           |  }
           |}
           |""".stripMargin
      val tests       = compileContractFull(code).rightValue.tests.value
      val barContract = tests.tests.head.before.head
      barContract.typeId is Ast.TypeId("Bar")
      barContract.immFields is AVector[(String, Val)](
        ("foos[0].b", Val.U256(0)),
        ("foos[1].b", Val.U256(0)),
        ("baz", Val.ByteVec(ContractId.zero.bytes)),
        ("a", Val.U256(10))
      )
      barContract.mutFields is AVector[(String, Val)](
        ("foos[0].a", Val.U256(0)),
        ("foos[1].a", Val.U256(0)),
        ("b", Val.U256(20))
      )
    }

    {
      info("Unit tests in abstract contracts")
      val code =
        s"""
           |Contract Foo(a: U256, b: U256, @unused mut c: Bool) extends Base(a, b) {
           |  pub fn foo() -> U256 { return base() }
           |  fn base() -> U256 { return a + b }
           |  fn isSum() -> Bool { return true }
           |}
           |Contract Bar(a: U256, b: U256) extends Base(a, b) {
           |  pub fn bar() -> U256 { return base() }
           |  fn base() -> U256 { return a - b }
           |  fn isSum() -> Bool { return false }
           |}
           |Abstract Contract Base(a: U256, b: U256) {
           |  fn isSum() -> Bool
           |  fn base() -> U256
           |  test "base" before Self(20, 10) {
           |    let result = if (isSum()) 30 else 10
           |    testCheck!(base() == result)
           |  }
           |}
           |""".stripMargin
      val contracts = Compiler.compileProject(code).rightValue._1
      contracts.length is 2
      val fooTests = contracts(0).tests.value.tests
      fooTests.length is 1
      val fooContract = fooTests.head.before.head
      fooContract.typeId is Ast.TypeId("Foo")
      fooContract.immFields is AVector[(String, Val)](("a", Val.U256(20)), ("b", Val.U256(10)))
      fooContract.mutFields is AVector[(String, Val)](("c", Val.False))

      val barTests = contracts(1).tests.value.tests
      barTests.length is 1
      val barContract = barTests.head.before.head
      barContract.typeId is Ast.TypeId("Bar")
      fooContract.immFields is AVector[(String, Val)](("a", Val.U256(20)), ("b", Val.U256(10)))
      barContract.mutFields.isEmpty is true
    }
  }

  it should "throw an error if test assert is called in non-test code" in {
    def code(testCall: String) =
      s"""
         |Contract Foo(v: U256) {
         |  pub fn foo() -> () {
         |    $$$testCall$$
         |  }
         |}
         |""".stripMargin
    testContractError(
      code("testCheck!(v == 0)"),
      "The `testCheck!` function can only be used in unit tests"
    )
    testContractError(
      code("testFail!(v == 0)"),
      "The `testFail!` function can only be used in unit tests"
    )
    testContractError(
      code("testEqual!(v, 0)"),
      "The `testEqual!` function can only be used in unit tests"
    )
  }

  it should "get error" in {
    val code =
      s"""
         |Contract Foo(v: U256) {
         |  pub fn foo() -> U256 { return v  }
         |  test "foo" before Self(0) {
         |    testCheck!(foo() == 0)
         |  }
         |}
         |""".stripMargin
    val contract    = compileContractFull(code).rightValue
    val tests       = contract.tests.value
    val sourceIndex = contract.ast.unitTests.head.tests.head.body.head.sourceIndex
    tests.getError("foo", None, "error", "") is CompilerError.TestError(
      "Test failed: foo, detail: error",
      None,
      None
    )
    tests.getError("foo", Some(tests.sourceIndexes.keys.head), "error", "") is CompilerError
      .TestError(
        "Test failed: foo, detail: error",
        sourceIndex,
        None
      )
  }

  it should "throw an error if `assert!` is called in test code" in {
    val code =
      s"""
         |Contract Foo(v: U256) {
         |  pub fn foo() -> U256 { return v }
         |  test "foo" before Self(0) {
         |    $$assert!(foo() == 0, 0)$$
         |  }
         |}
         |""".stripMargin
    testContractError(code, "Please use `testCheck!` instead of `assert!` in unit tests")
  }

  it should "throw an error if the contract id is invalid" in {
    def code(stat: String) =
      s"""
         |Contract Foo(bar: Bar) {
         |  pub fn foo() -> () { bar.bar() }
         |  test "foo"
         |  before Bar(0)@barId Self(barId)
         |  after $stat {
         |    foo()
         |  }
         |}
         |Contract Bar(mut v: U256) {
         |  pub fn bar() -> () {
         |    v += 1
         |  }
         |}
         |""".stripMargin
    testContractError(code(s"$$Bar(1)$$"), "Expect a contract id in after def")
    testContractError(
      code(s"Bar(1)@$$barId0$$"),
      "Variable barId0 is not defined in the current scope or is used before being defined"
    )
    compileContract(code("Bar(1)@barId")).isRight is true
  }

  it should "check contract state after testing" in {
    val tokenId = TokenId.random
    val code =
      s"""
         |struct Bar {
         |  a: [U256; 2],
         |  mut b: U256
         |}
         |Contract Foo(@unused mut bars: [Bar; 2], @unused mut bar: Bar) {
         |  pub fn foo() -> () {}
         |  test "foo"
         |  before Self([Bar { a: [0; 2], b: 0 }; 2], Bar { a: [0; 2], b: 0 })
         |  after Self{ALPH: 1 alph, #${tokenId.toHexString}: 2 alph}([Bar { a: [0, 1], b: 2 }; 2], Bar { a: [2, 3], b: 4 })
         |  {
         |    foo()
         |  }
         |}
         |""".stripMargin

    val contractState = compileContractFull(code).rightValue.tests.value.tests.head.after.head
    val immFields = AVector[Val](
      Val.U256(U256.Zero),
      Val.U256(U256.One),
      Val.U256(U256.Zero),
      Val.U256(U256.One),
      Val.U256(U256.Two),
      Val.U256(U256.unsafe(3))
    )
    val mutFields = AVector[Val](Val.U256(U256.Two), Val.U256(U256.Two), Val.U256(U256.unsafe(4)))
    val contractOutput = ContractOutput(
      ALPH.oneAlph,
      LockupScript.p2c(contractState.contractId),
      AVector((tokenId, ALPH.alph(2)))
    )
    Testing.checkContractState(contractState, immFields, mutFields, contractOutput) isE ()

    val invalidImmFields = Seq(
      (0, Val.U256(U256.One), "invalid field bars[0].a[0], expected U256(0), have: U256(1)"),
      (3, Val.U256(U256.Zero), "invalid field bars[1].a[1], expected U256(1), have: U256(0)"),
      (4, Val.U256(U256.Zero), "invalid field bar.a[0], expected U256(2), have: U256(0)")
    )
    invalidImmFields.foreach { case (index, value, error) =>
      val fields = immFields.replace(index, value)
      Testing
        .checkContractState(contractState, fields, mutFields, contractOutput)
        .leftValue is error
    }

    val invalidMutFields = Seq(
      (0, Val.U256(U256.One), "invalid field bars[0].b, expected U256(2), have: U256(1)"),
      (1, Val.U256(U256.Zero), "invalid field bars[1].b, expected U256(2), have: U256(0)"),
      (2, Val.U256(U256.Zero), "invalid field bar.b, expected U256(4), have: U256(0)")
    )
    invalidMutFields.foreach { case (index, value, error) =>
      val fields = mutFields.replace(index, value)
      Testing
        .checkContractState(contractState, immFields, fields, contractOutput)
        .leftValue is error
    }

    val invalidOutputs = Seq(
      (
        contractOutput.copy(amount = ALPH.alph(2)),
        s"invalid token amount, token id: ${TokenId.alph.toHexString}, expected: 1000000000000000000, have: 2000000000000000000"
      ),
      (
        contractOutput.copy(tokens = AVector((tokenId, ALPH.oneAlph))),
        s"invalid token amount, token id: ${tokenId.toHexString}, expected: 2000000000000000000, have: 1000000000000000000"
      )
    )
    invalidOutputs.foreach { case (output, error) =>
      Testing.checkContractState(contractState, immFields, mutFields, output).leftValue is error
    }
  }

  it should "throw an error if token amount overflow" in {
    val max = U256.MaxValue
    val code =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> U256 { return 0 }
         |  test "foo"
         |  before Self{ALPH: $max, ALPH: 2}() {
         |    testCheck!(foo() == 0)
         |  }
         |}
         |""".stripMargin
    compileContract(code).leftValue.getMessage is
      s"Token ${TokenId.alph.toHexString} amount overflow"
  }

  it should "support constant expr in contract constructor" in {
    val code =
      s"""
         |const Value0 = 1
         |const Value1 = 1
         |Contract Foo(v: U256) {
         |  pub fn foo() -> U256 { return v }
         |  test "foo"
         |  before Self(Value0 + Value1) {
         |    testCheck!(foo() == 2)
         |  }
         |}
         |""".stripMargin
    val test = compileContractFull(code).rightValue.tests.get.tests.head
    test.selfContract.immFields is AVector[(String, Val)](("v", Val.U256(U256.unsafe(2))))
  }

  it should "test using random func" in {
    def code(str: String) =
      s"""
         |Contract Foo() {
         |  pub fn foo0() -> U256 {
         |    return 0
         |  }
         |  pub fn foo1() -> I256 {
         |    return -1i
         |  }
         |  test "foo" {
         |    testCheck!($str)
         |  }
         |}
         |""".stripMargin
    compileContractFull(code("randomU256!() > foo0()")).isRight is true
    compileContractFull(code("randomI256!() > foo1()")).isRight is true
    testContractError(
      code(s"$$randomU256!(1)$$ > foo0()"),
      "Invalid args type for builtin func randomU256"
    )
    testContractError(
      code(s"$$randomU256!() > foo1()$$"),
      "Invalid param types List(U256, I256) for > operator"
    )
    testContractError(
      code(s"$$randomI256!(1)$$ > foo1()"),
      "Invalid args type for builtin func randomI256"
    )
    testContractError(
      code(s"$$randomI256!() > foo0()$$"),
      "Invalid param types List(I256, U256) for > operator"
    )
  }

  it should "compile testEqual" in {
    def code(testCall: String) =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> U256 {
         |    return 0
         |  }
         |  test "foo" {
         |    $testCall
         |  }
         |}
         |""".stripMargin

    compileContractFull(code("testEqual!(foo(), 0)")).isRight is true
    testContractError(
      code(s"$$testEqual!(foo(), 0, 0)$$"),
      "Expected 2 arguments, but got 3"
    )
    testContractError(
      code(s"$$testEqual!(foo(), 0i)$$"),
      "Invalid args type List(U256, I256) for builtin func testEqual"
    )
  }
}
