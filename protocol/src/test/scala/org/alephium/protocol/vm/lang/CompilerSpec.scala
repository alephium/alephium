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
         |  return
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
        "Expect array type, have: U256",
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
        "Expect array type, have: U256", // TODO: improve this error message
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
        "Assign List(U256) to List(I256)",
      s"""
         |TxContract Foo() {
         |  fn foo() -> U256 {
         |    let x = [1; 2]
         |    return x[#00]
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index type List(ByteVec)",
      s"""
         |TxContract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1; 2]
         |    x[-1i] = 0
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index type List(I256)",
      s"""
         |TxContract Foo() {
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

  trait TestContractMethodFixture {
    def test(
        contractCode: String,
        initFields: AVector[Val] = AVector.empty,
        methodIndex: Int = 0,
        args: AVector[Val] = AVector.empty,
        result: AVector[Val] = AVector.empty
    ) = {
      val contract       = Compiler.compileContract(contractCode).rightValue
      val (obj, context) = prepareContract(contract, initFields)
      StatefulVM.executeWithOutputs(context, obj, args, methodIndex).rightValue is result
    }
  }

  it should "test array" in new TestContractMethodFixture {
    {
      info("get array element from array literal")
      val code =
        s"""
           |TxContract ArrayTest() {
           |  pub fn test() -> () {
           |    assert!([0, 1, 2][2] == 2)
           |    assert!(foo()[1] == 1)
           |    assert!([foo(), foo()][0][0] == 0)
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
           |TxContract ArrayTest() {
           |  pub fn test() -> () {
           |    let array = [1, 2, 3]
           |    assert!(array[0] == 1 && array[1] == 2 && array[2] == 3)
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("assign array element by constant index")
      val code =
        s"""
           |TxContract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut array = [0; 3]
           |    array[0] = 1
           |    array[1] = 2
           |    array[2] = 3
           |    assert!(array[0] == 1 && array[1] == 2 && array[2] == 3)
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("array variable assignment")
      val code =
        s"""
           |TxContract ArrayTest() {
           |  pub fn test() -> () {
           |    let x = [1, 2, 3]
           |    let mut y = [0; 3]
           |    assert!(y[0] == 0 && y[1] == 0 && y[2] == 0)
           |    y = x
           |    assert!(y[0] == 1 && y[1] == 2 && y[2] == 3)
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("assign array element by variable index")
      val code =
        s"""
           |TxContract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut array = [0; 3]
           |    let mut i = 0
           |    while (i < 3) {
           |      array[i] = i + 1
           |      i = i + 1
           |    }
           |    assert!(array[0] == 1 && array[1] == 2 && array[2] == 3)
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("array as function params and return values")
      val code =
        s"""
           |TxContract ArrayTest() {
           |  pub fn test(mut x: [Bool; 2]) -> ([Bool; 2]) {
           |    x[0] = !x[0]
           |    x[1] = !x[1]
           |    return x
           |  }
           |}
           |""".stripMargin
      test(code, args = AVector(Val.False, Val.True), result = AVector(Val.True, Val.False))
    }

    {
      info("get sub array by constant index")
      val code =
        s"""
           |TxContract ArrayTest() {
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
           |      array[3] == v + 3
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
           |TxContract ArrayTest() {
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
           |      assert!(array[i] == v + i)
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
           |TxContract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut x = [[0; 2]; 2]
           |    x[0][0] = 1
           |    x[0][1] = 2
           |    x[1][0] = 3
           |    x[1][1] = 4
           |    assert!(
           |      x[0][0] == 1 && x[0][1] == 2 &&
           |      x[1][0] == 3 && x[1][1] == 4
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
           |TxContract ArrayTest() {
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
           |      x[1][0] == 3 && x[1][1] == 4
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
           |TxContract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut x = [[0; 2]; 2]
           |    x[0] = [0, 1]
           |    x[1] = [2, 3]
           |    assert!(
           |      x[0][0] == 0 && x[0][1] == 1 &&
           |      x[1][0] == 2 && x[1][1] == 3
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
           |TxContract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut x = [[0; 2]; 2]
           |    let mut i = 0
           |    while (i < 2) {
           |      x[i] = [i, i + 1]
           |      i = i + 1
           |    }
           |    i = 0
           |    while (i < 2) {
           |      assert!(x[i][0] == i)
           |      assert!(x[i][1] == i + 1)
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
           |TxContract ArrayTest() {
           |  pub fn test() -> () {
           |    let mut x = [0, 1, 2, 3]
           |    let num = 4
           |    assert!(x[foo()] == 3)
           |    assert!(x[num / 2] == 2)
           |    assert!(x[num % 3] == 1)
           |    assert!(x[num - 4] == 0)
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
         |TxContract Foo() {
         |  fn func0() -> () {
         |    let array0 = [0, 1, 2]
         |    let mut i = 0
         |    while (i < 3) {
         |      assert!(array0[i] == i)
         |      i = i + 1
         |    }
         |
         |    let array1 = [0, 1]
         |    i = 0
         |    while (i < 2) {
         |      assert!(array1[i] == i)
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
        LoadLocal(3), U256Const3, U256Lt, IfFalse(14),
        LoadLocal(3), Dup, U256Const3, U256Lt, Assert, LoadLocalByIndex, LoadLocal(3), U256Eq, Assert,
        LoadLocal(3), U256Const1, U256Add, StoreLocal(3), Jump(-18),
        U256Const0, U256Const1, StoreLocal(5), StoreLocal(4),
        U256Const0, StoreLocal(3),
        LoadLocal(3), U256Const2, U256Lt, IfFalse(16),
        LoadLocal(3), Dup, U256Const2, U256Lt, Assert, U256Const4, U256Add, LoadLocalByIndex, LoadLocal(3), U256Eq, Assert,
        LoadLocal(3), U256Const1, U256Add, StoreLocal(3), Jump(-20)
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
         |TxContract Foo(foo: U256, mut array: [[U256; 2]; 3]) {
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

  it should "test contract array fields" in new TestContractMethodFixture {
    {
      info("array fields assignment")
      val code =
        s"""
           |TxContract ArrayTest(mut array: [[U256; 2]; 4]) {
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
           |      assert!(a[i] == b[i])
           |      i = i + 1
           |    }
           |  }
           |}
           |""".stripMargin
      val args = AVector.from[Val]((0 until 8).map(Val.U256(_)))
      test(code, initFields = AVector.fill(8)(Val.U256(0)), args = args)
    }

    {
      info("create array with side effect")
      val code =
        s"""
           |TxContract ArrayTest(mut x: U256) {
           |  pub fn test() -> () {
           |    let array0 = [foo(), foo(), foo()]
           |    assert!(x == 3)
           |
           |    let array1 = [foo(), foo(), foo()][0]
           |    assert!(x == 6)
           |
           |    let array2 = [foo(); 3]
           |    assert!(x == 9)
           |  }
           |
           |  fn foo() -> U256 {
           |    x = x + 1
           |    return x
           |  }
           |}
           |""".stripMargin
      test(code, initFields = AVector(Val.U256(0)))
    }

    {
      info("assign array element")
      val code =
        s"""
           |TxContract ArrayTest(mut array: [[U256; 2]; 4]) {
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
           |        assert!(array[i][j] == i + j)
           |        j = j + 1
           |      }
           |      j = 0
           |      i = i + 1
           |    }
           |  }
           |}
           |""".stripMargin
      test(code, initFields = AVector.fill(8)(Val.U256(0)))
    }

    {
      info("avoid executing array indexing instructions multiple times")
      val code =
        s"""
           |TxContract Foo(mut array: [[U256; 2]; 4], mut x: U256) {
           |  pub fn test() -> () {
           |    let mut i = 0
           |    while (i < 4) {
           |      array[foo()] = [x; 2]
           |      i = i + 1
           |      assert!(x == i)
           |    }
           |    assert!(x == 4)
           |
           |    i = 0
           |    while (i < 4) {
           |      x = 0
           |      assert!(array[i][foo()] == i)
           |      assert!(array[i][foo()] == i)
           |      i = i + 1
           |      assert!(x == 2)
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
      test(code, initFields = AVector.fill(9)(Val.U256(0)))
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
    {
      info("return multiple simple values")
      val code =
        s"""
           |TxContract Foo() {
           |  pub fn test() -> () {
           |    let (a, b) = foo()
           |    assert!(a == 1 && b)
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
           |TxContract Foo(mut array: [U256; 3]) {
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
           |      y[0] == 0 && y[1] == 1 && y[2] == 2
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
      test(code, initFields = AVector.fill(3)(Val.U256(0)))
    }

    {
      info("test return multi-dim array and values")
      val code =
        s"""
           |TxContract Foo() {
           |  pub fn test() -> () {
           |    let (array, i) = foo()
           |    assert!(
           |      array[0][0] == 1 && array[0][1] == 2 && array[0][2] == 3 &&
           |      array[1][0] == 4 && array[1][1] == 5 && array[1][2] == 6 &&
           |      i == 7
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

  it should "return from if block" in new TestContractMethodFixture {
    val code: String =
      s"""
         |TxContract Foo(mut value: U256) {
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
    test(code, initFields = AVector(Val.U256(0)), result = AVector(Val.U256(1)))
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
      contract.methods.map(_.argsLength) is AVector(2, 1, 3, 0)
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
           |TxContract Bar() implements Foo {
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
           |TxContract Foo() implements C {
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
           |TxContract Bar1() extends Foo1() implements Foo2 {
           |  fn foo2() -> () {}
           |}
           |$foo1
           |$foo2
           |""".stripMargin
      val bar2: String =
        s"""
           |TxContract Bar2() extends Foo1() implements Foo2 {
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
         |@using(preapprovedAssets = true, assetsInContract = true)
         |TxScript Main(address: Address, tokenId: ByteVec, tokenAmount: U256, swapContractKey: ByteVec) {
         |  approveToken!(address, tokenId, tokenAmount)
         |  let swap = Swap(swapContractKey)
         |  swap.swapAlph(address, tokenAmount)
         |}
         |
         |Interface Swap {
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn swapAlph(buyer: Address, tokenAmount: U256) -> ()
         |}
         |""".stripMargin
    val script = Compiler.compileTxScript(code).rightValue
    script.toTemplateString() is "0101010001000a{0}{1}{2}a3{3}1700{0}{2}16000100"
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

  it should "compile if-else statements" in {
    {
      info("Simple if statement")
      val code =
        s"""
           |TxContract Foo() {
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
           |TxContract Foo() {
           |  fn foo() -> U256 {
           |    if (true) {
           |      return 1
           |    }
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).leftValue.message is
        "Expect return statement for function foo"
    }

    {
      info("Simple if-else statement")
      val code =
        s"""
           |TxContract Foo() {
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
           |TxContract Foo() {
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
           |TxContract Foo() {
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

    new TestContractMethodFixture {
      val code =
        s"""
           |TxContract Foo() {
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

      test(code, args = AVector(Val.U256(U256.Zero)), result = AVector(Val.U256(U256.unsafe(10))))
      test(code, args = AVector(Val.U256(U256.One)), result = AVector(Val.U256(U256.unsafe(1))))
      test(code, args = AVector(Val.U256(U256.Two)), result = AVector(Val.U256(U256.unsafe(100))))
    }
  }
}
