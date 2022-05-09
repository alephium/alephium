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

package org.alephium.protocol.vm.lang

import org.scalatest.Assertion

import org.alephium.protocol.{Hash, Signature, SignatureSchema}
import org.alephium.protocol.config.CompilerConfig
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
         |  pub fn bar(a: U256, b: U256) -> (U256) {
         |    return (a + b)
         |  }
         |}
         |""".stripMargin
    Compiler.compileAssetScript(script).isRight is true
  }

  it should "parse tx script" in {
    {
      info("success")

      val script =
        s"""
           |// comment
           |TxScript Foo {
           |  pub fn bar(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      Compiler.compileTxScript(script).isRight is true
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
        .message is """Parser failed: Parsed.Failure(Position 3:3, found "event Add(")"""
    }
  }

  it should "parse contracts" in {
    {
      info("success")

      val contract =
        s"""
           |// comment
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
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
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
           |  event Add(a: U256, b: U256)
           |}
           |""".stripMargin
      Compiler
        .compileContract(contract)
        .leftValue
        .message is "No function definition in TxContract Foo"
    }

    {
      info("duplicated function definitions")

      val contract =
        s"""
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
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
        .message is "These functions are defined multiple times: add1, add2"
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
         |TxContract Foo($xMut x: U256) {
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
         |TxContract Foo() {
         |  fn foo(bar: Bar) -> () {
         |    return bar.bar()
         |  }
         |
         |  pub fn bar() -> () {
         |    return
         |  }
         |}
         |
         |TxScript Bar {
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
    Compiler.compileTxScript(input, 1).isRight is true
  }

  it should "check function return types" in {
    val failed = Seq(
      s"""
         |TxContract Foo() {
         |  fn foo() -> (U256) {
         |  }
         |}
         |""".stripMargin,
      s"""
         |TxContract Foo() {
         |  fn foo(value: U256) -> (U256) {
         |    if (value > 10) {
         |      return 1
         |    }
         |  }
         |}
         |""".stripMargin,
      s"""
         |TxContract Foo() {
         |  fn foo() -> (U256) {
         |    let mut x = 0
         |    return 0
         |    x = 1
         |  }
         |}
         |""".stripMargin,
      s"""
         |TxContract Foo() {
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
    failed.foreach { code =>
      Compiler.compileContract(code).leftValue is Compiler.Error(
        "Expect return statement for function foo"
      )
    }

    val succeed = Seq(
      s"""
         |TxContract Foo() {
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
         |TxContract Foo() {
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
         |TxContract Foo() {
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

  it should "check contract type" in {
    val failed = Seq(
      s"""
         |TxContract Foo(bar: Bar) {
         |  fn foo() -> () {
         |  }
         |}
         |""".stripMargin,
      s"""
         |TxContract Foo() {
         |  fn foo(bar: Bar) -> () {
         |  }
         |}
         |""".stripMargin,
      s"""
         |TxContract Foo() {
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
         |TxContract Bar() {
         |  fn bar() -> () {
         |  }
         |}
         |""".stripMargin
    val succeed = Seq(
      s"""
         |TxContract Foo(bar: Bar) {
         |  fn foo() -> () {
         |  }
         |}
         |
         |$barContract
         |""".stripMargin,
      s"""
         |TxContract Foo() {
         |  fn foo(bar: Bar) -> () {
         |  }
         |}
         |
         |$barContract
         |""".stripMargin,
      s"""
         |TxContract Foo() {
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
        args: AVector[Val],
        output: AVector[Val] = AVector.empty,
        fields: AVector[Val] = AVector.empty
    ): Assertion = {
      val contract = Compiler.compileContract(input).toOption.get

      deserialize[StatefulContract](serialize(contract)) isE contract
      val (obj, context) = prepareContract(contract, fields)
      StatefulVM.executeWithOutputs(context, obj, args) isE output
    }
  }

  it should "generate IR code" in new Fixture {
    val input =
      s"""
         |TxContract Foo(x: U256) {
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
         |    assert!(hash == blake2b!(pk))
         |    verifyTxSignature!(pk)
         |    return
         |  }
         |}
         |""".stripMargin

    val (priKey, pubKey) = SignatureSchema.generatePriPub()
    val pubKeyHash       = Hash.hash(pubKey.bytes)

    val script = Compiler.compileAssetScript(input(pubKeyHash)).rightValue
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

  it should "converse values" in new Fixture {
    test(
      s"""
         |TxContract Conversion() {
         |  pub fn main() -> () {
         |    let mut x = 5u
         |    let mut y = 5i
         |    x = u256!(y)
         |    y = i256!(x)
         |  }
         |}
         |""".stripMargin,
      AVector.empty
    )
  }

  it should "test while" in new Fixture {
    test(
      s"""
         |TxContract While() {
         |  pub fn main() -> (U256) {
         |    let mut x = 5
         |    let mut done = false
         |    while !done {
         |      x = x + x - 3
         |      if x % 5 == 0 { done = true }
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
         |TxContract Main() {
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
         |TxContract Fibonacci() {
         |  pub fn f(n: I256) -> (I256) {
         |    if n < 2i {
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
         |TxContract Fibonacci() {
         |  pub fn f(n: U256) -> (U256) {
         |    if n < 2u {
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
         |TxContract Test() {
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
         |TxContract Foo() {
         |  pub fn f(mut n: U256) -> (U256) {
         |    if n < 2 {
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
         |TxContract Uniswap(
         |  mut alphReserve: U256,
         |  mut btcReserve: U256
         |) {
         |  pub fn exchange(alphAmount: U256) -> (U256) {
         |    let tokenAmount = btcReserve * alphAmount / (alphReserve + alphAmount)
         |    alphReserve = alphReserve + alphAmount
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
         |TxContract Operator() {
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
         |TxContract Foo() {
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
         |TxContract Foo(x: [U256; 2]) {
         |  fn foo() -> () {
         |    let x = [2; 3]
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Global variable has the same name as local variable: x",
      s"""
         |// assign to immutable array element(contract field)
         |TxContract Foo(x: [U256; 2]) {
         |  fn set() -> () {
         |    x[0] = 2
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Assign to immutable variable: x",
      s"""
         |// assign to immutable array element(local variable)
         |TxContract Foo() {
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
         |TxContract Foo() {
         |  fn foo() -> (U256) {
         |    let x = [[2; 2]; 4]
         |    return x[1][3]
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index: 3, array size: 2",
      s"""
         |// out of index
         |TxContract Foo() {
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
         |TxContract Foo() {
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
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1, 2]
         |    x[0][0] = 2
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Invalid assignment to array: x",
      s"""
         |// invalid array expression
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let x = [1, 2]
         |    let y = x[0][0]
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Expect array type, have: List(U256)", // TODO: improve this error message
      s"""
         |// invalid array expression
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let x = 2
         |    let y = x[0]
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Expect array type, have: List(U256)",
      s"""
         |// invalid binary expression(compare array)
         |TxContract Foo() {
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
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let x = [2; 2] + [2; 2]
         |    return
         |  }
         |}""".stripMargin ->
        "Invalid param types List(FixedSizeArray(U256,2), FixedSizeArray(U256,2)) for ArithOperator",
      s"""
         |// assign array element with invalid type
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let mut x = [3i; 2]
         |    x[0] = 3
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Assign List(U256) to List(I256)"
    )
    codes.foreach { case (code, error) =>
      Compiler.compileContract(code).leftValue.message is error
    }
  }

  it should "compile loop failed" in {
    val codes = List(
      s"""
         |// invalid loop step
         |TxContract Foo() {
         |  fn bar(value: U256) -> () {
         |    return
         |  }
         |
         |  fn foo() -> () {
         |    loop(1, 4, 0, bar(?))
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// nested loop
         |TxContract Foo() {
         |  fn bar(value: U256) -> () {
         |    return
         |  }
         |
         |  fn foo() -> () {
         |    loop(1, 4, 1, loop(1, 4, 1, bar(?)))
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// invalid placeholder
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1, 2, 3]
         |    x[0] = ?
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// invalid placeholder
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1, 2, 3]
         |    loop(4, 0, -1, x[? - 1] = ?)
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// invalid array index
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1, 2, 3]
         |    loop(4, 0, -1, x[?] = ?)
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// cannot define new variable in loop
         |TxContract Foo() {
         |  fn foo() -> () {
         |    loop(0, 4, 1, let x = ?)
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// cannot return in loop
         |TxContract Foo() {
         |  fn foo() -> () {
         |    loop(0, 4, 1, return ?)
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// loop body must be statements
         |TxContract Foo() {
         |  fn foo() -> () {
         |    loop(0, 4, 1, ?)
         |    return
         |  }
         |}
         |""".stripMargin,
      s"""
         |// only allow one statement in loop body
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let mut a = 0
         |    let mut b = 1
         |    loop(0, 4, 1,
         |      a = a + ?
         |      b = b + ?
         |    )
         |    return
         |  }
         |}
         |""".stripMargin
    )
    codes.foreach(code => Compiler.compileContract(code).isLeft is true)
  }

  trait TestContractMethodFixture {
    def code: String
    def fields: AVector[Val] = AVector.empty

    lazy val contract       = Compiler.compileContract(code).rightValue
    lazy val (obj, context) = prepareContract(contract, fields)

    def test(methodIndex: Int, args: AVector[Val], result: AVector[Val]) = {
      StatefulVM.executeWithOutputs(context, obj, args, methodIndex).rightValue is result
    }
  }

  it should "test array" in new TestContractMethodFixture {
    val code =
      s"""
         |TxContract ArrayTest() {
         |  pub fn test0() -> (Bool) {
         |    let mut arr1 = [1, 2, 3]
         |    arr1[0] = 2
         |    return arr1[0] == 2 && arr1[1] == 2 && arr1[2] == 3
         |  }
         |
         |  pub fn test1(x: U256) -> (U256) {
         |    return [x; 4][0]
         |  }
         |
         |  pub fn test2(mut x: [Bool; 4]) -> (Bool) {
         |    x[1] = !x[1]
         |    return x[1]
         |  }
         |
         |  pub fn test3(mut x: [U256; 4]) -> (Bool) {
         |    let mut y = x
         |    y[0] = y[0] + 1
         |    return y[0] == (x[0] + 1)
         |  }
         |
         |  pub fn test4(x: U256) -> ([U256; 2]) {
         |    let y = [[x; 2]; 5]
         |    return y[0]
         |  }
         |
         |  pub fn test5(value: U256) -> ([[U256; 2]; 2]) {
         |    let x = [[value; 2]; 3]
         |    let mut y = x
         |    y[0][0] = y[0][0] + 1
         |    y[0][1] = y[0][1] + 1
         |    y[2][0] = y[2][0] + 2
         |    y[2][1] = y[2][1] + 2
         |    return [y[0], y[2]]
         |  }
         |
         |  pub fn foo(x: U256) -> ([U256; 4]) {
         |    return [x; 4]
         |  }
         |
         |  pub fn test7(x: U256) -> (U256) {
         |    return foo(x)[0]
         |  }
         |
         |  pub fn bar(x: [U256; 4], y: [U256; 4]) -> (U256) {
         |    return [x, y][0][0]
         |  }
         |
         |  pub fn test9(x: U256) -> (U256) {
         |    return bar(foo(x), foo(x))
         |  }
         |
         |  pub fn test10() -> (Bool) {
         |    let mut x = [[4; 2]; 2]
         |    let y = [3; 2]
         |    x[0] = y
         |    return x[0][0] == 3 &&
         |           x[0][1] == 3 &&
         |           x[1][0] == 4 &&
         |           x[1][1] == 4
         |  }
         |
         |  pub fn test11() -> (Bool) {
         |    let mut x = [[[4; 2]; 2]; 2]
         |    let y = [3; 2]
         |    x[1][1] = y
         |    return x[0][0][0] == 4 &&
         |           x[0][0][1] == 4 &&
         |           x[1][1][0] == 3 &&
         |           x[1][1][1] == 3
         |  }
         |
         |  pub fn test12() -> (Bool) {
         |    let mut x = [4; 2]
         |    let y = [x, x]
         |    x[0] = 3
         |    return y[0][0] == 4 &&
         |           y[0][1] == 4 &&
         |           y[1][0] == 4 &&
         |           y[1][1] == 4
         |  }
         |
         |  pub fn test13() -> (Bool) {
         |    let mut x = [[4; 2]; 2]
         |    let y = [x[0], x[1]]
         |    x[0] = [3; 2]
         |    return y[0][0] == 4 &&
         |           y[0][1] == 4 &&
         |           y[1][0] == 4 &&
         |           y[1][1] == 4 &&
         |           x[0][0] == 3 &&
         |           x[0][1] == 3
         |  }
         |}
         |""".stripMargin

    test(0, AVector.empty, AVector(Val.True))
    test(1, AVector(Val.U256(3)), AVector(Val.U256(3)))
    test(2, AVector.fill(4)(Val.True), AVector(Val.False))
    test(3, AVector.fill(4)(Val.U256(10)), AVector(Val.True))
    test(4, AVector(Val.U256(4)), AVector.fill(2)(Val.U256(4)))
    test(5, AVector(Val.U256(1)), AVector(Val.U256(2), Val.U256(2), Val.U256(3), Val.U256(3)))
    test(7, AVector(Val.U256(3)), AVector(Val.U256(3)))
    test(9, AVector(Val.U256(3)), AVector(Val.U256(3)))
    test(10, AVector.empty, AVector(Val.True))
    test(11, AVector.empty, AVector(Val.True))
    test(12, AVector.empty, AVector(Val.True))
    test(13, AVector.empty, AVector(Val.True))
  }

  it should "test contract array fields" in new TestContractMethodFixture {
    val code =
      s"""
         |TxContract Foo(
         |  mut array: [[U256; 2]; 4],
         |  mut x: U256
         |) {
         |  fn foo0(a: [U256; 2], b: [U256; 2]) -> () {
         |    loop(0, 2, 1, assert!(a[?] == b[?]))
         |    return
         |  }
         |
         |  pub fn test1(a: [[U256; 2]; 4]) -> () {
         |    array = a
         |    loop(0, 4, 1, foo0(array[?], a[?]))
         |    return
         |  }
         |
         |  fn foo1() -> (U256) {
         |    x = x + 1
         |    return x
         |  }
         |
         |  pub fn test3() -> (Bool) {
         |    x = 0
         |    let res = [foo1(), foo1(), foo1(), foo1()]
         |    return x == 4
         |  }
         |
         |  pub fn test4() -> (Bool) {
         |    x = 0
         |    let res = [foo1(), foo1(), foo1(), foo1()][2]
         |    return x == 4
         |  }
         |
         |  pub fn test5() -> (Bool) {
         |    x = 0
         |    let res = [foo1(); 4]
         |    return x == 4
         |  }
         |}
         |""".stripMargin

    override val fields: AVector[Val] = AVector.fill(9)(Val.U256(0))
    test(1, AVector.fill(8)(Val.U256(3)), AVector.empty)
    test(3, AVector.empty, AVector(Val.True))
    test(4, AVector.empty, AVector(Val.True))
    test(5, AVector.empty, AVector(Val.True))
  }

  it should "compile failed if loop range too large" in {
    val code =
      s"""
         |TxContract LoopTest() {
         |  fn foo() -> () {
         |    let mut x = 0
         |    loop(0, 10, 1, x = x + ?)
         |    return
         |  }
         |}
         |""".stripMargin

    val config = new CompilerConfig {
      override def loopUnrollingLimit: Int = 5
    }
    Compiler.compileContract(code)(config) is Left(Compiler.Error("loop range too large"))
  }

  it should "test loop" in new TestContractMethodFixture {
    val code =
      s"""
         |TxContract LoopTest(mut array: [U256; 3]) {
         |  pub fn test0() -> (Bool) {
         |    let mut x = [0; 3]
         |    loop(0, 3, 1, x[?] = ?)
         |    return x[0] == 0 &&
         |           x[1] == 1 &&
         |           x[2] == 2
         |  }
         |
         |  pub fn test1() -> (Bool) {
         |    let mut x = [0; 3]
         |    loop(2, 0, -1, x[?] = ?)
         |    x[0] = 0
         |    return x[0] == 0 &&
         |           x[1] == 1 &&
         |           x[2] == 2
         |  }
         |
         |  pub fn test2() -> (Bool) {
         |    let mut x = [[0; 2]; 3]
         |    loop(0, 3, 1, x[?][0] = ?)
         |    return x[0][0] == 0 &&
         |           x[1][0] == 1 &&
         |           x[2][0] == 2
         |  }
         |
         |  pub fn test3() -> (Bool) {
         |    let mut x = [0; 3]
         |    let mut y = [1; 3]
         |    loop(0, 3, 1, x[?] = ?)
         |    loop(0, 3, 1, y[?] = ?)
         |    return x[0] == y[0] &&
         |           x[1] == y[1] &&
         |           x[2] == y[2]
         |  }
         |
         |  pub fn test4() -> (Bool) {
         |    loop(0, 3, 1, array[?] = ?)
         |    return array[0] == 0 &&
         |           array[1] == 1 &&
         |           array[2] == 2
         |  }
         |
         |  fn foo(value: U256) -> () {
         |    loop(0, 3, 1, array[?] = array[?] + value)
         |    return
         |  }
         |
         |  pub fn test6() -> (Bool) {
         |    loop(0, 3, 1, array[?] = 0)
         |    loop(0, 3, 1, foo(?))
         |    return array[0] == 3 &&
         |           array[1] == 3 &&
         |           array[2] == 3
         |  }
         |
         |  pub fn test7() -> (Bool) {
         |    let mut x = 0
         |    let mut y = 0
         |    loop(0, 3, 1,
         |      if (? >= 1) {
         |        x = x + ?
         |      } else {
         |        y = y + ?
         |      }
         |    )
         |    return x == 3 && y == 0
         |  }
         |
         |  pub fn test8() -> (Bool) {
         |    let mut x = 0
         |    let mut i = 0
         |    loop(0, 3, 1,
         |      while (i < ?) {
         |        x = ? + x
         |        i = i + 1
         |      }
         |    )
         |    return x == 3
         |  }
         |
         |  pub fn bar() -> ([U256; 3]) {
         |    return [0, 1, 2]
         |  }
         |
         |  pub fn test10() -> (Bool) {
         |    let mut x = [0; 3]
         |    loop(0, 3, 1, x[?] = bar()[?])
         |    return x[0] == 0 &&
         |           x[1] == 1 &&
         |           x[2] == 2
         |  }
         |}
         |""".stripMargin

    override val fields: AVector[Val] = AVector.fill(3)(Val.U256(0))
    test(0, AVector.empty, AVector(Val.True))
    test(1, AVector.empty, AVector(Val.True))
    test(2, AVector.empty, AVector(Val.True))
    test(3, AVector.empty, AVector(Val.True))
    test(4, AVector.empty, AVector(Val.True))
    test(6, AVector.empty, AVector(Val.True))
    test(7, AVector.empty, AVector(Val.True))
    test(8, AVector.empty, AVector(Val.True))
    test(10, AVector.empty, AVector(Val.True))
  }

  it should "compile return multiple values failed" in {
    val codes = Seq(
      s"""
         |// Assign to immutable variable
         |TxContract Foo() {
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
         |TxContract Foo() {
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
         |TxContract Foo() {
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
         |TxContract Foo() {
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
         |TxContract Foo() {
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

  it should "test return multiple values" in new TestContractMethodFixture {
    val code: String =
      s"""
         |TxContract Foo(mut array: [U256; 3]) {
         |  fn foo0() -> (U256, U256) {
         |    return 1, 2
         |  }
         |
         |  pub fn test1() -> (Bool) {
         |    let mut a = 0
         |    let mut b = 0
         |    a, b = foo0()
         |    return a == 1 && b == 2
         |  }
         |
         |  pub fn test2() -> (Bool) {
         |    array[0], array[1] = foo0()
         |    return array[0] == 1 && array[1] == 2
         |  }
         |
         |  pub fn foo1() -> ([U256; 3], [U256; 3], U256) {
         |    return [1, 2, 3], [4, 5, 6], 7
         |  }
         |
         |  pub fn test4() -> (Bool) {
         |    let mut i = 0
         |    let mut x = [[0; 3]; 2]
         |    x[0], array, i = foo1()
         |    return x[0][0] == 1 &&
         |           x[0][1] == 2 &&
         |           x[0][2] == 3 &&
         |           array[0] == 4 &&
         |           array[1] == 5 &&
         |           array[2] == 6 &&
         |           i == 7
         |  }
         |
         |  pub fn foo2(value: U256) -> ([U256; 3], U256) {
         |    loop(0, 3, 1, array[?] = array[?] + value)
         |    return array, value
         |  }
         |
         |  pub fn test6() -> (Bool) {
         |    array = [1, 2, 3]
         |    let mut x = [[0; 3]; 3]
         |    let mut y = [0; 3]
         |    loop(0, 3, 1, x[?], y[?] = foo2(?))
         |    return x[0][0] == 1 &&
         |           x[0][1] == 2 &&
         |           x[0][2] == 3 &&
         |           x[1][0] == 2 &&
         |           x[1][1] == 3 &&
         |           x[1][2] == 4 &&
         |           x[2][0] == 4 &&
         |           x[2][1] == 5 &&
         |           x[2][2] == 6 &&
         |           y[0] == 0 &&
         |           y[1] == 1 &&
         |           y[2] == 2
         |  }
         |
         |  fn foo3() -> ([U256; 2], U256, U256) {
         |    return [1; 2], 1, 1
         |  }
         |
         |  pub fn test8() -> () {
         |    let (mut a, mut b, c) = foo3()
         |    assert!(b == 1 && c == 1 && a[0] == 1 && a[1] == 1)
         |    b = 2
         |    loop(0, 2, 1, a[?] = ?)
         |    assert!(b == 2 && a[0] == 0 && a[1] == 1)
         |    return
         |  }
         |}
         |""".stripMargin

    override val fields = AVector.fill(3)(Val.U256(0))
    test(1, AVector.empty, AVector(Val.True))
    test(2, AVector.empty, AVector(Val.True))
    test(4, AVector.empty, AVector(Val.True))
    test(6, AVector.empty, AVector(Val.True))
    test(8, AVector.empty, AVector.empty)
  }

  it should "return from if block" in new TestContractMethodFixture {
    val code: String =
      s"""
         |TxContract Foo(mut value: U256) {
         |  pub fn foo() -> () {
         |    if (true) {
         |      value = 1
         |      return
         |    }
         |
         |    value = 2
         |    return
         |  }
         |
         |  pub fn getValue() -> (U256) {
         |    return value
         |  }
         |}
         |""".stripMargin

    override val fields = AVector(Val.U256(0))
    test(0, AVector.empty, AVector.empty)
    test(1, AVector.empty, AVector(Val.U256(1)))
  }

  it should "generate efficient code for arrays" in {
    val code =
      s"""
         |TxContract Foo() {
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
        isPayable = false,
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
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
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
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
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
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
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
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
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
           |TxContract Foo(mut x: U256, mut y: U256, c: U256) {
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
         |TxContract Parent(mut x: U256) {
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
           |TxContract Child(mut x: U256, y: U256) extends Parent(x) {
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
           |TxContract Child(mut x: U256, y: U256) extends Parent(z) {
           |  pub fn foo() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      Compiler.compileContract(child).leftValue.message is
        "Contract field z does not exist"
    }

    {
      info("duplicated function definitions")

      val child =
        s"""
           |TxContract Child(mut x: U256, y: U256) extends Parent(x) {
           |  pub fn foo() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      Compiler.compileContract(child).leftValue.message is
        "These functions are defined multiple times: foo"
    }

    {
      info("duplicated event definitions")

      val child =
        s"""
           |TxContract Child(mut x: U256, y: U256) extends Parent(x) {
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
           |TxContract Child(x: U256, y: U256) extends Parent(x) {
           |  pub fn bar() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      Compiler.compileContract(child).leftValue.message is
        "Invalid contract inheritance fields, expect List(Argument(Ident(x),U256,true)), have List(Argument(Ident(x),U256,false))"
    }

    {
      info("invalid field in parent contract")

      val child =
        s"""
           |TxContract Child(mut x: U256, mut y: U256) extends Parent(y) {
           |  pub fn bar() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      Compiler.compileContract(child).leftValue.message is
        "Invalid contract inheritance fields, expect List(Argument(Ident(x),U256,true)), have List(Argument(Ident(y),U256,true))"
    }

    {
      info("Cyclic inheritance")

      val code =
        s"""
           |TxContract A(x: U256) extends B(x) {
           |  fn a() -> () {
           |  }
           |}
           |
           |TxContract B(x: U256) extends C(x) {
           |  fn b() -> () {
           |  }
           |}
           |
           |TxContract C(x: U256) extends A(x) {
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
           |TxContract Child(mut x: U256) extends Parent0(x), Parent1(x) {
           |  pub fn foo() -> () {
           |    p0(true, true)
           |    p1(true, true, true)
           |    gp(true)
           |  }
           |}
           |
           |TxContract Grandparent(mut x: U256) {
           |  event GP(value: U256)
           |
           |  fn gp(a: Bool) -> () {
           |    x = x + 1
           |    emit GP(x)
           |  }
           |}
           |
           |TxContract Parent0(mut x: U256) extends Grandparent(x) {
           |  fn p0(a: Bool, b: Bool) -> () {
           |    gp(a)
           |  }
           |}
           |
           |TxContract Parent1(mut x: U256) extends Grandparent(x) {
           |  fn p1(a: Bool, b: Bool, c: Bool) -> () {
           |    gp(a)
           |  }
           |}
           |""".stripMargin

      val contract = Compiler.compileContract(code).rightValue
      contract.methodsLength is 4
      contract.methods.map(_.argsLength) is AVector(0, 1, 2, 3)
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
      error.message is "No function definition in TxContract Foo"
    }

    {
      info("Interface inheritance should not contain duplicated functions")
      val foo =
        s"""
           |Interface Foo {
           |  fn foo() -> ()
           |}
           |""".stripMargin
      val bar =
        s"""
           |Interface Bar extends Foo {
           |  fn foo() -> ()
           |}
           |
           |$foo
           |""".stripMargin
      val error = Compiler.compileMultiContract(bar).leftValue
      error.message is "These functions are defined multiple times: foo"
    }

    {
      info("Contract should implement interface functions with the same signature")
      val foo =
        s"""
           |Interface Foo {
           |  fn foo() -> ()
           |}
           |""".stripMargin
      val bar =
        s"""
           |TxContract Bar() extends Foo {
           |  pub fn foo() -> () {
           |    return
           |  }
           |}
           |
           |$foo
           |""".stripMargin
      val error = Compiler.compileMultiContract(bar).leftValue
      error.message is "Function foo is implemented with wrong signature"
    }

    {
      info("Interface inheritance can be chained")
      val a =
        s"""
           |Interface A {
           |  pub fn a() -> ()
           |}
           |""".stripMargin
      val b =
        s"""
           |Interface B extends A {
           |  pub fn b(x: Bool) -> ()
           |}
           |
           |$a
           |""".stripMargin
      val c =
        s"""
           |Interface C extends B {
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
           |TxContract Foo() extends C {
           |  pub fn c(x: Bool, y: Bool) -> () {}
           |  pub fn a() -> () {}
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
        Compiler.compileMultiContract(code).rightValue.contracts(0).asInstanceOf[Ast.TxContract]
      contract.funcs.map(_.args.length) is Seq(0, 1, 2, 3)
    }

    {
      info("Contract inherits both interface and contract")
      val foo1: String =
        s"""
           |TxContract Foo1() {
           |  fn foo1() -> () {}
           |}
           |""".stripMargin
      val foo2: String =
        s"""
           |Interface Foo2 {
           |  fn foo2() -> ()
           |}
           |""".stripMargin
      val bar1: String =
        s"""
           |TxContract Bar1() extends Foo1(), Foo2 {
           |  fn foo2() -> () {}
           |}
           |$foo1
           |$foo2
           |""".stripMargin
      val bar2: String =
        s"""
           |TxContract Bar2() extends Foo2, Foo1() {
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
       |TxScript Main<address: Address, tokenId: ByteVec, tokenAmount: U256, swapContractKey: ByteVec> {
       |  pub payable fn main() -> () {
       |    approveToken!(address, tokenId, tokenAmount)
       |    let swap = Swap(swapContractKey)
       |    swap.swapAlph(address, tokenAmount)
       |  }
       |}
       |
       |Interface Swap {
       |  pub payable fn swapAlph(buyer: Address, tokenAmount: U256) -> ()
       |}
       |""".stripMargin
    val script = Compiler.compileTxScript(code).rightValue
    script.toTemplateString() is "0101010001000a{address:Address}{tokenId:ByteVec}{tokenAmount:U256}a3{swapContractKey:ByteVec}1700{address:Address}{tokenAmount:U256}16000100"
  }

  it should "compile events with <= 8 fields" in {
    def code(nineFields: Boolean): String = {
      val eventStr = if (nineFields) ", a9: U256" else ""
      val emitStr  = if (nineFields) ", 9" else ""
      s"""
         |TxContract Foo(tmp: U256) {
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
}
