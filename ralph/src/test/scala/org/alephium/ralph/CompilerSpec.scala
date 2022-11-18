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

import org.scalatest.Assertion

import org.alephium.protocol.{Hash, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm._
import org.alephium.serde._
import org.alephium.util._

// scalastyle:off no.equal file.size.limit
class CompilerSpec extends AlephiumSpec with ContextGenerators {
  it should "parse asset script" in {
    val script =
      s"""
         |// comment
         |AssetScript Foo {
         |  @using(updateFields = false)
         |  pub fn bar(a: U256, b: U256) -> (U256) {
         |    return (a + b)
         |  }
         |}
         |""".stripMargin
    val (_, warnings) = Compiler.compileAssetScript(script).rightValue
    warnings.isEmpty is true
  }

  it should "parse tx script" in {
    {
      info("success")

      val script =
        s"""
           |// comment
           |TxScript Foo {
           |  return
           |  pub fn bar(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler.compileTxScript(script).isRight is true
    }

    {
      info("fail without main statements")

      val script =
        s"""
           |TxScript Foo {}
           |""".stripMargin
      Compiler
        .compileTxScript(script)
        .leftValue
        .message is "No main statements defined in TxScript Foo"
    }

    {
      info("fail with event definition")

      val script =
        s"""
           |TxScript Foo {
           |  event Add(a: U256, b: U256)
           |
           |  pub fn bar(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler
        .compileTxScript(script)
        .leftValue
        .message is """Parser failed: Expected multiContract:1:1 / rawTxScript:2:1 / "}":3:3, found "event Add(""""
    }
  }

  it should "parse contracts" in {
    {
      info("success")

      val contract =
        s"""
           |// comment
           |Contract Foo(mut x: U256, mut y: U256) {
           |  // comment
           |  pub fn add0(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |
           |  fn add1() -> (U256) {
           |    return (x + y)
           |  }
           |
           |  fn add2(d: U256) -> () {
           |    let mut z = 0u
           |    z = d
           |    x = x + z // comment
           |    y = y + z // comment
           |    return
           |  }
           |}
           |""".stripMargin

      Compiler.compileContract(contract).isRight is true
    }

    {
      info("no function definition")

      val contract =
        s"""
           |Contract Foo(mut x: U256, mut y: U256, c: U256) {
           |  event Add(a: U256, b: U256)
           |}
           |""".stripMargin
      Compiler
        .compileContract(contract)
        .leftValue
        .message is "No function found in Contract \"Foo\""
    }

    {
      info("duplicated function definitions")

      val contract =
        s"""
           |Contract Foo(mut x: U256, mut y: U256, c: U256) {
           |  pub fn add1(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |  pub fn add2(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |  pub fn add3(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |
           |  pub fn add1(b: U256, a: U256) -> (U256) {
           |    return (a + b)
           |  }
           |  pub fn add2(b: U256, a: U256) -> (U256) {
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler
        .compileContract(contract)
        .leftValue
        .message is "These functions are implemented multiple times: add1, add2"
    }
  }

  it should "infer types" in {
    def check(
        xMut: String,
        a: String,
        aType: String,
        b: String,
        bType: String,
        rType: String,
        fname: String,
        validity: Boolean = false
    ) = {
      val contract =
        s"""
           |Contract Foo($xMut x: U256) {
           |  pub fn add($a: $aType, $b: $bType) -> ($rType) {
           |    x = a + b
           |    return (a - b)
           |  }
           |
           |  fn $fname() -> () {
           |    return
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(contract).isRight is validity
    }

    check("mut", "a", "U256", "b", "U256", "U256", "foo", true)
    check("", "a", "U256", "b", "U256", "U256", "foo")
    check("mut", "x", "U256", "b", "U256", "U256", "foo")
    check("mut", "a", "U256", "x", "U256", "U256", "foo")
    check("mut", "a", "I64", "b", "U256", "U256", "foo")
    check("mut", "a", "U256", "b", "I64", "U256", "foo")
    check("mut", "a", "U256", "b", "U256", "I64", "foo")
    check("mut", "a", "U256", "b", "U256", "U256, U256", "foo")
    check("mut", "a", "U256", "b", "U256", "U256", "add")
  }

  it should "parse multiple contracts" in {
    val input =
      s"""
         |Contract Foo() {
         |  fn foo(bar: Bar) -> () {
         |    return bar.bar()
         |  }
         |
         |  pub fn bar() -> () {
         |    return
         |  }
         |}
         |
         |Contract Bar() {
         |  pub fn bar() -> () {
         |    return foo()
         |  }
         |
         |  fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(input, 0).isRight is true
    Compiler.compileContract(input, 1).isRight is true
  }

  it should "check function return types" in {
    val noReturnCases = Seq(
      s"""
         |Contract Foo() {
         |  fn foo() -> (U256) {
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo(value: U256) -> (U256) {
         |    if (value > 10) {
         |      return 1
         |    }
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo() -> (U256) {
         |    let mut x = 0
         |    return 0
         |    x = 1
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo(value: U256) -> (U256) {
         |    if (value > 10) {
         |      return 0
         |    } else {
         |      if (value > 20) {
         |        return 1
         |      }
         |    }
         |  }
         |}
         |""".stripMargin
    )
    noReturnCases.foreach { code =>
      Compiler.compileContract(code).leftValue is Compiler.Error(
        "Expected return statement for function \"foo\""
      )
    }

    val invalidReturnCases = Seq(
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    return 1
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo() -> (U256) {
         |    return
         |  }
         |}
         |""".stripMargin
    )
    invalidReturnCases.foreach { code =>
      Compiler.compileContract(code).leftValue.message.startsWith("Invalid return types:") is true
    }

    val succeed = Seq(
      s"""
         |Contract Foo() {
         |  fn foo() -> (U256) {
         |    panic!()
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo(value: U256) -> (U256) {
         |    if (value > 10) {
         |      return 0
         |    } else {
         |      return 1
         |    }
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo(value: U256) -> (U256) {
         |    if (value > 10) {
         |      return 0
         |    } else {
         |      if (value > 20) {
         |        return 1
         |      }
         |      return 2
         |    }
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo(value: U256) -> (U256) {
         |    if (value > 10) {
         |      if (value < 8) {
         |        return 0
         |      } else {
         |        return 1
         |      }
         |    } else {
         |      if (value > 20) {
         |        return 2
         |      } else {
         |        return 3
         |      }
         |    }
         |  }
         |}
         |""".stripMargin
    )
    succeed.foreach { code =>
      Compiler.compileContract(code).isRight is true
    }
  }

  it should "test panic" in new Fixture {
    def code(error: String = "") =
      s"""
         |Contract Foo() {
         |  pub fn foo(x: U256) -> (U256) {
         |    if (x == 0) {
         |      return 0
         |    }
         |    panic!($error)
         |  }
         |}
         |""".stripMargin
    test(code(), AVector(Val.U256(0)), AVector(Val.U256(0)))
    fail(code(), AVector(Val.U256(1)), _ is AssertionFailed)
    fail(code("1"), AVector(Val.U256(2)), _ is a[AssertionFailedWithErrorCode])
  }

  it should "check contract type" in {
    val failed = Seq(
      s"""
         |Contract Foo(bar: Bar) {
         |  fn foo() -> () {
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo(bar: Bar) -> () {
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    let bar = Bar(#00)
         |  }
         |}
         |""".stripMargin
    )
    failed.foreach { code =>
      Compiler.compileContract(code).leftValue is Compiler.Error(
        "Contract Bar does not exist"
      )
    }

    val barContract =
      s"""
         |Contract Bar() {
         |  fn bar() -> () {
         |  }
         |}
         |""".stripMargin
    val succeed = Seq(
      s"""
         |Contract Foo(bar: Bar) {
         |  fn foo() -> () {
         |  }
         |}
         |
         |$barContract
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo(bar: Bar) -> () {
         |  }
         |}
         |
         |$barContract
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    let bar = Bar(#00)
         |  }
         |}
         |
         |$barContract
         |""".stripMargin
    )
    succeed.foreach { code =>
      Compiler.compileContract(code).isRight is true
    }
  }

  trait Fixture {
    def test(
        input: String,
        args: AVector[Val] = AVector.empty,
        output: AVector[Val] = AVector.empty,
        fields: AVector[Val] = AVector.empty,
        methodIndex: Int = 0
    ): Assertion = {
      val compiled = Compiler.compileContractFull(input).rightValue
      compiled.code is compiled.debugCode
      val contract = compiled.code

      deserialize[StatefulContract](serialize(contract)) isE contract
      val (obj, context) = prepareContract(contract, fields)
      StatefulVM.executeWithOutputsWithDebug(context, obj, args, methodIndex) isE output
    }

    def fail(
        input: String,
        args: AVector[Val],
        check: ExeFailure => Assertion,
        fields: AVector[Val] = AVector.empty
    ): Assertion = {
      val contract = Compiler.compileContract(input).toOption.get

      deserialize[StatefulContract](serialize(contract)) isE contract
      val (obj, context) = prepareContract(contract, fields)
      check(StatefulVM.executeWithOutputs(context, obj, args).leftValue.rightValue)
    }
  }

  it should "generate IR code" in new Fixture {
    val input =
      s"""
         |Contract Foo(x: U256) {
         |
         |  pub fn add(a: U256) -> (U256) {
         |    return square(x) + square(a)
         |  }
         |
         |  fn square(n: U256) -> (U256) {
         |    return n * n
         |  }
         |}
         |""".stripMargin

    test(
      input,
      AVector(Val.U256(U256.Two)),
      AVector(Val.U256(U256.unsafe(5))),
      AVector(Val.U256(U256.One))
    )
  }

  it should "verify signature" in {
    def input(hash: Hash) =
      s"""
         |AssetScript P2PKH {
         |  pub fn verify(pk: ByteVec) -> () {
         |    let hash = #${hash.toHexString}
         |    assert!(hash == blake2b!(pk), 0)
         |    verifyTxSignature!(pk)
         |    return
         |  }
         |}
         |""".stripMargin

    val (priKey, pubKey) = SignatureSchema.generatePriPub()
    val pubKeyHash       = Hash.hash(pubKey.bytes)

    val script = Compiler.compileAssetScript(input(pubKeyHash)).rightValue._1
    deserialize[StatelessScript](serialize(script)) isE script

    val args             = AVector[Val](Val.ByteVec.from(pubKey))
    val statelessContext = genStatelessContext(signatures = AVector(Signature.zero))
    val signature        = SignatureSchema.sign(statelessContext.txId.bytes, priKey)
    statelessContext.signatures.pop().rightValue is Signature.zero
    statelessContext.signatures.push(signature) isE ()
    StatelessVM.execute(statelessContext, script.toObject, args).isRight is true
    StatelessVM.execute(statelessContext, script.toObject, args) is
      failed(StackUnderflow) // no signature in the stack
  }

  it should "test while" in new Fixture {
    test(
      s"""
         |Contract While() {
         |  pub fn main() -> (U256) {
         |    let mut x = 5
         |    let mut done = false
         |    while (!done) {
         |      x = x + x - 3
         |      if (x % 5 == 0) { done = true }
         |    }
         |    return x
         |  }
         |}
         |""".stripMargin,
      AVector.empty,
      AVector(Val.U256(U256.unsafe(35)))
    )
  }

  it should "check types for for-loop" in {
    def code(
        initialize: String = "let mut i = 0",
        condition: String = "i < 10",
        update: String = "i = i + 1",
        body: String = "return"
    ): String =
      s"""
         |Contract ForLoop() {
         |  pub fn test() -> () {
         |    for ($initialize; $condition; $update) {
         |      $body
         |    }
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(code()).isRight is true
    Compiler.compileContract(code(initialize = "true")).isLeft is true
    Compiler.compileContract(code(condition = "1")).leftValue.message is
      "Invalid condition type: Const(U256(1))"
    Compiler.compileContract(code(update = "true")).isLeft is true
    Compiler.compileContract(code(body = "")).isLeft is true
    Compiler.compileContract(code(initialize = "")).leftValue.message is
      "No initialize statement in for loop"
    Compiler.compileContract(code(update = "")).leftValue.message is
      "No update statement in for loop"
  }

  it should "test for loop" in new Fixture {
    test(
      s"""
         |Contract ForLoop() {
         |  pub fn main() -> (U256) {
         |    let mut x = 1
         |    for (let mut i = 1; i < 5; i = i + 1) {
         |      x = x * i
         |    }
         |    return x
         |  }
         |}
         |""".stripMargin,
      AVector.empty,
      AVector(Val.U256(U256.unsafe(24)))
    )
    test(
      s"""
         |Contract ForLoop() {
         |  pub fn main() -> (U256) {
         |    let mut x = 5
         |    for (let mut done = false; !done; done = done) {
         |      x = x + x - 3
         |      if (x % 5 == 0) { done = true }
         |    }
         |    return x
         |  }
         |}
         |""".stripMargin,
      AVector.empty,
      AVector(Val.U256(U256.unsafe(35)))
    )
  }

  it should "test the following typical examples" in new Fixture {
    test(
      s"""
         |Contract Main() {
         |
         |  pub fn main() -> () {
         |    let an_i256 = 5i
         |    let an_u256 = 5u
         |
         |    // Or a default will be used.
         |    let default_integer = 7   // `U256`
         |
         |    // A mutable variable's value can be changed.
         |    let mut another_i256 = 5i
         |    let mut another_u256 = 5u
         |    another_i256 = 6i
         |    another_u256 = 6u
         |
         |    let mut bool = true
         |    bool = false
         |  }
         |}
         |""".stripMargin,
      AVector.empty
    )

    test(
      s"""
         |Contract Fibonacci() {
         |  pub fn f(n: I256) -> (I256) {
         |    if (n < 2i) {
         |      return n
         |    } else {
         |      return f(n-1i) + f(n-2i)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.I256(I256.from(10))),
      AVector[Val](Val.I256(I256.from(55)))
    )

    test(
      s"""
         |Contract Fibonacci() {
         |  pub fn f(n: U256) -> (U256) {
         |    if (n < 2u) {
         |      return n
         |    } else {
         |      return f(n-1u) + f(n-2u)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.U256(U256.unsafe(10))),
      AVector[Val](Val.U256(U256.unsafe(55)))
    )

    test(
      s"""
         |Contract Test() {
         |  pub fn main() -> (Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool) {
         |    let b0 = 1 == 1
         |    let b1 = 1 == 2
         |    let b2 = 1 != 2
         |    let b3 = 1 != 1
         |    let b4 = 1 < 2
         |    let b5 = 1 < 0
         |    let b6 = 1 <= 2
         |    let b7 = 1 <= 1
         |    let b8 = 1 > 0
         |    let b9 = 1 > 2
         |    let b10 = 1 >= 0
         |    let b11 = 1 >= 1
         |    return b0, b1, b2, b3, b4, b5, b6, b7, b8, b9, b10, b11
         |  }
         |}
         |""".stripMargin,
      AVector.empty,
      AVector[Val](
        Val.True,
        Val.False,
        Val.True,
        Val.False,
        Val.True,
        Val.False,
        Val.True,
        Val.True,
        Val.True,
        Val.False,
        Val.True,
        Val.True
      )
    )

    test(
      s"""
         |Contract Foo() {
         |  pub fn f(mut n: U256) -> (U256) {
         |    if (n < 2) {
         |      n = n + 1
         |    }
         |    return n
         |  }
         |}
         |""".stripMargin,
      AVector(Val.U256(U256.unsafe(2))),
      AVector[Val](Val.U256(U256.unsafe(2)))
    )
  }

  it should "execute quasi uniswap" in new Fixture {
    val contract =
      s"""
         |Contract Uniswap(
         |  mut alphReserve: U256,
         |  mut btcReserve: U256
         |) {
         |  pub fn exchange(attoAlphAmount: U256) -> (U256) {
         |    let tokenAmount = btcReserve * attoAlphAmount / (alphReserve + attoAlphAmount)
         |    alphReserve = alphReserve + attoAlphAmount
         |    btcReserve = btcReserve - tokenAmount
         |    return tokenAmount
         |  }
         |}
         |""".stripMargin

    test(
      contract,
      AVector(Val.U256(U256.unsafe(1000))),
      AVector(Val.U256(U256.unsafe(99))),
      AVector(Val.U256(U256.unsafe(1000000)), Val.U256(U256.unsafe(100000)))
    )

    test(
      contract,
      AVector(Val.U256(U256.unsafe(1000))),
      AVector(Val.U256(U256.unsafe(99))),
      AVector(
        Val.U256(U256.unsafe(Long.MaxValue) divUnsafe U256.unsafe(10)),
        Val.U256(U256.unsafe(Long.MaxValue) divUnsafe U256.unsafe(100))
      )
    )
  }

  it should "test operator precedence" in new Fixture {
    val contract =
      s"""
         |Contract Operator() {
         |  pub fn main() -> (U256, Bool, Bool) {
         |    let x = 1 + 2 * 3 - 2 / 2
         |    let y = (1 < 2) && (2 <= 2) && (2 < 3)
         |    let z = !false && false || false
         |
         |    return x, y, z
         |  }
         |}
         |""".stripMargin
    test(contract, AVector.empty, AVector(Val.U256(U256.unsafe(6)), Val.True, Val.False))
  }

  it should "compile array failed" in {
    val codes = List(
      s"""
         |// duplicated variable name
         |Contract Foo() {
         |  fn foo() -> () {
         |    let x = 0
         |    let x = [1, 2, 3]
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Local variables have the same name: x",
      s"""
         |// duplicated variable name
         |Contract Foo(x: [U256; 2]) {
         |  fn foo() -> () {
         |    let x = [2; 3]
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Global variable has the same name as local variable: x",
      s"""
         |// assign to immutable array element(contract field)
         |Contract Foo(x: [U256; 2]) {
         |  fn set() -> () {
         |    x[0] = 2
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Assign to immutable variable: x",
      s"""
         |// assign to immutable array element(local variable)
         |Contract Foo() {
         |  fn foo() -> () {
         |    let x = [2; 4]
         |    x[0] = 3
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Assign to immutable variable: x",
      s"""
         |// out of index
         |Contract Foo() {
         |  fn foo() -> (U256) {
         |    let x = [[2; 2]; 4]
         |    return x[1][3]
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index: 3, array size: 2",
      s"""
         |// out of index
         |Contract Foo() {
         |  fn foo() -> () {
         |    let mut x = [2; 2]
         |    x[2] = 3
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index: 2, array size: 2",
      s"""
         |// invalid array element assignment
         |Contract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1, 2]
         |    x[2] = 2
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index: 2, array size: 2",
      s"""
         |// invalid array element assignment
         |Contract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1, 2]
         |    x[0][0] = 2
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Expected array type, got \"U256\"",
      s"""
         |// invalid array expression
         |Contract Foo() {
         |  fn foo() -> () {
         |    let x = [1, 2]
         |    let y = x[0][0]
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Expected array type, got \"U256\"", // TODO: improve this error message
      s"""
         |// invalid array expression
         |Contract Foo() {
         |  fn foo() -> () {
         |    let x = 2
         |    let y = x[0]
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Expected array type, got \"List(U256)\"",
      s"""
         |// invalid binary expression(compare array)
         |Contract Foo() {
         |  fn foo() -> (Bool) {
         |    let x = [3; 2]
         |    let y = [3; 2]
         |    return x == y
         |  }
         |}
         |""".stripMargin ->
        "Invalid param types List(FixedSizeArray(U256,2), FixedSizeArray(U256,2)) for Eq",
      s"""
         |// invalid binary expression(add array)
         |Contract Foo() {
         |  fn foo() -> () {
         |    let x = [2; 2] + [2; 2]
         |    return
         |  }
         |}""".stripMargin ->
        "Invalid param types List(FixedSizeArray(U256,2), FixedSizeArray(U256,2)) for ArithOperator",
      s"""
         |// assign array element with invalid type
         |Contract Foo() {
         |  fn foo() -> () {
         |    let mut x = [3i; 2]
         |    x[0] = 3
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Assign List(U256) to List(I256)",
      s"""
         |Contract Foo() {
         |  fn foo() -> U256 {
         |    let x = [1; 2]
         |    return x[#00]
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index type \"List(ByteVec)\", expected \"U256\"",
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1; 2]
         |    x[-1i] = 0
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index type \"List(I256)\", expected \"U256\"",
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1; 2]
         |    x[1 + 2] = 0
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index: 3, array size: 2"
    )
    codes.foreach { case (code, error) =>
      Compiler.compileContract(code).leftValue.message is error
    }
  }

  it should "test array" in new Fixture {
    {
      info("get array element from array literal")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    assert!([0, 1, 2][2] == 2, 0)
           |    assert!(foo()[1] == 1, 0)
           |    assert!([foo(), foo()][0][0] == 0, 0)
           |  }
           |
           |  fn foo() -> ([U256; 3]) {
           |    return [0, 1, 2]
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("array constant index")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    let array = [1, 2, 3]
           |    assert!(array[0] == 1 && array[1] == 2 && array[2] == 3, 0)
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("assign array element by constant index")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut array = [0; 3]
           |    array[0] = 1
           |    array[1] = 2
           |    array[2] = 3
           |    assert!(array[0] == 1 && array[1] == 2 && array[2] == 3, 0)
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("array variable assignment")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    let x = [1, 2, 3]
           |    let mut y = [0; 3]
           |    assert!(y[0] == 0 && y[1] == 0 && y[2] == 0, 0)
           |    y = x
           |    assert!(y[0] == 1 && y[1] == 2 && y[2] == 3, 0)
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("assign array element by variable index")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut array = [0; 3]
           |    let mut i = 0
           |    while (i < 3) {
           |      array[i] = i + 1
           |      i = i + 1
           |    }
           |    assert!(array[0] == 1 && array[1] == 2 && array[2] == 3, 0)
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("array as function params and return values")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test(mut x: [Bool; 2]) -> ([Bool; 2]) {
           |    x[0] = !x[0]
           |    x[1] = !x[1]
           |    return x
           |  }
           |}
           |""".stripMargin
      test(code, args = AVector(Val.False, Val.True), output = AVector(Val.True, Val.False))
    }

    {
      info("get sub array by constant index")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    let array = [[0, 1, 2, 3], [4, 5, 6, 7]]
           |    check(array[0], 0)
           |    check(array[1], 4)
           |  }
           |
           |  fn check(array: [U256; 4], v: U256) -> () {
           |    assert!(
           |      array[0] == v &&
           |      array[1] == v + 1 &&
           |      array[2] == v + 2 &&
           |      array[3] == v + 3,
           |      0
           |    )
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("get sub array by variable index")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    let array = [[0, 1, 2, 3], [4, 5, 6, 7]]
           |    let mut i = 0
           |    while (i < 2) {
           |      check(array[i], i * 4)
           |      i = i + 1
           |    }
           |  }
           |
           |  fn check(array: [U256; 4], v: U256) -> () {
           |    let mut i = 0
           |    while (i < 4) {
           |      assert!(array[i] == v + i, 0)
           |      i = i + 1
           |    }
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("assign multi-dim array elements by constant index")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut x = [[0; 2]; 2]
           |    x[0][0] = 1
           |    x[0][1] = 2
           |    x[1][0] = 3
           |    x[1][1] = 4
           |    assert!(
           |      x[0][0] == 1 && x[0][1] == 2 &&
           |      x[1][0] == 3 && x[1][1] == 4,
           |      0
           |    )
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("assign multi-dim array elements by variable index")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut x = [[0, 1], [2, 3]]
           |    let mut i = 0
           |    let mut j = 0
           |    while (i < 2) {
           |      while (j < 2) {
           |        x[i][j] = x[i][j] + 1
           |        j = j + 1
           |      }
           |      j = 0
           |      i = i + 1
           |    }
           |    assert!(
           |      x[0][0] == 1 && x[0][1] == 2 &&
           |      x[1][0] == 3 && x[1][1] == 4,
           |      0
           |    )
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("assign sub array by constant index")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut x = [[0; 2]; 2]
           |    x[0] = [0, 1]
           |    x[1] = [2, 3]
           |    assert!(
           |      x[0][0] == 0 && x[0][1] == 1 &&
           |      x[1][0] == 2 && x[1][1] == 3,
           |      0
           |    )
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("assign sub array by variable index")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut x = [[0; 2]; 2]
           |    let mut i = 0
           |    while (i < 2) {
           |      x[i] = [i, i + 1]
           |      i = i + 1
           |    }
           |    i = 0
           |    while (i < 2) {
           |      assert!(x[i][0] == i, 0)
           |      assert!(x[i][1] == i + 1, 1)
           |      i = i + 1
           |    }
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("expression as array index")
      val code =
        s"""
           |Contract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut x = [0, 1, 2, 3]
           |    let num = 4
           |    assert!(x[foo()] == 3, 0)
           |    assert!(x[num / 2] == 2, 0)
           |    assert!(x[num % 3] == 1, 0)
           |    assert!(x[num - 4] == 0, 0)
           |  }
           |
           |  fn foo() -> U256 {
           |    return 3
           |  }
           |}
           |""".stripMargin
      test(code)
    }
  }

  it should "avoid using array index variables whenever possible" in {
    val code =
      s"""
         |Contract Foo() {
         |  fn func0() -> () {
         |    let array0 = [0, 1, 2]
         |    let mut i = 0
         |    while (i < 3) {
         |      assert!(array0[i] == i, 0)
         |      i = i + 1
         |    }
         |
         |    let array1 = [0, 1]
         |    i = 0
         |    while (i < 2) {
         |      assert!(array1[i] == i, 0)
         |      i = i + 1
         |    }
         |  }
         |
         |  fn func1() -> () {
         |    let array0 = [[0; 2]; 3]
         |    let array1 = [[0; 2]; 3]
         |    let mut i = 0
         |    while (i < 3) {
         |      foo(array0[i], array1[i])
         |      i = i + 1
         |    }
         |  }
         |
         |  fn foo(a1: [U256; 2], a2: [U256; 2]) -> () {
         |  }
         |}
         |""".stripMargin

    val contract = Compiler.compileContract(code).rightValue
    // format: off
    contract.methods(0) is Method[StatefulContext](
      isPublic = false,
      usePreapprovedAssets = false,
      useContractAssets = false,
      argsLength = 0,
      localsLength = 6,
      returnLength = 0,
      instrs = AVector[Instr[StatefulContext]](
        U256Const0, U256Const1, U256Const2, StoreLocal(2), StoreLocal(1), StoreLocal(0),
        U256Const0, StoreLocal(3),
        LoadLocal(3), U256Const3, U256Lt, IfFalse(15),
        LoadLocal(3), Dup, U256Const3, U256Lt, Assert, LoadLocalByIndex, LoadLocal(3), U256Eq, U256Const0, AssertWithErrorCode,
        LoadLocal(3), U256Const1, U256Add, StoreLocal(3), Jump(-19),
        U256Const0, U256Const1, StoreLocal(5), StoreLocal(4),
        U256Const0, StoreLocal(3),
        LoadLocal(3), U256Const2, U256Lt, IfFalse(17),
        LoadLocal(3), Dup, U256Const2, U256Lt, Assert, U256Const4, U256Add, LoadLocalByIndex, LoadLocal(3), U256Eq, U256Const0, AssertWithErrorCode,
        LoadLocal(3), U256Const1, U256Add, StoreLocal(3), Jump(-21)
      )
    )
    contract.methods(1) is Method[StatefulContext](
      isPublic = false,
      usePreapprovedAssets = false,
      useContractAssets = false,
      argsLength = 0,
      localsLength = 14,
      returnLength = 0,
      instrs = AVector[Instr[StatefulContext]](
        U256Const0, U256Const0, U256Const0, U256Const0, U256Const0, U256Const0, StoreLocal(5), StoreLocal(4), StoreLocal(3), StoreLocal(2), StoreLocal(1), StoreLocal(0),
        U256Const0, U256Const0, U256Const0, U256Const0, U256Const0, U256Const0, StoreLocal(11), StoreLocal(10), StoreLocal(9), StoreLocal(8), StoreLocal(7), StoreLocal(6),
        U256Const0, StoreLocal(12),
        LoadLocal(12), U256Const3, U256Lt, IfFalse(40),
        LoadLocal(12), Dup, U256Const3, U256Lt, Assert, U256Const2, U256Mul, StoreLocal(13),
        LoadLocal(13), U256Const0, U256Add, LoadLocalByIndex,
        LoadLocal(13), U256Const1, U256Add, LoadLocalByIndex,
        LoadLocal(12), Dup, U256Const3, U256Lt, Assert, U256Const2, U256Mul, U256Const(Val.U256(6)), U256Add, StoreLocal(13),
        LoadLocal(13), U256Const0, U256Add, LoadLocalByIndex,
        LoadLocal(13), U256Const1, U256Add, LoadLocalByIndex,
        CallLocal(2),
        LoadLocal(12), U256Const1, U256Add, StoreLocal(12), Jump(-44)
      )
    )
    // format: on
  }

  it should "abort if variable array index is invalid" in {
    val code =
      s"""
         |Contract Foo(foo: U256, mut array: [[U256; 2]; 3]) {
         |  pub fn test0() -> () {
         |    let mut x = [1, 2, 3, 4]
         |    let mut i = 0
         |    while (i < 5) {
         |      x[i] = 0
         |      i = i + 1
         |    }
         |  }
         |
         |  pub fn test1(idx1: U256, idx2: U256) -> () {
         |    let mut x = [[2; 2]; 3]
         |    x[idx1][idx2] = 0
         |  }
         |
         |  pub fn test2(idx1: U256, idx2: U256) -> () {
         |    array[idx1][idx2] = 0
         |  }
         |
         |  pub fn test3(idx1: U256, idx2: U256) -> (U256) {
         |    return array[idx1][idx2]
         |  }
         |}
         |""".stripMargin

    val contract       = Compiler.compileContract(code).rightValue
    val (obj, context) = prepareContract(contract, AVector.fill(7)(Val.U256(0)))

    def test(methodIndex: Int, args: AVector[Val]) = {
      StatefulVM
        .executeWithOutputs(context, obj, args, methodIndex)
        .leftValue
        .rightValue is AssertionFailed
    }

    test(0, AVector.empty)
    test(1, AVector(Val.U256(0), Val.U256(4)))
    test(1, AVector(Val.U256(3), Val.U256(0)))
    test(2, AVector(Val.U256(0), Val.U256(4)))
    test(2, AVector(Val.U256(3), Val.U256(0)))
    test(3, AVector(Val.U256(0), Val.U256(4)))
    test(3, AVector(Val.U256(3), Val.U256(0)))
  }

  it should "test contract array fields" in new Fixture {
    {
      info("array fields assignment")
      val code =
        s"""
           |Contract ArrayTest(mut array: [[U256; 2]; 4]) {
           |  pub fn test(a: [[U256; 2]; 4]) -> () {
           |    array = a
           |    let mut i = 0
           |    while (i < 4) {
           |      foo(array[i], a[i])
           |      i = i + 1
           |    }
           |  }
           |
           |  fn foo(a: [U256; 2], b:[U256; 2]) -> () {
           |    let mut i = 0
           |    while (i < 2) {
           |      assert!(a[i] == b[i], 0)
           |      i = i + 1
           |    }
           |  }
           |}
           |""".stripMargin
      val args = AVector.from[Val]((0 until 8).map(Val.U256(_)))
      test(code, fields = AVector.fill(8)(Val.U256(0)), args = args)
    }

    {
      info("create array with side effect")
      val code =
        s"""
           |Contract ArrayTest(mut x: U256) {
           |  pub fn test() -> () {
           |    let array0 = [foo(), foo(), foo()]
           |    assert!(x == 3, 0)
           |
           |    let array1 = [foo(), foo(), foo()][0]
           |    assert!(x == 6, 0)
           |
           |    let array2 = [foo(); 3]
           |    assert!(x == 9, 0)
           |  }
           |
           |  fn foo() -> U256 {
           |    x = x + 1
           |    return x
           |  }
           |}
           |""".stripMargin
      test(code, fields = AVector(Val.U256(0)))
    }

    {
      info("assign array element")
      val code =
        s"""
           |Contract ArrayTest(mut array: [[U256; 2]; 4]) {
           |  pub fn test() -> () {
           |    let mut i = 0
           |    let mut j = 0
           |    while (i < 4) {
           |      while (j < 2) {
           |        array[i][j] = i + j
           |        j = j + 1
           |      }
           |      j = 0
           |      i = i + 1
           |    }
           |
           |    i = 0
           |    j = 0
           |    while (i < 4) {
           |      while (j < 2) {
           |        assert!(array[i][j] == i + j, 0)
           |        j = j + 1
           |      }
           |      j = 0
           |      i = i + 1
           |    }
           |  }
           |}
           |""".stripMargin
      test(code, fields = AVector.fill(8)(Val.U256(0)))
    }

    {
      info("avoid executing array indexing instructions multiple times")
      val code =
        s"""
           |Contract Foo(mut array: [[U256; 2]; 4], mut x: U256) {
           |  pub fn test() -> () {
           |    let mut i = 0
           |    while (i < 4) {
           |      array[foo()] = [x; 2]
           |      i = i + 1
           |      assert!(x == i, 0)
           |    }
           |    assert!(x == 4, 0)
           |
           |    i = 0
           |    while (i < 4) {
           |      x = 0
           |      assert!(array[i][foo()] == i, 0)
           |      assert!(array[i][foo()] == i, 0)
           |      i = i + 1
           |      assert!(x == 2, 0)
           |    }
           |  }
           |
           |  fn foo() -> U256 {
           |    let v = x
           |    x = x + 1
           |    return v
           |  }
           |}
           |""".stripMargin
      test(code, fields = AVector.fill(9)(Val.U256(0)))
    }
  }

  it should "get constant array index" in {
    def testConstantFolding(before: String, after: String) = {
      val beforeAst = fastparse.parse(before, StatelessParser.expr(_)).get.value
      val afterAst  = fastparse.parse(after, StatelessParser.expr(_)).get.value
      Compiler.State.getConstantIndex(beforeAst) is afterAst
    }

    testConstantFolding("1 + 1", "2")
    testConstantFolding("2 / 1", "2")
    testConstantFolding("2 - 1", "1")
    testConstantFolding("2 >> 1", "1")
    testConstantFolding("1 << 1", "2")
    testConstantFolding("2 % 3", "2")
    testConstantFolding("2 & 0", "0")
    testConstantFolding("2 | 1", "3")
    testConstantFolding("2 ^ 1", "3")
    testConstantFolding("1 + 2 * 3", "7")
    testConstantFolding("1 * 4 + 4 * i", "4 + 4 * i")
    testConstantFolding("foo()", "foo()")
    testConstantFolding("a + b", "a + b")
    // TODO: optimize following cases
    testConstantFolding("2 * 4 + 4 * i - 2 * 3", "8 + 4 * i - 6")
    testConstantFolding("a + 2 + 3", "a + 2 + 3")
  }

  it should "use the same generated variable for both production and debug code" in {
    val code =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    assert!(bar() == 0, 0)
         |    assert!(bas()[0] == 1, 0)
         |    assert!(bat()[0][1] == 0, 0)
         |  }
         |  fn bar() -> U256 {
         |    return 0
         |  }
         |  fn bas() -> [U256; 3] {
         |    return [0, 1, 2]
         |  }
         |  fn bat() -> [[U256; 3]; 2] {
         |    return [[0, 1, 2]; 2]
         |  }
         |}
         |""".stripMargin
    val compiled = Compiler.compileContractFull(code).rightValue
    compiled.code is compiled.debugCode
  }

  it should "compile return multiple values failed" in {
    val codes = Seq(
      s"""
         |// Assign to immutable variable
         |Contract Foo() {
         |  fn bar() -> (U256, U256) {
         |    return 1, 2
         |  }
         |
         |  pub fn foo() -> () {
         |    let mut a = 0
         |    let b = 1
         |    a, b = bar()
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// Assign ByteVec to U256
         |Contract Foo() {
         |  fn bar() -> (U256, ByteVec) {
         |    return 1, #00
         |  }
         |
         |  pub fn foo() -> () {
         |    let mut a = 0
         |    let mut b = 1
         |    a, b = bar()
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// Assign (U256, U256) to (U256, U256, U256)
         |Contract Foo() {
         |  fn bar() -> (U256, U256) {
         |    return 1, 2
         |  }
         |
         |  pub fn foo() -> () {
         |    let mut a = 0
         |    let mut b = 0
         |    let mut c = 0
         |    a, b, c = bar()
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// Assign (U256, U256, U256) to (U256, U256)
         |Contract Foo() {
         |  fn bar() -> (U256, U256, U256) {
         |    return 1, 2, 3
         |  }
         |
         |  pub fn foo() -> () {
         |    let mut a = 0
         |    let mut b = 0
         |    a, b = bar()
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn bar() -> (U256, U256) {
         |    return 1, 2
         |  }
         |
         |  pub fn foo() -> () {
         |    let (a, mut b, c) = bar()
         |    return
         |  }
         |}
         |""".stripMargin
    )

    codes.foreach(Compiler.compileContract(_).isLeft is true)
  }

  it should "test return multiple values" in new Fixture {
    {
      info("return multiple simple values")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn test() -> () {
           |    let (a, b) = foo()
           |    assert!(a == 1 && b, 0)
           |  }
           |
           |  fn foo() -> (U256, Bool) {
           |    return 1, true
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("test return array and simple values")
      val code =
        s"""
           |Contract Foo(mut array: [U256; 3]) {
           |  pub fn test() -> () {
           |    array = [1, 2, 3]
           |    let mut x = [[0; 3]; 3]
           |    let mut y = [0; 3]
           |    let mut i = 0
           |    while (i < 3) {
           |      x[i], y[i] = foo(i)
           |      i = i + 1
           |    }
           |    assert!(
           |      x[0][0] == 1 && x[0][1] == 2 && x[0][2] == 3 &&
           |      x[1][0] == 2 && x[1][1] == 3 && x[1][2] == 4 &&
           |      x[2][0] == 4 && x[2][1] == 5 && x[2][2] == 6 &&
           |      y[0] == 0 && y[1] == 1 && y[2] == 2,
           |      0
           |    )
           |  }
           |
           |  pub fn foo(value: U256) -> ([U256; 3], U256) {
           |    let mut i = 0
           |    while (i < 3) {
           |      array[i] = array[i] + value
           |      i = i + 1
           |    }
           |    return array, value
           |  }
           |}
           |""".stripMargin
      test(code, fields = AVector.fill(3)(Val.U256(0)))
    }

    {
      info("test return multi-dim array and values")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn test() -> () {
           |    let (array, i) = foo()
           |    assert!(
           |      array[0][0] == 1 && array[0][1] == 2 && array[0][2] == 3 &&
           |      array[1][0] == 4 && array[1][1] == 5 && array[1][2] == 6 &&
           |      i == 7,
           |      0
           |    )
           |  }
           |
           |  fn foo() -> ([[U256; 3]; 2], U256) {
           |    return [[1, 2, 3], [4, 5, 6]], 7
           |  }
           |}
           |""".stripMargin
      test(code)
    }
  }

  it should "return from if block" in new Fixture {
    val code: String =
      s"""
         |Contract Foo(mut value: U256) {
         |  pub fn test() -> U256 {
         |    if (true) {
         |      value = 1
         |      return value
         |    }
         |
         |    value = 2
         |    return value
         |  }
         |}
         |""".stripMargin
    test(code, fields = AVector(Val.U256(0)), output = AVector(Val.U256(1)))
  }

  it should "generate efficient code for arrays" in {
    val code =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    let mut x = [1, 2, 3, 4]
         |    let y = x[0]
         |    return
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(code).rightValue.methods.head is
      Method[StatefulContext](
        isPublic = true,
        usePreapprovedAssets = false,
        useContractAssets = false,
        argsLength = 0,
        localsLength = 5,
        returnLength = 0,
        instrs = AVector[Instr[StatefulContext]](
          U256Const1,
          U256Const2,
          U256Const3,
          U256Const4,
          StoreLocal(3),
          StoreLocal(2),
          StoreLocal(1),
          StoreLocal(0),
          LoadLocal(0),
          StoreLocal(4),
          Return
        )
      )
  }

  it should "parse events definition and emission" in {

    {
      info("event definition and emission")

      val contract =
        s"""
           |Contract Foo() {
           |
           |  event Add(a: U256, b: U256)
           |
           |  pub fn add(a: U256, b: U256) -> (U256) {
           |    emit Add(a, b)
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(contract).isRight is true
    }

    {
      info("multiple event definitions and emissions")

      val contract =
        s"""
           |Contract Foo() {
           |
           |  event Add1(a: U256, b: U256)
           |  event Add2(a: U256, b: U256)
           |
           |  pub fn add(a: U256, b: U256) -> (U256) {
           |    emit Add1(a, b)
           |    emit Add2(a, b)
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(contract).isRight is true
    }

    {
      info("event doesn't exist")

      val contract =
        s"""
           |Contract Foo() {
           |
           |  event Add(a: U256, b: U256)
           |
           |  pub fn add(a: U256, b: U256) -> (U256) {
           |    emit Add2(a, b)
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(contract).leftValue.message is "Event Add2 does not exist"
    }

    {
      info("duplicated event definitions")

      val contract =
        s"""
           |Contract Foo() {
           |
           |  event Add1(a: U256, b: U256)
           |  event Add2(a: U256, b: U256)
           |  event Add3(a: U256, b: U256)
           |  event Add1(b: U256, a: U256)
           |  event Add2(b: U256, a: U256)
           |
           |  pub fn add(a: U256, b: U256) -> (U256) {
           |    emit Add(a, b)
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler
        .compileContract(contract)
        .leftValue
        .message is "These events are defined multiple times: Add1, Add2"
    }

    {
      info("emit event with wrong args")

      val contract =
        s"""
           |Contract Foo() {
           |
           |  event Add(a: U256, b: U256)
           |
           |  pub fn add(a: U256, b: U256) -> (U256) {
           |    let z = false
           |    emit Add(a, z)
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler
        .compileContract(contract)
        .leftValue
        .message is "Invalid args type List(U256, Bool) for event Add(U256, U256)"
    }
  }

  it should "test contract inheritance compilation" in {
    val parent =
      s"""
         |Abstract Contract Parent(mut x: U256) {
         |  event Foo()
         |
         |  pub fn foo() -> () {
         |  }
         |}
         |""".stripMargin

    {
      info("extends from parent contract")

      val child =
        s"""
           |Contract Child(mut x: U256, y: U256) extends Parent(x) {
           |  pub fn bar() -> () {
           |    foo()
           |  }
           |
           |  pub fn emitEvent() -> () {
           |    emit Foo()
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      Compiler.compileContract(child).isRight is true
    }

    {
      info("field does not exist")

      val child =
        s"""
           |Contract Child(mut x: U256, y: U256) extends Parent(z) {
           |  pub fn foo() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      Compiler.compileContract(child).leftValue.message is
        "Inherited field \"z\" does not exist in contract \"Child\""
    }

    {
      info("duplicated function definitions")

      val child =
        s"""
           |Contract Child(mut x: U256, y: U256) extends Parent(x) {
           |  pub fn foo() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      Compiler.compileContract(child).leftValue.message is
        "These functions are implemented multiple times: foo"
    }

    {
      info("duplicated event definitions")

      val child =
        s"""
           |Contract Child(mut x: U256, y: U256) extends Parent(x) {
           |  event Foo()
           |
           |  pub fn bar() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      Compiler.compileContract(child).leftValue.message is
        "These events are defined multiple times: Foo"
    }

    {
      info("invalid field in child contract")

      val child =
        s"""
           |Contract Child(x: U256, y: U256) extends Parent(x) {
           |  pub fn bar() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      Compiler.compileContract(child).leftValue.message is
        "Invalid contract inheritance fields, expected \"List(Argument(Ident(x),U256,true,false))\", got \"List(Argument(Ident(x),U256,false,false))\""
    }

    {
      info("invalid field in parent contract")

      val child =
        s"""
           |Contract Child(mut x: U256, mut y: U256) extends Parent(y) {
           |  pub fn bar() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      Compiler.compileContract(child).leftValue.message is
        "Invalid contract inheritance fields, expected \"List(Argument(Ident(x),U256,true,false))\", got \"List(Argument(Ident(y),U256,true,false))\""
    }

    {
      info("Cyclic inheritance")

      val code =
        s"""
           |Contract A(x: U256) extends B(x) {
           |  fn a() -> () {
           |  }
           |}
           |
           |Contract B(x: U256) extends C(x) {
           |  fn b() -> () {
           |  }
           |}
           |
           |Contract C(x: U256) extends A(x) {
           |  fn c() -> () {
           |  }
           |}
           |""".stripMargin

      Compiler.compileContract(code).leftValue.message is
        "Cyclic inheritance detected for contract A"
    }

    {
      info("extends from multiple parent")

      val code =
        s"""
           |Contract Child(mut x: U256) extends Parent0(x), Parent1(x) {
           |  pub fn foo() -> () {
           |    p0(true, true)
           |    p1(true, true, true)
           |    gp(true)
           |  }
           |}
           |
           |Contract Grandparent(mut x: U256) {
           |  event GP(value: U256)
           |
           |  fn gp(a: Bool) -> () {
           |    x = x + 1
           |    emit GP(x)
           |  }
           |}
           |
           |Abstract Contract Parent0(mut x: U256) extends Grandparent(x) {
           |  fn p0(a: Bool, b: Bool) -> () {
           |    gp(a)
           |  }
           |}
           |
           |Abstract Contract Parent1(mut x: U256) extends Grandparent(x) {
           |  fn p1(a: Bool, b: Bool, c: Bool) -> () {
           |    gp(a)
           |  }
           |}
           |""".stripMargin

      val contract = Compiler.compileContract(code).rightValue
      contract.methodsLength is 4
      contract.methods.map(_.argsLength) is AVector(2, 1, 3, 0)
    }

    {
      info("Follow the same function annotation")

      def code(flag: Boolean) =
        s"""
           |Contract Foo() implements Bar {
           |  @using(externalCallCheck = $flag)
           |  fn bar() -> () {
           |    return
           |  }
           |}
           |Interface Bar {
           |  @using(externalCallCheck = false)
           |  fn bar() -> ()
           |}
           |""".stripMargin
      Compiler.compileContract(code(false)).isRight is true
      Compiler
        .compileContract(code(true))
        .leftValue
        .message is "Function \"bar\" is implemented with wrong signature"
    }
  }

  it should "test interface compilation" in {
    {
      info("Interface should contain at least one function")
      val foo =
        s"""
           |Interface Foo {
           |}
           |""".stripMargin
      val error = Compiler.compileMultiContract(foo).leftValue
      error.message is "No function definition in Interface Foo"
    }

    {
      info("Interface inheritance should not contain duplicated functions")
      val foo =
        s"""
           |Interface Foo {
           |  @using(externalCallCheck = false)
           |  fn foo() -> ()
           |}
           |""".stripMargin
      val bar =
        s"""
           |Interface Bar extends Foo {
           |  @using(externalCallCheck = false)
           |  fn foo() -> ()
           |}
           |
           |$foo
           |""".stripMargin
      val error = Compiler.compileMultiContract(bar).leftValue
      error.message is "These abstract functions are defined multiple times: foo"
    }

    {
      info("Contract should implement interface functions with the same signature")
      val foo =
        s"""
           |Interface Foo {
           |  @using(externalCallCheck = false)
           |  fn foo() -> ()
           |}
           |""".stripMargin
      val bar =
        s"""
           |Contract Bar() implements Foo {
           |  @using(externalCallCheck = false)
           |  pub fn foo() -> () {
           |    return
           |  }
           |}
           |
           |$foo
           |""".stripMargin
      val error = Compiler.compileMultiContract(bar).leftValue
      error.message is "Function \"foo\" is implemented with wrong signature"
    }

    {
      info("Interface inheritance can be chained")
      val a =
        s"""
           |Interface A {
           |  @using(externalCallCheck = false)
           |  pub fn a() -> ()
           |}
           |""".stripMargin
      val b =
        s"""
           |Interface B extends A {
           |  @using(externalCallCheck = false)
           |  pub fn b(x: Bool) -> ()
           |}
           |
           |$a
           |""".stripMargin
      val c =
        s"""
           |Interface C extends B {
           |  @using(externalCallCheck = false)
           |  pub fn c(x: Bool, y: Bool) -> ()
           |}
           |
           |$b
           |""".stripMargin
      val interface =
        Compiler.compileMultiContract(c).rightValue.contracts(0).asInstanceOf[Ast.ContractInterface]
      interface.funcs.map(_.args.length) is Seq(0, 1, 2)

      val code =
        s"""
           |Contract Foo() implements C {
           |  @using(externalCallCheck = false)
           |  pub fn c(x: Bool, y: Bool) -> () {}
           |  @using(externalCallCheck = false)
           |  pub fn a() -> () {}
           |  @using(externalCallCheck = false)
           |  pub fn b(x: Bool) -> () {}
           |  pub fn d(x: Bool, y: Bool, z: Bool) -> () {
           |    a()
           |    b(x)
           |    c(x, y)
           |  }
           |}
           |
           |$c
           |""".stripMargin
      val contract =
        Compiler.compileMultiContract(code).rightValue.contracts(0).asInstanceOf[Ast.Contract]
      contract.funcs.map(_.args.length) is Seq(0, 1, 2, 3)
    }

    {
      info("Contract inherits both interface and contract")
      val foo1: String =
        s"""
           |Abstract Contract Foo1() {
           |  @using(externalCallCheck = false)
           |  fn foo1() -> () {}
           |}
           |""".stripMargin
      val foo2: String =
        s"""
           |Interface Foo2 {
           |  @using(externalCallCheck = false)
           |  fn foo2() -> ()
           |}
           |""".stripMargin
      val bar1: String =
        s"""
           |Contract Bar1() extends Foo1() implements Foo2 {
           |  @using(externalCallCheck = false)
           |  fn foo2() -> () {}
           |}
           |$foo1
           |$foo2
           |""".stripMargin
      val bar2: String =
        s"""
           |Contract Bar2() extends Foo1() implements Foo2 {
           |  @using(externalCallCheck = false)
           |  fn foo2() -> () {}
           |}
           |$foo1
           |$foo2
           |""".stripMargin
      Compiler.compileContract(bar1).isRight is true
      Compiler.compileContract(bar2).isRight is true
    }
  }

  it should "compile TxScript" in {
    val code =
      s"""
         |@using(preapprovedAssets = true, assetsInContract = false)
         |TxScript Main(address: Address, tokenId: ByteVec, tokenAmount: U256, swapContractKey: ByteVec) {
         |  let swap = Swap(swapContractKey)
         |  swap.swapAlph{
         |    address -> tokenId: tokenAmount
         |  }(address, tokenAmount)
         |}
         |
         |Interface Swap {
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn swapAlph(buyer: Address, tokenAmount: U256) -> ()
         |}
         |""".stripMargin
    val script = Compiler.compileTxScript(code).rightValue
    script.toTemplateString() is "0101030001000c{3}1700{0}{1}{2}a3{0}{2}0e0c16000100"
  }

  it should "use braces syntax for functions that uses preapproved assets" in {
    def code(
        bracesPart: String = "{callerAddress!() -> amount}",
        usePreapprovedAssets: Boolean = true,
        useAssetsInContract: Boolean = false
    ): String =
      s"""
         |TxScript Main(fooContractId: ByteVec, amount: U256) {
         |  let foo = Foo(fooContractId)
         |  foo.foo${bracesPart}()
         |}
         |
         |Interface Foo {
         |  @using(preapprovedAssets = $usePreapprovedAssets, assetsInContract = $useAssetsInContract)
         |  pub fn foo() -> ()
         |}
         |""".stripMargin
    Compiler.compileTxScript(code()).isRight is true
    Compiler.compileTxScript(code(bracesPart = "")).leftValue.message is
      "Function `foo` needs preapproved assets, please use braces syntax"
    Compiler.compileTxScript(code(usePreapprovedAssets = false)).leftValue.message is
      "Function `foo` does not use preapproved assets"
    Compiler
      .compileTxScript(code(usePreapprovedAssets = false, useAssetsInContract = true))
      .leftValue
      .message is
      "Function `foo` does not use preapproved assets"
  }

  it should "check if contract assets is used in the function" in {
    def code(useAssetsInContract: Boolean = false, instr: String = "return"): String =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = $useAssetsInContract)
         |  fn foo() -> () {
         |    $instr
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(code()).isRight is true
    Compiler
      .compileContract(code(true, "transferAlphFromSelf!(callerAddress!(), 1 alph)"))
      .isRight is true
    Compiler
      .compileContract(code(false, "transferAlphFromSelf!(callerAddress!(), 1 alph)"))
      .isRight is true
    Compiler
      .compileContract(code(true))
      .leftValue
      .message is "Function \"Foo.foo\" does not use contract assets, but its annotation of contract assets is turn on"
  }

  it should "check types for braces syntax" in {
    def code(
        address: String = "Address",
        amount: String = "U256",
        tokenId: String = "ByteVec",
        tokenAmount: String = "U256"
    ): String =
      s"""
         |TxScript Main(
         |  fooContractId: ByteVec,
         |  address: ${address},
         |  amount: ${amount},
         |  tokenId: ${tokenId},
         |  tokenAmount: ${tokenAmount}
         |) {
         |  let foo = Foo(fooContractId)
         |  foo.foo{address -> amount, tokenId: tokenAmount}()
         |}
         |
         |Interface Foo {
         |  @using(preapprovedAssets = true)
         |  pub fn foo() -> ()
         |}
         |""".stripMargin
    Compiler.compileTxScript(code()).isRight is true
    Compiler.compileTxScript(code(address = "Bool")).leftValue.message is
      "Invalid address type: Variable(Ident(address))"
    Compiler.compileTxScript(code(amount = "Bool")).leftValue.message is
      "Invalid amount type: Some(Variable(Ident(amount)))"
    Compiler.compileTxScript(code(tokenId = "Bool")).leftValue.message is
      "Invalid token amount type: List((Variable(Ident(tokenId)),Variable(Ident(tokenAmount))))"
    Compiler.compileTxScript(code(tokenAmount = "Bool")).leftValue.message is
      "Invalid token amount type: List((Variable(Ident(tokenId)),Variable(Ident(tokenAmount))))"
  }

  it should "compile events with <= 8 fields" in {
    def code(nineFields: Boolean): String = {
      val eventStr = if (nineFields) ", a9: U256" else ""
      val emitStr  = if (nineFields) ", 9" else ""
      s"""
         |Contract Foo(tmp: U256) {
         |  event Foo(a1: U256, a2: U256, a3: U256, a4: U256, a5: U256, a6: U256, a7: U256, a8: U256 $eventStr)
         |
         |  pub fn foo() -> () {
         |    emit Foo(1, 2, 3, 4, 5, 6, 7, 8 $emitStr)
         |    return
         |  }
         |}
         |""".stripMargin
    }
    Compiler.compileContract(code(false)).isRight is true
    Compiler
      .compileContract(code(true))
      .leftValue
      .message is "Max 8 fields allowed for contract events"
  }

  it should "compile if-else statements" in {
    {
      info("Simple if statement")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> () {
           |    if (true) {
           |      return
           |    }
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).rightValue.methods.head.instrs is
        AVector[Instr[StatefulContext]](ConstTrue, IfFalse(1), Return)
    }

    {
      info("Simple if statement without return")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> U256 {
           |    if (true) {
           |      return 1
           |    }
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).leftValue.message is
        "Expected return statement for function \"foo\""
    }

    {
      info("Invalid type of condition expr")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> U256 {
           |    if (0) {
           |      return 0
           |    } else {
           |      return 1
           |    }
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).leftValue.message is
        "Invalid type of condition expr: List(U256)"
    }

    {
      info("Simple if-else statement")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> () {
           |    if (true) {
           |      return
           |    } else {
           |      return
           |    }
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).rightValue.methods.head.instrs is
        AVector[Instr[StatefulContext]](ConstTrue, IfFalse(2), Return, Jump(1), Return)
    }

    {
      info("Simple if-else-if statement")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> () {
           |    if (true) {
           |      return
           |    } else if (false) {
           |      return
           |    } else {
           |      return
           |    }
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).rightValue.methods.head.instrs is
        AVector[Instr[StatefulContext]](
          ConstTrue,
          IfFalse(2),
          Return,
          Jump(5),
          ConstFalse,
          IfFalse(2),
          Return,
          Jump(1),
          Return
        )
    }

    {
      info("Invalid if-else-if statement")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> () {
           |    if (true) {
           |      return
           |    } else if (false) {
           |      return
           |    }
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).leftValue.message is
        "If ... else if constructs should be terminated with an else statement"
    }

    new Fixture {
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo(x: U256) -> (U256) {
           |    if (x == 1) {
           |      return 1
           |    } else if (x == 0) {
           |      return 10
           |    } else {
           |      return 100
           |    }
           |  }
           |}
           |""".stripMargin

      test(code, args = AVector(Val.U256(U256.Zero)), output = AVector(Val.U256(U256.unsafe(10))))
      test(code, args = AVector(Val.U256(U256.One)), output = AVector(Val.U256(U256.unsafe(1))))
      test(code, args = AVector(Val.U256(U256.Two)), output = AVector(Val.U256(U256.unsafe(100))))
    }
  }

  it should "compile if-else expressions" in {
    {
      info("Simple if expression")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> U256 {
           |    return if (true) 0 else 1
           |  }
           |}
           |""".stripMargin

      Compiler.compileContract(code).rightValue.methods.head.instrs is
        AVector[Instr[StatefulContext]](
          ConstTrue,
          IfFalse(2),
          U256Const0,
          Jump(1),
          U256Const1,
          Return
        )
    }

    {
      info("Simple if-else-if expression")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> U256 {
           |    return if (false) 0 else if (true) 1 else 2
           |  }
           |}
           |""".stripMargin

      Compiler.compileContract(code).rightValue.methods.head.instrs is
        AVector[Instr[StatefulContext]](
          ConstFalse,
          IfFalse(2),
          U256Const0,
          Jump(5),
          ConstTrue,
          IfFalse(2),
          U256Const1,
          Jump(1),
          U256Const2,
          Return
        )
    }

    {
      info("Invalid if-else expression types")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> U256 {
           |    return if (false) 1 else #00
           |  }
           |}
           |""".stripMargin

      Compiler.compileContract(code).leftValue.message is
        "Invalid types of if-else expression branches, expected \"List(ByteVec)\", got \"List(U256)\""
    }

    {
      info("If-else expressions have no else branch")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> U256 {
           |    return if (false) 1
           |  }
           |}
           |""".stripMargin

      Compiler.compileContract(code).leftValue.message is
        "If else expressions should be terminated with an else branch"
    }

    {
      info("Invalid type of condition expr")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> U256 {
           |    return if (0) 0 else 1
           |  }
           |}
           |""".stripMargin

      Compiler.compileContract(code).leftValue.message is
        "Invalid type of condition expr: List(U256)"
    }

    new Fixture {
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo(x: U256) -> U256 {
           |    return if (x == 1) 1 else if (x == 0) 10 else 100
           |  }
           |}
           |""".stripMargin

      test(code, args = AVector(Val.U256(U256.Zero)), output = AVector(Val.U256(U256.unsafe(10))))
      test(code, args = AVector(Val.U256(U256.One)), output = AVector(Val.U256(U256.unsafe(1))))
      test(code, args = AVector(Val.U256(U256.Two)), output = AVector(Val.U256(U256.unsafe(100))))
    }
  }

  it should "compile contract constant variables failed" in {
    val code =
      s"""
         |Contract Foo() {
         |  const C = 0
         |  const C = true
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin
    Compiler.compileContract(code).leftValue.message is
      "These constant variables are defined multiple times: C"
  }

  it should "test contract constant variables" in new Fixture {
    {
      info("Contract constant variables")
      val foo =
        s"""
           |Contract Foo() {
           |  const C0 = 0
           |  const C1 = #00
           |  pub fn foo() -> () {
           |    assert!(C0 == 0, 0)
           |    assert!(C1 == #00, 0)
           |  }
           |}
           |""".stripMargin
      test(foo)
    }

    {
      info("Inherit constant variables from parents")
      val address = Address.p2pkh(PublicKey.generate).toBase58
      val bar =
        s"""
           |Contract Bar() extends Foo() {
           |  const C2 = 1i
           |  const C3 = @$address
           |  pub fn bar() -> () {
           |    assert!(C0 == 0, 0)
           |    assert!(C1 == #00, 0)
           |    assert!(C2 == 1i, 0)
           |    assert!(C3 == @$address, 0)
           |  }
           |}
           |
           |Abstract Contract Foo() {
           |  const C0 = 0
           |  const C1 = #00
           |}
           |""".stripMargin

      test(bar, methodIndex = 0)
    }
  }

  it should "compile contract enum failed" in {
    {
      info("Duplicated enum definitions")
      val code =
        s"""
           |Contract Foo() {
           |  enum ErrorCodes {
           |    Error0 = 0
           |  }
           |  enum ErrorCodes {
           |    Error1 = 1
           |  }
           |  pub fn foo() -> () {}
           |}
           |""".stripMargin
      Compiler.compileContract(code).leftValue.message is
        "These enums are defined multiple times: ErrorCodes"
    }

    {
      info("Enum field does not exist")
      val code =
        s"""
           |Contract Foo() {
           |  enum ErrorCodes {
           |    Error0 = 0
           |  }
           |  pub fn foo() -> U256 {
           |    return ErrorCodes.Error1
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).leftValue.message is
        "Variable foo.ErrorCodes.Error1 does not exist"
    }
  }

  it should "test contract enums" in new Fixture {
    {
      info("Contract enums")
      val foo =
        s"""
           |Contract Foo() {
           |  enum FooErrorCodes {
           |    Error0 = 0
           |    Error1 = 1
           |  }
           |  pub fn foo() -> () {
           |    assert!(FooErrorCodes.Error0 == 0, 0)
           |    assert!(FooErrorCodes.Error1 == 1, 0)
           |  }
           |}
           |""".stripMargin
      test(foo)
    }

    {
      info("Inherit enums from parents")
      val bar =
        s"""
           |Contract Bar() extends Foo() {
           |  enum BarValues {
           |    Value0 = #00
           |    Value1 = #01
           |  }
           |  pub fn bar() -> () {
           |    assert!(FooErrorCodes.Error0 == 0, 0)
           |    assert!(FooErrorCodes.Error1 == 1, 0)
           |    assert!(BarValues.Value0 == #00, 0)
           |    assert!(BarValues.Value1 == #01, 0)
           |  }
           |}
           |
           |Abstract Contract Foo() {
           |  enum FooErrorCodes {
           |    Error0 = 0
           |    Error1 = 1
           |  }
           |}
           |""".stripMargin
      test(bar, methodIndex = 0)
    }
  }

  it should "check unused variables" in {
    {
      info("Check unused local variables in AssetScript")
      val code =
        s"""
           |AssetScript Foo {
           |  @using(updateFields = false)
           |  pub fn foo(a: U256) -> U256 {
           |    let b = 1
           |    let c = 2
           |    return c
           |  }
           |}
           |""".stripMargin
      Compiler.compileAssetScript(code).rightValue._2 is
        AVector("Found unused variables in Foo: foo.a, foo.b")
    }

    {
      info("Check unused local variables in TxScript")
      val code =
        s"""
           |@using(updateFields = false)
           |TxScript Foo {
           |  let b = 1
           |  foo()
           |
           |  @using(updateFields = false)
           |  fn foo() -> () {
           |  }
           |}
           |""".stripMargin
      Compiler.compileTxScriptFull(code).rightValue.warnings is
        AVector("Found unused variables in Foo: main.b")
    }

    {
      info("Check unused template variables in TxScript")
      val code =
        s"""
           |@using(updateFields = false)
           |TxScript Foo(a: U256, b: U256) {
           |  foo(a)
           |
           |  @using(updateFields = false)
           |  fn foo(v: U256) -> () {
           |    assert!(v == 0, 0)
           |  }
           |}
           |""".stripMargin
      Compiler.compileTxScriptFull(code).rightValue.warnings is
        AVector("Found unused fields in Foo: b")
    }

    {
      info("Check unused local variables in Contract")
      val code =
        s"""
           |Contract Foo() {
           |  @using(updateFields = false)
           |  pub fn foo(a: U256) -> U256 {
           |    let b = 1
           |    let c = 0
           |    return c
           |  }
           |}
           |""".stripMargin
      Compiler.compileContractFull(code).rightValue.warnings is
        AVector("Found unused variables in Foo: foo.a, foo.b")
    }

    {
      info("Check unused fields in Contract")
      val code =
        s"""
           |Contract Foo(a: ByteVec, b: U256, c: [U256; 2]) {
           |  @using(updateFields = false)
           |  pub fn getB() -> U256 {
           |    return b
           |  }
           |}
           |""".stripMargin
      Compiler.compileContractFull(code).rightValue.warnings is
        AVector("Found unused fields in Foo: a, c")
    }

    {
      info("Check unused fields in contract inheritance")
      val code =
        s"""
           |Contract Foo(a: U256, b: U256, c: [U256; 2]) extends Bar(a, b) {
           |  @using(updateFields = false)
           |  pub fn foo() -> () {}
           |}
           |
           |Abstract Contract Bar(a: U256, b: U256) {
           |  @using(updateFields = false)
           |  pub fn bar() -> U256 {
           |    return a
           |  }
           |}
           |""".stripMargin
      Compiler.compileContractFull(code).rightValue.warnings is
        AVector("Found unused fields in Foo: b, c")
    }

    {
      info("No warnings for used fields")
      val code =
        s"""
           |Interface I {
           |  @using(updateFields = false, externalCallCheck = false)
           |  pub fn i() -> ()
           |}
           |Abstract Contract Base(v: U256) {
           |  @using(updateFields = false)
           |  fn base() -> () {
           |    assert!(v == 0, 0)
           |  }
           |}
           |Contract Bar(v: U256) extends Base(v) implements I {
           |  @using(updateFields = false, externalCallCheck = false)
           |  pub fn i() -> () {
           |    assert!(v == 0, 0)
           |    base()
           |  }
           |}
           |Contract Foo(v: U256) extends Base(v) {
           |  @using(updateFields = false)
           |  pub fn foo() -> () {
           |    base()
           |  }
           |}
           |""".stripMargin
      val result = Compiler.compileProject(code).rightValue
      result._1.flatMap(_.warnings) is AVector.empty[String]
    }

    {
      info("Unused variable in abstract contract")
      val code =
        s"""
           |Abstract Contract Foo() {
           |  @using(updateFields = false)
           |  pub fn foo(x: U256) -> () {}
           |}
           |Contract Bar() extends Foo() {}
           |Contract Baz() extends Foo() {}
           |""".stripMargin
      val result = Compiler.compileProject(code).rightValue
      result._1.flatMap(_.warnings) is AVector(
        "Found unused variables in Bar: foo.x",
        "Found unused variables in Baz: foo.x"
      )
    }

    {
      info("Check unused constants in Contract")
      val code =
        s"""
           |Contract Foo() {
           |  const C0 = 0
           |  const C1 = 1
           |  @using(updateFields = false)
           |  pub fn foo() -> () {
           |    assert!(C1 == 1, 0)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContractFull(code).rightValue.warnings is
        AVector("Found unused constants in Foo: C0")
    }

    {
      info("Check unused enums in Contract")
      val code =
        s"""
           |Contract Foo() {
           |  enum Chain {
           |    Alephium = 0
           |    Eth = 1
           |  }
           |
           |  enum Language {
           |    Ralph = #00
           |    Solidity = #01
           |  }
           |
           |  @using(updateFields = false)
           |  pub fn foo() -> () {
           |    assert!(Chain.Alephium == 0, 0)
           |    assert!(Language.Ralph == #00, 0)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContractFull(code).rightValue.warnings is
        AVector("Found unused constants in Foo: Chain.Eth, Language.Solidity")
    }

    {
      info("No warnings for multiple contracts inherit from the same parent")
      val code =
        s"""
           |Abstract Contract Foo() {
           |  @using(updateFields = false)
           |  fn foo(x: U256) -> () {
           |    assert!(x == 0, 0)
           |  }
           |}
           |Contract Bar() extends Foo() {
           |  @using(updateFields = false)
           |  pub fn bar() -> () { foo(0) }
           |}
           |Contract Baz() extends Foo() {
           |  @using(updateFields = false)
           |  pub fn baz() -> () { foo(0) }
           |}
           |""".stripMargin
      val (contracts, _) = Compiler.compileProject(code).rightValue
      contracts.length is 2
      contracts.foreach(_.warnings.isEmpty is true)
    }
  }

  it should "test anonymous variable definitions" in new Fixture {
    {
      info("Single anonymous variable")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> () {
           |    let _ = 0
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).rightValue.methods.head.instrs is
        AVector[Instr[StatefulContext]](
          U256Const0,
          Pop
        )
    }

    {
      info("Pop values for simple anonymous variables")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> U256 {
           |    let (_, a, _) = bar()
           |    return a
           |  }
           |
           |  pub fn bar() -> (U256, U256, U256) {
           |    return 0, 1, 2
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).rightValue.methods.head.instrs is
        AVector[Instr[StatefulContext]](
          CallLocal(1),
          Pop,
          StoreLocal(0),
          Pop,
          LoadLocal(0),
          Return
        )
    }

    {
      info("Pop values for anonymous array variables")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> () {
           |    let (a, b, c, d) = bar()
           |    assert!(a == 0 && b[0] == 1 && b[1] == 2 && c== 3, 0)
           |    assert!(d[0][0] == 4 && d[0][1] == 5 && d[1][0] == 6 && d[1][1] == 7, 0)
           |
           |    let (e, _, f, _) = bar()
           |    assert!(e == 0 && f == 3, 0)
           |
           |    let (_, g, _, _) = bar()
           |    assert!(g[0] == 1 && g[1] == 2, 0)
           |
           |    let (_, _, _, h) = bar()
           |    assert!(h[0][0] == 4 && h[0][1] == 5 && h[1][0] == 6 && h[1][1] == 7, 0)
           |  }
           |
           |  pub fn bar() -> (U256, [U256; 2], U256, [[U256; 2]; 2]) {
           |    return 0, [1, 2], 3, [[4, 5], [6, 7]]
           |  }
           |}
           |""".stripMargin
      test(code, AVector.empty)
    }
  }

  it should "not generate code for abstract contract" in {
    val foo =
      s"""
         |Abstract Contract Foo() {
         |  pub fn foo() -> () {}
         |  pub fn bar() -> () {}
         |}
         |""".stripMargin
    Compiler.compileContract(foo).leftValue.message is
      "Code generation is not supported for abstract contract \"Foo\""
  }

  "unused constants and enums" should "have no effect on code generation" in {
    val foo =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin

    val bar =
      s"""
         |Contract Bar() {
         |  const C0 = 0
         |  const C1 = 1
         |  enum Errors {
         |    Error0 = 0
         |    Error1 = 1
         |  }
         |  pub fn bar() -> () {}
         |}
         |""".stripMargin

    val fooContract = Compiler.compileContract(foo).rightValue
    val barContract = Compiler.compileContract(bar).rightValue
    fooContract is barContract
  }

  it should "parse unused variables and fields" in {
    def code(unused: String) =
      s"""
         |Contract Foo($unused a: U256, $unused b: [U256; 2]) {
         |  @using(updateFields = false)
         |  pub fn foo($unused x: U256, $unused y: [U256; 2]) -> () {
         |    return
         |  }
         |}
         |""".stripMargin

    {
      info("Fields and variables are unused")
      val warnings = Compiler.compileContractFull(code("")).rightValue.warnings
      warnings.toSet is Set(
        "Found unused variables in Foo: foo.x, foo.y",
        "Found unused fields in Foo: a, b"
      )
    }

    {
      info("Fields and variables are annotated as unused")
      val warnings = Compiler.compileContractFull(code("@unused")).rightValue.warnings
      warnings.isEmpty is true
    }
  }

  it should "test compile update fields functions" in {
    {
      info("Skip check update fields for script main function")
      val code =
        s"""
           |TxScript Main {
           |  assert!(true, 0)
           |}
           |""".stripMargin
      val warnings = Compiler.compileTxScriptFull(code).rightValue.warnings
      warnings.isEmpty is true
    }

    {
      info("Simple update fields functions")
      val code =
        s"""
           |Contract Foo() {
           |  @using(updateFields = false)
           |  pub fn foo() -> () {}
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("Function changes the contract state, but has `updateFields = false`")
      val code =
        s"""
           |Contract Foo(mut a: U256) {
           |  @using(updateFields = false)
           |  pub fn foo() -> () {
           |    a = 0
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).leftValue.message is
        "Function \"Foo.foo\" changes state, but has `updateFields = false`"
    }

    {
      info("Call internal update fields functions")
      val code =
        s"""
           |Contract Foo() {
           |  @using(updateFields = false)
           |  pub fn foo() -> () {
           |    bar()
           |  }
           |  @using(updateFields = false)
           |  pub fn bar() -> () {}
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("Call builtin functions")
      val code =
        s"""
           |Contract Foo() {
           |  @using(updateFields = false)
           |  pub fn foo() -> () {
           |    let _ = selfContractId!()
           |    transferAlphToSelf!(callerAddress!(), 1 alph)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("Migrate contract with fields")
      val code =
        s"""
           |Contract Foo() {
           |  @using(updateFields = false)
           |  pub fn foo(code: ByteVec, fields: ByteVec) -> () {
           |    migrateWithFields!(code, fields)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).leftValue.message is
        "Function \"Foo.foo\" changes state, but has `updateFields = false`"
    }

    {
      info("Invalid internal function calls")
      val code =
        s"""
           |Contract Foo() {
           |  @using(updateFields = false)
           |  pub fn foo() -> () {
           |    bar()
           |  }
           |  @using(updateFields = true)
           |  pub fn bar() -> () {}
           |}
           |""".stripMargin
      Compiler.compileContract(code).leftValue.message is
        "Function \"Foo.foo\" has internal update fields calls: \"bar\""
    }

    {
      info("Invalid external function calls")
      val code =
        s"""
           |Contract Foo(bar: Bar) {
           |  @using(updateFields = false)
           |  pub fn foo() -> () {
           |    bar.bar()
           |  }
           |}
           |Contract Bar(mut x: [U256; 2]) {
           |  pub fn bar() -> () {
           |    x[0] = 0
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).leftValue.message is
        "Function \"Foo.foo\" has external update fields calls: \"Bar.bar\""
    }

    {
      info("Emit events does not update fields")
      val code =
        s"""
           |Contract Foo() {
           |  event E(v: U256)
           |  pub fn foo() -> () {
           |    checkCaller!(true, 0)
           |    emit E(0)
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings is AVector(
        "Function Foo.foo does not update fields, please use @using(updateFields = false) for the function"
      )
    }

    {
      info("Function use preapproved assets but does not update fields")
      val code =
        s"""
           |Contract Foo() {
           |  @using(updateFields = false, preapprovedAssets = true)
           |  pub fn foo(tokenId: ByteVec) -> () {
           |    assert!(tokenRemaining!(callerAddress!(), tokenId) == 1, 0)
           |    assert!(alphRemaining!(callerAddress!()) == 1, 0)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("Invalid mutual function calls")
      val code =
        s"""
           |Contract Foo(bar: Bar, mut a: U256) {
           |  @using(updateFields = false)
           |  pub fn foo() -> () {
           |    bar.bar()
           |  }
           |  pub fn update() -> () {
           |    a = 0
           |  }
           |}
           |Contract Bar(foo: Foo) {
           |  @using(updateFields = false)
           |  pub fn bar() -> () {
           |    foo.update()
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).leftValue.message is
        "Function \"Bar.bar\" has external update fields calls: \"Foo.update\""
    }

    {
      info("Invalid interface function calls")
      def code(updateFields: Boolean): String =
        s"""
           |Contract Foo() {
           |  @using(updateFields = false)
           |  pub fn foo(contractId: ByteVec) -> () {
           |    Bar(contractId).bar()
           |  }
           |}
           |Interface Bar {
           |  @using(updateFields = $updateFields)
           |  pub fn bar() -> ()
           |}
           |""".stripMargin
      Compiler.compileContractFull(code(false)).isRight is true
      val error = Compiler.compileContractFull(code(true)).leftValue
      error.message is "Function \"Foo.foo\" has external update fields calls: \"Bar.bar\""
    }

    {
      info("Warning for functions which does not update fields")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> () {
           |    checkCaller!(true, 0)
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings is AVector(
        "Function Foo.foo does not update fields, please use @using(updateFields = false) for the function"
      )
    }

    {
      info(
        "Warning for public functions which has no external call check and no update fields annotation"
      )
      def code(annotation: String, modifier: String): String =
        s"""
           |Contract Foo(mut a: U256) {
           |  $annotation
           |  $modifier fn foo() -> () {
           |    a = a + 1
           |  }
           |
           |  pub fn bar() -> () {
           |    checkCaller!(true, 0)
           |    foo()
           |  }
           |}
           |""".stripMargin

      Compiler.compileContractFull(code("", "pub"), 0).rightValue.warnings is AVector(
        "No update fields annotation for function: Foo.foo, please use @using(updateFields = true/false) for the function"
      )
      Compiler.compileContractFull(code("", ""), 0).rightValue.warnings.isEmpty is true

      Compiler
        .compileContractFull(code("@using(externalCallCheck = true)", "pub"), 0)
        .rightValue
        .warnings is AVector(
        "No update fields annotation for function: Foo.foo, please use @using(updateFields = true/false) for the function"
      )
      Compiler
        .compileContractFull(code("@using(externalCallCheck = true)", ""), 0)
        .rightValue
        .warnings
        .isEmpty is true

      Compiler
        .compileContractFull(
          code("@using(externalCallCheck = true, updateFields = true)", "pub"),
          0
        )
        .rightValue
        .warnings
        .isEmpty is true
      Compiler
        .compileContractFull(code("@using(externalCallCheck = true, updateFields = true)", ""), 0)
        .rightValue
        .warnings
        .isEmpty is true
    }

    {
      info("No warning for functions which has external call check")
      def code(annotation: String): String =
        s"""
           |Contract Foo(mut a: U256) {
           |  $annotation
           |  pub fn foo() -> () {
           |    checkCaller!(true, 0)
           |    a = a + 1
           |  }
           |}
           |""".stripMargin
      Compiler.compileContractFull(code(""), 0).rightValue.warnings.isEmpty is true

      Compiler
        .compileContractFull(code("@using(updateFields = true)"), 0)
        .rightValue
        .warnings
        .isEmpty is true
    }
  }

  trait MultiContractFixture {
    val code =
      s"""
         |Abstract Contract Common() {
         |  pub fn c() -> () {}
         |}
         |Contract Foo() extends Common() {
         |  pub fn foo() -> () {}
         |}
         |TxScript M1(id: ByteVec) {
         |  Foo(id).foo()
         |}
         |Contract Bar() extends Common() {
         |  pub fn bar() -> () {}
         |}
         |TxScript M2(id: ByteVec) {
         |  Bar(id).bar()
         |}
         |""".stripMargin
    val multiContract = Compiler.compileMultiContract(code).rightValue
  }

  it should "compile all contracts" in new MultiContractFixture {
    val contracts = multiContract.genStatefulContracts()(CompilerOptions.Default)
    contracts.length is 2
    contracts(0)._1.ast.ident.name is "Foo"
    contracts(0)._2 is 1
    contracts(1)._1.ast.ident.name is "Bar"
    contracts(1)._2 is 3
  }

  it should "compile all scripts" in new MultiContractFixture {
    val scripts = multiContract.genStatefulScripts()(CompilerOptions.Default)
    scripts.length is 2
    scripts(0).ast.ident.name is "M1"
    scripts(1).ast.ident.name is "M2"
  }
}
