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

import scala.collection.mutable

import akka.util.ByteString
import org.scalatest.Assertion

import org.alephium.protocol.{Hash, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm._
import org.alephium.serde._
import org.alephium.util._

// scalastyle:off no.equal file.size.limit number.of.methods
class CompilerSpec extends AlephiumSpec with ContextGenerators {

  def replace(code: String): String = code.replace("$", "")
  def replaceFirst(code: String): String = {
    val i = index(code)
    code.substring(0, i) + code.substring(i + 1)
  }
  def index(code: String): Int = code.indexOf("$")

  def testContractError(code: String, message: String): Compiler.Error = {
    testErrorT(code, message, Compiler.compileContract(_))
  }

  def testContractFullError(code: String, message: String): Compiler.Error = {
    testErrorT(code, message, Compiler.compileContractFull(_))
  }

  def testMultiContractError(code: String, message: String): Compiler.Error = {
    testErrorT(code, message, Compiler.compileMultiContract(_))
  }

  def testTxScriptError(code: String, message: String): Compiler.Error = {
    testErrorT(code, message, Compiler.compileTxScript(_))
  }

  def testErrorT[T](
      code: String,
      message: String,
      f: String => Either[Compiler.Error, T]
  ): Compiler.Error = {
    val startIndex = index(code)
    val newCode    = replaceFirst(code)
    val endIndex   = index(newCode)
    val error      = f(replace(newCode)).leftValue

    error.position is startIndex
    error.foundLength is (endIndex - startIndex)
    error.message is message

    error
  }

  def methodSelectorOf(signature: String): MethodSelector = {
    MethodSelector(Method.Selector(DjbHash.intHash(ByteString.fromString(signature))))
  }

  it should "compile asset script" in {
    def runScript(assetScript: StatelessScript, args: AVector[Val]): AVector[Val] = {
      val (scriptObj, statelessContext) = prepareStatelessScript(assetScript)
      StatelessVM.executeWithOutputs(statelessContext, scriptObj, args).rightValue
    }

    {
      val code =
        s"""
           |// comment
           |AssetScript Foo {
           |  pub fn bar(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      val (script, warnings) = Compiler.compileAssetScript(code).rightValue
      warnings.isEmpty is true
      runScript(script, AVector(Val.U256(2), Val.U256(3))) is AVector[Val](Val.U256(5))
    }

    {
      val code =
        s"""
           |struct Foo {
           |  x: U256,
           |  y: ByteVec
           |}
           |AssetScript Foo {
           |  pub fn f0() -> Foo {
           |    return Foo { x: 1, y: #0011 }
           |  }
           |}
           |""".stripMargin
      val (script, warnings) = Compiler.compileAssetScript(code).rightValue
      warnings.isEmpty is true
      runScript(script, AVector.empty) is AVector[Val](Val.U256(1), Val.ByteVec(Hex.unsafe("0011")))
    }
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

      val script = "TxScript Foo {$}$"
      val error  = testTxScriptError(script, "Expected main statements for type `Foo`")

      error
        .format(replace(script)) is
        """-- error (1:15): Syntax error
          |1 |TxScript Foo {}
          |  |              ^
          |  |              Expected main statements for type `Foo`
          |""".stripMargin
    }

    {
      info("fail with event definition")

      val script =
        s"""
           |TxScript Foo {
           |  $$event Add($$a: U256, b: U256)
           |
           |  pub fn bar(a: U256, b: U256) -> (U256) {
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      val error = testTxScriptError(script, """Expected "}"""")

      error
        .format(replace(script)) is
        """-- error (3:3): Syntax error
          |3 |  event Add(a: U256, b: U256)
          |  |  ^^^^^^^^^^
          |  |  Expected "}"
          |  |----------------------------------------------------------------------------------------------------
          |  |Trace log: Expected multiContract:1:1 / entities:2:1 / rawTxScript:2:1 / "}":3:3, found "event Add("
          |""".stripMargin
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
           |  @using(updateFields = true)
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
           |Contract $$Foo$$(mut x: U256, mut y: U256, c: U256) {
           |  event Add(a: U256, b: U256)
           |}
           |""".stripMargin

      testContractError(contract, "No function found in Contract \"Foo\"")
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
           |  $$pub fn add1(b: U256, a: U256) -> (U256) {
           |    return (a + b)
           |  }$$
           |  pub fn add2(b: U256, a: U256) -> (U256) {
           |    return (a + b)
           |  }
           |}
           |""".stripMargin

      testContractError(contract, "These functions are implemented multiple times: add1, add2")
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
           |  @using(updateFields = true)
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
         |  fn $$foo$$() -> (U256) {
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn $$foo$$(value: U256) -> (U256) {
         |    if (value > 10) {
         |      return 1
         |    }
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn $$foo$$() -> (U256) {
         |    let mut x = 0
         |    return 0
         |    x = 1
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn $$foo$$(value: U256) -> (U256) {
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
      testContractError(code, "Expected return statement for function \"foo\"")
    }

    val invalidReturnCases = Seq(
      (
        s"""
           |Contract Foo() {
           |  fn foo() -> () {
           |    $$return 1$$
           |  }
           |}
           |""".stripMargin,
        """Invalid return types "List(U256)" for func foo, expected "List()""""
      ),
      (
        s"""
           |Contract Foo() {
           |  fn foo() -> (U256) {
           |    $$return$$
           |  }
           |}
           |""".stripMargin,
        """Invalid return types "List()" for func foo, expected "List(U256)""""
      )
    )
    invalidReturnCases.foreach { case (code, error) =>
      testContractError(code, error)
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
         |Contract Foo(bar: $$Bar$$) {
         |  fn foo() -> () {
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo(bar: $$Bar$$) -> () {
         |  }
         |}
         |""".stripMargin,
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    let bar = $$Bar$$(#00)
         |  }
         |}
         |""".stripMargin
    )
    failed.foreach { code =>
      testContractError(code, "Contract Bar does not exist")
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
        immFields: AVector[Val] = AVector.empty,
        mutFields: AVector[Val] = AVector.empty,
        methodIndex: Int = 0
    ): Assertion = {
      val compiled = Compiler.compileContractFull(input).rightValue
      compiled.code is compiled.debugCode
      val contract = compiled.code

      deserialize[StatefulContract](serialize(contract)) isE contract
      val (obj, context) = prepareContract(contract, immFields, mutFields)
      StatefulVM.executeWithOutputs(context, obj, args, methodIndex) isE output
    }

    def fail(
        input: String,
        args: AVector[Val],
        check: ExeFailure => Assertion,
        immFields: AVector[Val] = AVector.empty,
        mutFields: AVector[Val] = AVector.empty
    ): Assertion = {
      val contract = Compiler.compileContract(input).toOption.get

      deserialize[StatefulContract](serialize(contract)) isE contract
      val (obj, context) = prepareContract(contract, immFields, mutFields)
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
        body: String = "return",
        forKey: String = "for"
    ): String =
      s"""
         |Contract ForLoop() {
         |  pub fn test() -> () {
         |    $forKey ($initialize; $condition; $update) {
         |      $body
         |    }$$
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(replace(code())).isRight is true
    Compiler.compileContract(replace(code(initialize = "true"))).isLeft is true
    testContractError(
      code(condition = "1", forKey = "$for"),
      "Invalid condition type: Const(U256(1))"
    )
    Compiler.compileContract(code(update = "true")).isLeft is true
    testContractError(code(update = "$i = true$"), "Cannot assign \"Bool\" to \"U256\"")
    Compiler.compileContract(code(body = "")).isLeft is true
    testContractError(
      code(initialize = "", forKey = "$for$"),
      "No initialize statement in for loop"
    )
    testContractError(code(update = "", forKey = "$for$"), "No update statement in for loop")
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

  it should "declare variables before its usage" in {
    testContractError(
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    for (let mut i = 0; i < 2; i = $$j$$ + 1) {
         |      for (let mut j = 0; j < 2; j = j + 1) {
         |        let _ = i + j
         |      }
         |    }
         |  }
         |}
         |""".stripMargin,
      "Variable foo.j does not exist or is used before declaration"
    )
    testContractError(
      s"""
         |Contract Foo() {
         |  pub fn foo() -> U256 {
         |    let a = $$b$$
         |    let b = 10
         |    return b
         |  }
         |}
         |""".stripMargin,
      "Variable foo.b does not exist or is used before declaration"
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
         |  @using(updateFields = true)
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
      mutFields = AVector(Val.U256(U256.unsafe(1000000)), Val.U256(U256.unsafe(100000)))
    )

    test(
      contract,
      AVector(Val.U256(U256.unsafe(1000))),
      AVector(Val.U256(U256.unsafe(99))),
      mutFields = AVector(
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
         |    let $$x$$ = [1, 2, 3]
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Local variables have the same name: x",
      s"""
         |// duplicated variable name
         |Contract Foo(x: [U256; 2]) {
         |  fn foo() -> () {
         |    let $$x$$ = [2; 3]
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Global variable has the same name as local variable: x",
      s"""
         |// assign to immutable array element(contract field)
         |Contract Foo(x: [U256; 2]) {
         |  fn set() -> () {
         |    $$x[0] = 2$$
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Cannot assign to immutable variable x.",
      s"""
         |// assign to immutable array element(local variable)
         |Contract Foo() {
         |  fn foo() -> () {
         |    let x = [2; 4]
         |    $$x[0] = 3$$
         |   return
         |  }
         |}
         |""".stripMargin ->
        "Cannot assign to immutable variable x.",
      s"""
         |// out of index
         |Contract Foo() {
         |  fn foo() -> (U256) {
         |    let x = [[2; 2]; 4]
         |    return x[1]$$[3]$$
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index: 3, array size: 2",
      s"""
         |// out of index
         |Contract Foo() {
         |  fn foo() -> () {
         |    let mut x = [2; 2]
         |    x$$[2]$$ = 3
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
         |    x$$[2]$$ = 2
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
         |    $$x[0]$$[0] = 2
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Expected array or map type, got \"U256\"",
      s"""
         |// invalid array expression
         |Contract Foo() {
         |  fn foo() -> () {
         |    let x = [1, 2]
         |    let y = $$x[0]$$[0]
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Expected array or map type, got \"U256\"", // TODO: improve this error message
      s"""
         |// invalid array expression
         |Contract Foo() {
         |  fn foo() -> () {
         |    let x = 2
         |    let y = $$x$$[0]
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Expected array or map type, got \"U256\"",
      s"""
         |// invalid binary expression(compare array)
         |Contract Foo() {
         |  fn foo() -> (Bool) {
         |    let x = [3; 2]
         |    let y = [3; 2]
         |    return $$x == y$$
         |  }
         |}
         |""".stripMargin ->
        "Invalid param types List(FixedSizeArray(U256,2), FixedSizeArray(U256,2)) for == operator",
      s"""
         |// invalid binary expression(add array)
         |Contract Foo() {
         |  fn foo() -> () {
         |    let x = $$[2; 2] + [2; 2]$$
         |    return
         |  }
         |}""".stripMargin ->
        "Invalid param types List(FixedSizeArray(U256,2), FixedSizeArray(U256,2)) for + operator",
      s"""
         |// assign array element with invalid type
         |Contract Foo() {
         |  fn foo() -> () {
         |    let mut x = [3i; 2]
         |    $$x[0] = 3$$
         |    return
         |  }
         |}
         |""".stripMargin ->
        "Cannot assign \"U256\" to \"I256\"",
      s"""
         |Contract Foo() {
         |  fn foo() -> U256 {
         |    let x = [1; 2]
         |    return x$$[#00]$$
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index type \"ByteVec\", expected \"U256\"",
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1; 2]
         |    x$$[-1i]$$ = 0
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index type \"I256\", expected \"U256\"",
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    let mut x = [1; 2]
         |    x$$[1 + 2]$$ = 0
         |  }
         |}
         |""".stripMargin ->
        "Invalid array index: 3, array size: 2"
    )
    codes.foreach { case (code, error) =>
      testContractError(code, error)
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
           |    let x = [0, 1, 2, 3]
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
      usePayToContractOnly = false,
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
      usePayToContractOnly = false,
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
         |  @using(updateFields = true)
         |  pub fn test2(idx1: U256, idx2: U256) -> () {
         |    array[idx1][idx2] = 0
         |  }
         |
         |  pub fn test3(idx1: U256, idx2: U256) -> (U256) {
         |    return array[idx1][idx2]
         |  }
         |}
         |""".stripMargin

    val contract = Compiler.compileContract(code).rightValue
    val (obj, context) =
      prepareContract(contract, AVector(Val.U256(0)), AVector.fill(6)(Val.U256(0)))

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
           |  @using(updateFields = true)
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
      test(code, mutFields = AVector.fill(8)(Val.U256(0)), args = args)
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
           |  @using(updateFields = true)
           |  fn foo() -> U256 {
           |    x = x + 1
           |    return x
           |  }
           |}
           |""".stripMargin
      test(code, mutFields = AVector(Val.U256(0)))
    }

    {
      info("assign array element")
      val code =
        s"""
           |Contract ArrayTest(mut array: [[U256; 2]; 4]) {
           |  @using(updateFields = true)
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
      test(code, mutFields = AVector.fill(8)(Val.U256(0)))
    }

    {
      info("avoid executing array indexing instructions multiple times")
      val code =
        s"""
           |Contract Foo(mut array: [[U256; 2]; 4], mut x: U256) {
           |  @using(updateFields = true)
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
           |  @using(updateFields = true)
           |  fn foo() -> U256 {
           |    let v = x
           |    x = x + 1
           |    return v
           |  }
           |}
           |""".stripMargin
      test(code, mutFields = AVector.fill(9)(Val.U256(0)))
    }
  }

  it should "get constant array index" in {
    val StatelessParser = new StatelessParser(None)
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
           |  @using(updateFields = true)
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
           |  @using(updateFields = true)
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
      test(code, mutFields = AVector.fill(3)(Val.U256(0)))
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
         |  @using(updateFields = true)
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
    test(code, mutFields = AVector(Val.U256(0)), output = AVector(Val.U256(1)))
  }

  it should "generate efficient code for arrays" in {
    val code =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    let x = [1, 2, 3, 4]
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
        usePayToContractOnly = false,
        argsLength = 0,
        localsLength = 5,
        returnLength = 0,
        instrs = AVector[Instr[StatefulContext]](
          methodSelectorOf("foo()->()"),
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
           |    emit $$Add2$$(a, b)
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      testContractError(contract, "Event Add2 does not exist")
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
           |  $$event Add1(b: U256, a: U256)$$
           |  event Add2(b: U256, a: U256)
           |
           |  pub fn add(a: U256, b: U256) -> (U256) {
           |    emit Add(a, b)
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      testContractError(contract, "These events are defined multiple times: Add1, Add2")
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
           |    emit Add($$a$$, z)
           |    return (a + b)
           |  }
           |}
           |""".stripMargin
      testContractError(contract, "Invalid args type List(U256, Bool) for event Add(U256, U256)")
    }

    {
      info("array/struct field type")
      def contract(tpe: String, value: String): String =
        s"""
           |struct Bar { x: U256 }
           |Contract Foo(result: U256) {
           |
           |  event TestEvent(f: $tpe)
           |
           |  pub fn testArrayEventType() -> (U256) {
           |    $$emit TestEvent($value)$$
           |    return 0
           |  }
           |}
           |""".stripMargin

      testContractError(
        contract("[U256; 2]", "[1, 2]"),
        "Array and struct types are not supported for event \"Foo.TestEvent\""
      )
      testContractError(
        contract("Bar", "Bar { x: 0 }"),
        "Array and struct types are not supported for event \"Foo.TestEvent\""
      )
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
           |    x = 0
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
           |Contract Child(mut x: U256, y: U256) extends Parent($$z$$) {
           |  pub fn foo() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      testContractError(child, "Inherited field \"z\" does not exist in contract \"Child\"")
    }

    {
      info("duplicated function definitions parent before")

      val child =
        s"""
           |$parent
           |Contract Child(mut x: U256, y: U256) extends Parent(x) {
           |  $$pub fn foo() -> () {
           |  }$$
           |}
           |""".stripMargin

      testContractError(child, "These functions are implemented multiple times: foo")
    }
    {
      info("duplicated function definitions parent after")

      val child =
        s"""
           |Contract Child(mut x: U256, y: U256) extends Parent(x) {
           |  $$pub fn foo() -> () {
           |  }$$
           |}
           |$parent
           |""".stripMargin

      testContractError(child, "These functions are implemented multiple times: foo")
    }

    {
      info("duplicated event definitions")

      val child =
        s"""
           |Contract Child(mut x: U256, y: U256) extends Parent(x) {
           |  $$event Foo()$$
           |
           |  pub fn bar() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      testContractError(child, "These events are defined multiple times: Foo")
    }

    {
      info("invalid field in child contract")

      val child =
        s"""
           |Contract Child($$x: U256$$, y: U256) extends Parent(x) {
           |  pub fn bar() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      testContractError(
        child,
        "Invalid contract inheritance fields, expected \"List(Argument(Ident(x),U256,true,false))\", got \"List(Argument(Ident(x),U256,false,false))\""
      )
    }

    {
      info("invalid field in parent contract")

      val child =
        s"""
           |Contract Child(mut x: U256, $$mut y: U256$$) extends Parent(y) {
           |  pub fn bar() -> () {
           |  }
           |}
           |
           |$parent
           |""".stripMargin

      testContractError(
        child,
        "Invalid contract inheritance fields, expected \"List(Argument(Ident(x),U256,true,false))\", got \"List(Argument(Ident(y),U256,true,false))\""
      )
    }

    {
      info("Cyclic inheritance")

      val code =
        s"""
           |$$Contract A(x: U256) extends B(x) {
           |  fn a() -> () {
           |  }
           |}$$
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

      testContractError(code, "Cyclic inheritance detected for contract A")
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
           |  @using(updateFields = true)
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

    def wrongSignature(code: String, funcName: String) = {
      Compiler
        .compileContract(code)
        .leftValue
        .message is s"""Function "$funcName" is implemented with wrong signature"""
    }

    {
      info("Check the function annotations")

      def interface(interfaceAnnotations: String, implAnnotations: String): String =
        s"""
           |Contract Foo(addr: Address) implements Bar {
           |  $implAnnotations
           |  fn bar() -> () {
           |    approveToken!(selfAddress!(), ALPH, 1 alph)
           |    checkCaller!(true, 0)
           |    return
           |  }
           |}
           |
           |Interface Bar {
           |  $interfaceAnnotations
           |  fn bar() -> ()
           |}
           |""".stripMargin

      def abstractContract(interfaceAnnotations: String, implAnnotations: String): String =
        s"""
           |Contract Foo(addr: Address) extends Bar() {
           |  $implAnnotations
           |  fn bar() -> () {
           |    approveToken!(selfAddress!(), ALPH, 1 alph)
           |    checkCaller!(true, 0)
           |    return
           |  }
           |}
           |
           |Abstract Contract Bar() {
           |  $interfaceAnnotations
           |  fn bar() -> ()
           |}
           |""".stripMargin

      def test(
          annotation: String,
          mustBeEqual: Boolean,
          code: (String, String) => String
      ): Assertion = {
        Compiler.compileContract(code("", "")).isRight is true
        Compiler
          .compileContract(code(s"@using($annotation = true)", s"@using($annotation = true)"))
          .isRight is true
        Compiler
          .compileContract(code(s"@using($annotation = false)", s"@using($annotation = false)"))
          .isRight is true
        if (mustBeEqual) {
          Compiler.compileContract(code(s"@using($annotation = false)", "")).isRight is true
          wrongSignature(code(s"@using($annotation = true)", ""), "bar")
          wrongSignature(code(s"@using($annotation = true)", s"@using($annotation = false)"), "bar")
          wrongSignature(code(s"@using($annotation = false)", s"@using($annotation = true)"), "bar")
        } else {
          Compiler.compileContract(code(s"@using($annotation = true)", "")).isRight is true
          Compiler.compileContract(code(s"@using($annotation = false)", "")).isRight is true
          Compiler.compileContract(code("", s"@using($annotation = true)")).isRight is true
          Compiler.compileContract(code("", s"@using($annotation = false)")).isRight is true
        }
      }

      Seq(interface, abstractContract).foreach(code => {
        test(Parser.FunctionUsingAnnotation.usePreapprovedAssetsKey, true, code)
        test(Parser.FunctionUsingAnnotation.useContractAssetsKey, false, code)
        test(Parser.FunctionUsingAnnotation.useCheckExternalCallerKey, false, code)
        test(Parser.FunctionUsingAnnotation.useUpdateFieldsKey, false, code)
      })
    }

    {
      info("Check the function signature")
      def code(modifier: String, args: String, rets: String): String =
        s"""
           |Contract Foo(c: U256) implements Bar {
           |  $modifier fn bar($args) -> ($rets) {
           |    return c
           |  }
           |}
           |
           |Interface Bar {
           |  pub fn bar(a: U256) -> U256
           |}
           |""".stripMargin

      Compiler.compileContract(code("pub", "a: U256", "U256")).isRight is true
      Compiler.compileContract(code("pub", "@unused a: U256", "U256")).isRight is true
      Compiler.compileContract(code("pub", "b: U256", "U256")).isRight is true
      wrongSignature(code("", "a: U256", "U256"), "bar")
      wrongSignature(code("pub", "mut a: U256", "U256"), "bar")
      wrongSignature(code("pub", "a: ByteVec", "U256"), "bar")
      wrongSignature(code("", "a: U256", "ByteVec"), "bar")
    }
  }

  it should "test interface compilation" in {
    {
      info("Interface should contain at least one function")
      val foo =
        s"""
           |Interface $$Foo$$ {
           |}
           |""".stripMargin
      testMultiContractError(foo, "No function definition in Interface Foo")
    }

    {
      info("Interface inheritance should not contain duplicated functions")
      val foo =
        s"""
           |Interface Foo {
           |  @using(checkExternalCaller = false)
           |  fn foo() -> ()
           |}
           |""".stripMargin
      val bar =
        s"""
           |Interface Bar extends Foo {
           |  $$@using(checkExternalCaller = false)
           |  fn foo() -> ()$$
           |}
           |
           |$foo
           |""".stripMargin
      testMultiContractError(bar, "These abstract functions are defined multiple times: foo")
    }

    {
      info("Contract should implement interface functions with the same signature")
      val foo =
        s"""
           |Interface Foo {
           |  @using(checkExternalCaller = false)
           |  fn foo() -> ()
           |}
           |""".stripMargin
      val bar =
        s"""
           |Contract Bar() implements Foo {
           |  $$@using(checkExternalCaller = false)
           |  pub fn foo() -> () {
           |    return
           |  }$$
           |}
           |
           |$foo
           |""".stripMargin
      testMultiContractError(bar, "Function \"foo\" is implemented with wrong signature")
    }

    {
      info("Interface inheritance can be chained")

      val a =
        s"""
           |Interface A {
           |  @using(checkExternalCaller = false)
           |  pub fn a() -> ()
           |}
           |""".stripMargin
      val b =
        s"""
           |Interface B extends A {
           |  @using(checkExternalCaller = false)
           |  pub fn b(x: Bool) -> ()
           |}
           |
           |$a
           |""".stripMargin
      val c =
        s"""
           |Interface C extends B {
           |  @using(checkExternalCaller = false)
           |  pub fn c(x: Bool, y: Bool) -> ()
           |}
           |
           |$b
           |""".stripMargin
      val interface =
        Compiler.compileMultiContract(c).rightValue.contracts(0).asInstanceOf[Ast.ContractInterface]
      interface.funcs.map(_.args.length) is Seq(0, 1, 2)

      {
        info("Contract that only inherits from interface")
        val code =
          s"""
             |Contract Foo() implements C {
             |  @using(checkExternalCaller = false)
             |  pub fn c(x: Bool, y: Bool) -> () {}
             |  @using(checkExternalCaller = false)
             |  pub fn a() -> () {}
             |  @using(checkExternalCaller = false)
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
        info("Contract that inherits from both interface and abstract contract")
        val e =
          s"""
             |Abstract Contract E() implements B {
             |  @using(checkExternalCaller = false)
             |  pub fn e(x: Bool, y: Bool, z: Bool) -> ()
             |}
             |""".stripMargin

        val code =
          s"""
             |Contract Foo() extends E() implements C {
             |  @using(checkExternalCaller = false)
             |  pub fn c(x: Bool, y: Bool) -> () {}
             |  @using(checkExternalCaller = false)
             |  pub fn a() -> () {}
             |  @using(checkExternalCaller = false)
             |  pub fn b(x: Bool) -> () {}
             |  pub fn e(x: Bool, y: Bool, z: Bool) -> () {
             |    a()
             |    b(x)
             |    c(x, y)
             |  }
             |}
             |
             |$c
             |$e
             |""".stripMargin

        val contract =
          Compiler.compileMultiContract(code).rightValue.contracts(0).asInstanceOf[Ast.Contract]
        contract.funcs.map(_.args.length) is Seq(0, 1, 2, 3)
      }
    }

    {
      info("Contract inherits both interface and contract")
      val foo1: String =
        s"""
           |Abstract Contract Foo1() {
           |  @using(checkExternalCaller = false)
           |  fn foo1() -> () {}
           |}
           |""".stripMargin
      val foo2: String =
        s"""
           |Interface Foo2 {
           |  @using(checkExternalCaller = false)
           |  fn foo2() -> ()
           |}
           |""".stripMargin
      val bar1: String =
        s"""
           |Contract Bar1() extends Foo1() implements Foo2 {
           |  @using(checkExternalCaller = false)
           |  fn foo2() -> () {}
           |}
           |$foo1
           |$foo2
           |""".stripMargin
      val bar2: String =
        s"""
           |Contract Bar2() extends Foo1() implements Foo2 {
           |  @using(checkExternalCaller = false)
           |  fn foo2() -> () {}
           |}
           |$foo1
           |$foo2
           |""".stripMargin
      Compiler.compileContract(bar1).isRight is true
      Compiler.compileContract(bar2).isRight is true
    }

    {
      info("Find missing inehritance")
      val code: String =
        s"""
           |Contract Foo() implements $$Bar$$ {
           |  pub fn foo() -> () {
           |     return
           |  }
           |}
           |""".stripMargin

      testContractError(
        code,
        """Contract "Bar" does not exist"""
      )
    }

    {
      info("Find missing extension")
      val code: String =
        s"""
           |Contract Foo() extends $$Bar$$() {
           |  pub fn foo() -> () {
           |     return
           |  }
           |}
           |""".stripMargin

      testContractError(
        code,
        """Contract "Bar" does not exist"""
      )
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

  it should "test template variables" in {
    def runScript(script: StatefulScript, templateVars: AVector[Val]): Unit = {
      val templateCode = script.toTemplateString()
      val pattern      = "\\{(\\d+)\\}".r
      val bytecode = pattern.replaceAllIn(
        templateCode,
        m => {
          val index = m.group(1).toInt
          val value = templateVars(index)
          val instr: Instr[StatefulContext] = value match {
            case Val.Bool(v)    => if (v) ConstTrue else ConstFalse
            case v: Val.I256    => I256Const(v)
            case v: Val.U256    => U256Const(v)
            case v: Val.ByteVec => BytesConst(v)
            case v: Val.Address => AddressConst(v)
          }
          Hex.toHexString(serialize(instr))
        }
      )
      val txScript = deserialize[StatefulScript](Hex.unsafe(bytecode)).rightValue
      val (scriptObj, statefulContext) = prepareStatefulScript(txScript)
      StatefulVM.execute(statefulContext, scriptObj, AVector.empty).isRight is true
      ()
    }

    {
      info("Gen code for template array variables")
      val code =
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main(address: Address, numbers0: [[U256; 2]; 2], bytes: ByteVec, numbers1: [U256; 3]) {
           |  let _ = bytes
           |  let _ = address
           |  assert!(numbers0[1][1] == 3 && numbers0[0][0] == 0, 0)
           |  let _ = numbers0[1]
           |  assert!(numbers1[2] == 2 && numbers1[1] == 1 && numbers1[0] == 0, 0)
         }
           |""".stripMargin
      val script = Compiler.compileTxScript(code).rightValue
      script.toTemplateString() is "010100000700402c{1}{2}{3}{4}1703170217011700{6}{7}{8}170617051704{5}18{0}1816030f2f16000c2f1a0c7b16021603181816060e2f16050d2f1a16040c2f1a0c7b"
      runScript(
        script,
        AVector(
          Val.Address(lockupScriptGen.sample.get),
          Val.U256(0),
          Val.U256(1),
          Val.U256(2),
          Val.U256(3),
          Val.ByteVec(Hex.unsafe("0011")),
          Val.U256(0),
          Val.U256(1),
          Val.U256(2)
        )
      )
    }

    {
      info("Gen code for variable index")
      val code =
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main(numbers: [U256; 3]) {
           |  for (let mut i = 0; i < 3; i = i + 1) {
           |    assert!(numbers[i] == i, 0)
           |  }
           |}
           |""".stripMargin
      val script = Compiler.compileTxScript(code).rightValue
      script.toTemplateString() is "0101000004001b{0}{1}{2}1702170117000c170316030f314c0f16037a0f314d7816032f0c7b16030d2a17034a2d"
      runScript(script, AVector(Val.U256(0), Val.U256(1), Val.U256(2)))
    }

    {
      info("Access template array in non-main functions")
      val code =
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main(numbers: [U256; 3]) {
           |  let _ = numbers[0]
           |  foo()
           |
           |  fn foo() -> () {
           |    for (let mut i = 0; i < 3; i = i + 1) {
           |      assert!(numbers[i] == i, 0)
           |    }
           |  }
           |}
           |""".stripMargin
      val script = Compiler.compileTxScript(code).rightValue
      script.toTemplateString() is "02010000030009{0}{1}{2}170217011700160018000100000004001b{0}{1}{2}1702170117000c170316030f314c0f16037a0f314d7816032f0c7b16030d2a17034a2d"
      runScript(script, AVector(Val.U256(0), Val.U256(1), Val.U256(2)))
    }

    {
      info("Throw an error if the index is greater than max var index")
      val code =
        s"""
           |TxScript Main(numbers: [U256; 3]) {
           |  let _ = numbers[257]
           |}
           |""".stripMargin
      Compiler
        .compileTxScript(code)
        .leftValue
        .message is "Invalid array index 257"
    }

    {
      info("Throw an error if the number of template variables exceeds the limit")
      val code =
        s"""
           |TxScript Main(numbers0: [U256; 128], numbers1: [U256; 128]) {
           |  return
           |}
           |""".stripMargin
      Compiler
        .compileTxScript(code)
        .leftValue
        .message is "Number of template variables more than 255"
    }

    {
      info("Gen code for struct template variables")
      val code =
        s"""
           |struct Foo {
           |  x: U256,
           |  y: ByteVec
           |}
           |@using(preapprovedAssets = false)
           |TxScript Main(a: U256, foo: Foo, array: [Foo; 2], b: Bool) {
           |  assert!(a == 0, 0)
           |  assert!(b, 0)
           |  assert!(foo.x == 0 && foo.y == #00, 0)
           |  assert!(array[0].y == #01 && array[0].x == 1, 0)
           |  assert!(array[1].y == #02 && array[1].x == 2, 0)
           |}
           |""".stripMargin

      val script = Compiler.compileTxScript(code).rightValue
      script.toTemplateString() is "010100000600402f{1}{2}17011700{3}{4}{5}{6}1705170417031702{0}0c2f0c7b{7}0c7b16000c2f1601140100411a0c7b16031401014116020d2f1a0c7b16051401024116040e2f1a0c7b"
      runScript(
        script,
        AVector(
          Val.U256(0),
          Val.U256(0),
          Val.ByteVec(Hex.unsafe("00")),
          Val.U256(1),
          Val.ByteVec(Hex.unsafe("01")),
          Val.U256(2),
          Val.ByteVec(Hex.unsafe("02")),
          Val.True
        )
      )
    }
  }

  it should "use ApproveAlph instr for approve ALPH" in {
    val code =
      s"""
         |TxScript Main(address: Address, fooId: ByteVec) {
         |  Foo(fooId).foo{address -> ALPH: 1}()
         |}
         |
         |Interface Foo {
         |  @using(preapprovedAssets = true)
         |  pub fn foo() -> ()
         |}
         |""".stripMargin
    val script = Compiler.compileTxScript(code).rightValue
    script.toTemplateString() is "01010300000007{0}0da20c0c{1}0100"
  }

  it should "use braces syntax for functions that uses preapproved assets" in {
    def code(
        bracesPart: String = "{callerAddress!() -> ALPH: amount}",
        usePreapprovedAssets: Boolean = true,
        useAssetsInContract: Boolean = false
    ): String =
      s"""
         |TxScript Main(fooContractId: ByteVec, amount: U256) {
         |  let foo = Foo(fooContractId)
         |  $$foo.foo${bracesPart}()$$
         |}
         |
         |Interface Foo {
         |  @using(preapprovedAssets = $usePreapprovedAssets, assetsInContract = $useAssetsInContract)
         |  pub fn foo() -> ()
         |}
         |""".stripMargin
    Compiler.compileTxScript(replace(code())).isRight is true
    testTxScriptError(
      code(bracesPart = ""),
      "Function `foo` needs preapproved assets, please use braces syntax"
    )
    testTxScriptError(
      code(usePreapprovedAssets = false),
      "Function `foo` does not use preapproved assets"
    )
    testTxScriptError(
      code(usePreapprovedAssets = false, useAssetsInContract = true),
      "Function `foo` does not use preapproved assets"
    )
  }

  it should "check if contract assets is used in the function" in {
    def code(useAssetsInContract: String = "false", instr: String = "return"): String =
      s"""
         |Contract Foo() {
         |  $$@using(assetsInContract = $useAssetsInContract)
         |  fn foo() -> () {
         |    $instr
         |  }$$
         |}
         |""".stripMargin
    Compiler.compileContract(replace(code())).isRight is true

    val statements = Seq(
      "transferTokenFromSelf!(callerAddress!(), ALPH, 1 alph)",
      "transferTokenFromSelf!(callerAddress!(), selfTokenId!(), 1 alph)",
      "destroySelf!(callerAddress!())",
      "transferTokenToSelf!(callerAddress!(), ALPH, 1 alph)",
      "transferTokenToSelf!(callerAddress!(), selfTokenId!(), 1 alph)",
      "approveToken!(selfAddress!(), ALPH, 1 alph)"
    )
    statements.foreach { stmt =>
      Compiler.compileContract(replace(code("true", stmt))).isRight is true
    }
    testContractError(
      code("true"),
      "Function \"Foo.foo\" does not use contract assets, but the annotation `assetsInContract` is enabled. " +
        "Please remove the `assetsInContract` annotation or set it to `enforced`"
    )
    testContractError(
      code("true", "payGasFee!(callerAddress!(), 1 alph)"),
      "Function \"Foo.foo\" does not use contract assets, but the annotation `assetsInContract` is enabled. " +
        "Please remove the `assetsInContract` annotation or set it to `enforced`"
    )
    Compiler.compileContract(replace(code("enforced"))).isRight is true
    statements.take(3).foreach { stmt =>
      testContractError(
        code("false", stmt),
        "Function \"Foo.foo\" uses contract assets, please use annotation `assetsInContract = true`."
      )
    }
    Seq(
      "transferTokenToSelf!(callerAddress!(), ALPH, 1 alph)",
      "transferTokenToSelf!(callerAddress!(), selfTokenId!(), 1 alph)"
    ).foreach { stmt =>
      testContractError(
        code("false", stmt),
        "Function \"Foo.foo\" transfers assets to the contract, please set either `assetsInContract` or `payToContractOnly` to true."
      )
    }
  }

  it should "check types for braces syntax" in {
    def code(
        address: String = "Address",
        tokenId: String = "ByteVec",
        tokenAmount: String = "U256",
        arg: String = "address -> tokenId: tokenAmount"
    ): String =
      s"""
         |TxScript Main(
         |  fooContractId: ByteVec,
         |  address: ${address},
         |  tokenId: ${tokenId},
         |  tokenAmount: ${tokenAmount}
         |) {
         |  let foo = Foo(fooContractId)
         |  foo.foo{$arg}()
         |}
         |
         |Interface Foo {
         |  @using(preapprovedAssets = true)
         |  pub fn foo() -> ()
         |}
         |""".stripMargin
    Compiler.compileTxScript(code()).isRight is true
    testTxScriptError(
      code(address = "Bool", arg = s"$$address$$ -> tokenId: tokenAmount"),
      "Invalid address type: Variable(Ident(address))"
    )
    testTxScriptError(
      code(tokenId = "Bool", arg = s"address -> $$tokenId$$: tokenAmount"),
      "Invalid token amount type: List((Variable(Ident(tokenId)),Variable(Ident(tokenAmount))))"
    )
    testTxScriptError(
      code(tokenAmount = "Bool", arg = s"address -> $$tokenId$$: tokenAmount"),
      "Invalid token amount type: List((Variable(Ident(tokenId)),Variable(Ident(tokenAmount))))"
    )
  }

  it should "compile events with <= 8 fields" in {
    def code(nineFields: Boolean): String = {
      val eventStr = if (nineFields) ", a9: U256" else ""
      val emitStr  = if (nineFields) ", 9" else ""
      s"""
         |Contract Foo(tmp: U256) {
         |  $$event Foo(a1: U256, a2: U256, a3: U256, a4: U256, a5: U256, a6: U256, a7: U256, a8: U256 $eventStr)$$
         |
         |  pub fn foo() -> () {
         |    emit Foo(1, 2, 3, 4, 5, 6, 7, 8 $emitStr)
         |    return
         |  }
         |}
         |""".stripMargin
    }
    Compiler.compileContract(replace(code(false))).isRight is true
    testContractError(code(true), "Max 8 fields allowed for contract events")
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
        AVector[Instr[StatefulContext]](
          ConstTrue,
          IfFalse(1),
          Return
        )
    }

    {
      info("Simple if statement without return")
      val code =
        s"""
           |Contract Foo() {
           |  fn $$foo$$() -> U256 {
           |    if (true) {
           |      return 1
           |    }
           |  }
           |}
           |""".stripMargin
      testContractError(code, "Expected return statement for function \"foo\"")
    }

    {
      info("Invalid type of condition expr")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> U256 {
           |    if ($$0$$) {
           |      return 0
           |    } else {
           |      return 1
           |    }
           |  }
           |}
           |""".stripMargin
      testContractError(code, "Invalid type of condition expr: List(U256)")
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
        AVector[Instr[StatefulContext]](
          ConstTrue,
          IfFalse(2),
          Return,
          Jump(1),
          Return
        )
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
           |    $$if (true) {
           |      return
           |    }$$ else if (false) {
           |      return
           |    }
           |  }
           |}
           |""".stripMargin
      testContractError(
        code,
        "If ... else if constructs should be terminated with an else statement"
      )
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
           |    return $$if (false) 1 else #00$$
           |  }
           |}
           |""".stripMargin

      testContractError(
        code,
        "Invalid types of if-else expression branches, expected \"List(ByteVec)\", got \"List(U256)\""
      )
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

      Compiler.compileContract(code).leftValue.format(code) is
        """-- error (5:3): Syntax error
          |5 |  }
          |  |  ^
          |  |  Expected `else` statement
          |  |------------------------------------------------------------------------------------------
          |  |Description: `if/else` expressions require both `if` and `else` statements to be complete.
          |""".stripMargin
    }

    {
      info("Invalid type of condition expr")
      val code =
        s"""
           |Contract Foo() {
           |  fn foo() -> U256 {
           |    return if ($$0$$) 0 else 1
           |  }
           |}
           |""".stripMargin

      testContractError(code, "Invalid type of condition expr: List(U256)")
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
         |  $$const C = true$$
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin
    testContractError(code, "These constant variables are defined multiple times: C")
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
      info("Enum field does not exist")
      val code =
        s"""
           |Contract Foo() {
           |  enum ErrorCodes {
           |    Error0 = 0
           |  }
           |  pub fn foo() -> U256 {
           |    return ErrorCodes.$$Error1$$
           |  }
           |}
           |""".stripMargin
      testContractError(
        code,
        "Variable foo.ErrorCodes.Error1 does not exist or is used before declaration"
      )
    }
    {
      info("Enum field does not exist")
      val code =
        s"""
           |Contract Foo() {
           |  $$enum ErrorCodes$$ {
           |  }
           |}
           |""".stripMargin
      testContractError(code, "No field definition in Enum ErrorCodes")
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

    {
      info("Fail if there are different field types")
      val code =
        s"""
           |Contract C() extends A(), B() {}
           |
           |Abstract Contract A() {
           |  enum Color {
           |    $$Red = 0$$
           |  }
           |}
           |
           |Abstract Contract B() {
           |  enum Color {
           |    Blue = #0011
           |  }
           |}
           |""".stripMargin

      testContractError(code, "There are different field types in the enum Color: ByteVec,U256")
    }

    {
      info("Fail if there are conflict enum fields")
      val code =
        s"""
           |Contract C() extends A(), B() {}
           |
           |Abstract Contract A() {
           |  enum Color {
           |    Red = 0
           |  }
           |}
           |
           |Abstract Contract B() {
           |  enum Color {
           |    $$Red = 1$$
           |  }
           |}
           |""".stripMargin

      testContractError(code, "There are conflict fields in the enum Color: Red")
    }

    {
      info("Merge enums")
      val code =
        s"""
           |Contract Foo() extends B(), C() {
           |  pub fn foo() -> () {
           |    assert!(Color.Red == 0, 0)
           |    assert!(Color.Green == 1, 0)
           |    assert!(Color.Blue == 2, 0)
           |    assert!(EA.A == 0, 0)
           |    assert!(EB.B == 0, 0)
           |    assert!(EC.C == 0, 0)
           |  }
           |}
           |
           |Abstract Contract A() {
           |  enum EA {
           |    A = 0
           |  }
           |
           |  enum Color {
           |    Red = 0
           |  }
           |}
           |
           |Abstract Contract B() extends A() {
           |  enum EB {
           |    B = 0
           |  }
           |
           |  enum Color {
           |    Green = 1
           |  }
           |}
           |
           |Abstract Contract C() {
           |  enum EC {
           |    C = 0
           |  }
           |
           |  enum Color {
           |    Blue = 2
           |  }
           |}
           |""".stripMargin

      test(code)
    }
  }

  it should "check unused variables" in {
    {
      info("Check unused local variables in AssetScript")
      val code =
        s"""
           |AssetScript Foo {
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
           |TxScript Foo {
           |  let b = 1
           |  foo()
           |
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
           |TxScript Foo(a: U256, b: U256) {
           |  foo(a)
           |
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
           |  pub fn foo() -> () {}
           |}
           |
           |Abstract Contract Bar(a: U256, b: U256) {
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
           |  @using(checkExternalCaller = false)
           |  pub fn i() -> ()
           |}
           |Abstract Contract Base(v: U256) {
           |  fn base() -> () {
           |    assert!(v == 0, 0)
           |  }
           |}
           |Contract Bar(v: U256) extends Base(v) implements I {
           |  @using(checkExternalCaller = false)
           |  pub fn i() -> () {
           |    assert!(v == 0, 0)
           |    base()
           |  }
           |}
           |Contract Foo(v: U256) extends Base(v) {
           |  @using(checkExternalCaller = false)
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
           |  fn foo(x: U256) -> () {
           |    assert!(x == 0, 0)
           |  }
           |}
           |Contract Bar() extends Foo() {
           |  @using(checkExternalCaller = false)
           |  pub fn bar() -> () { foo(0) }
           |}
           |Contract Baz() extends Foo() {
           |  @using(checkExternalCaller = false)
           |  pub fn baz() -> () { foo(0) }
           |}
           |""".stripMargin
      val (contracts, _, _) = Compiler.compileProject(code).rightValue
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
          methodSelectorOf("foo()->()"),
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
          methodSelectorOf("foo()->(U256)"),
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
         |$$Abstract Contract Foo() {
         |  pub fn foo() -> () {}
         |  pub fn bar() -> () {}
         |}$$
         |""".stripMargin
    testContractError(foo, "Code generation is not supported for abstract contract \"Foo\"")
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
         |Contract Foo() {
         |  const C0 = 0
         |  const C1 = 1
         |  enum Errors {
         |    Error0 = 0
         |    Error1 = 1
         |  }
         |  pub fn foo() -> () {}
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
      Compiler.compileContractFull(code).rightValue.warnings is
        AVector(
          s"""Function "Foo.foo" updates fields. Please use "@using(updateFields = true)" for the function."""
        )
    }

    {
      info("Call internal function which does not update fields")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> () {
           |    bar()
           |  }
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
           |  @using(assetsInContract = true)
           |  pub fn foo() -> () {
           |    let _ = selfContractId!()
           |    transferTokenToSelf!(callerAddress!(), ALPH, 1 alph)
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
           |  @using(checkExternalCaller = false)
           |  pub fn foo(code: ByteVec, fields: ByteVec) -> () {
           |    migrateWithFields!(code, #00, fields)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContractFull(code).rightValue.warnings is
        AVector(
          s"""Function "Foo.foo" updates fields. Please use "@using(updateFields = true)" for the function."""
        )
    }

    {
      info("Call internal update fields functions")
      val code =
        s"""
           |Contract Foo(mut a: U256) {
           |  pub fn foo() -> () {
           |    bar()
           |  }
           |  @using(updateFields = true)
           |  pub fn bar() -> () {
           |    a = 0
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("Call external update fields functions")
      val code =
        s"""
           |Contract Foo(bar: Bar) {
           |  pub fn foo() -> () {
           |    bar.bar()
           |  }
           |}
           |Contract Bar(mut x: [U256; 2]) {
           |  @using(updateFields = true)
           |  pub fn bar() -> () {
           |    x[0] = 0
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
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
      Compiler.compileContractFull(code, 0).rightValue.warnings is AVector.empty[String]
    }

    {
      info("Function use preapproved assets but does not update fields")
      val code =
        s"""
           |Contract Foo() {
           |  @using(preapprovedAssets = true)
           |  pub fn foo(tokenId: ByteVec) -> () {
           |    assert!(tokenRemaining!(callerAddress!(), tokenId) == 1, 0)
           |    assert!(tokenRemaining!(callerAddress!(), ALPH) == 1, 0)
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("Mutual function calls")
      val code =
        s"""
           |Contract Foo(bar: Bar, mut a: U256) {
           |  pub fn foo() -> () {
           |    bar.bar()
           |  }
           |  @using(updateFields = true)
           |  pub fn update() -> () {
           |    a = 0
           |  }
           |}
           |Contract Bar(foo: Foo) {
           |  pub fn bar() -> () {
           |    foo.update()
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("Call interface update fields functions")
      def code(updateFields: Boolean): String =
        s"""
           |Contract Foo() {
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
      Compiler.compileContractFull(code(true)).isRight is true
    }

    {
      info("Warning for functions which does not update fields but has @using(updateFields = true)")
      val code =
        s"""
           |Contract Foo() {
           |  @using(updateFields = true)
           |  pub fn foo() -> () {
           |    checkCaller!(true, 0)
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings is AVector(
        s"""Function "Foo.foo" does not update fields. Please remove "@using(updateFields = true)" for the function."""
      )
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

  it should "use alph instructions in code generation" in {
    val code =
      s"""
         |Contract Foo() {
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn foo(from: Address, to: Address) -> () {
         |    approveToken!(from, ALPH, 1 alph)
         |    tokenRemaining!(from, ALPH)
         |    transferToken!(from, to, ALPH, 1 alph)
         |    transferTokenToSelf!(from, ALPH, 1 alph)
         |    transferTokenFromSelf!(to, ALPH, 1 alph)
         |  }
         |
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn bar(from: Address, to: Address, tokenId: ByteVec) -> () {
         |    approveToken!(from, tokenId, 1)
         |    tokenRemaining!(from, tokenId)
         |    transferToken!(from, to, tokenId, 1)
         |    transferTokenToSelf!(from, tokenId, 1)
         |    transferTokenFromSelf!(to, tokenId, 1)
         |  }
         |}
         |""".stripMargin
    val tokenInstrs = Seq(
      ApproveToken,
      TokenRemaining,
      TransferToken,
      TransferTokenToSelf,
      TransferTokenFromSelf
    )
    val alphInstrs = Seq(
      ApproveAlph,
      AlphRemaining,
      TransferAlph,
      TransferAlphToSelf,
      TransferAlphFromSelf
    )
    val method0 = Compiler.compileContract(code).rightValue.methods(0)
    tokenInstrs.foreach(instr => method0.instrs.contains(instr) is false)
    alphInstrs.foreach(instr => method0.instrs.contains(instr) is true)

    val method1 = Compiler.compileContract(code).rightValue.methods(1)
    tokenInstrs.foreach(instr => method1.instrs.contains(instr) is true)
    alphInstrs.foreach(instr => method1.instrs.contains(instr) is false)
  }

  it should "load both mutable and immutable fields" in {
    val code =
      s"""
         |Contract Foo(mut a: U256, mut x: [U256; 2], b: Bool, y: [Bool; 2]) {
         |  pub fn foo(z: I256) -> () {
         |    a = 0
         |    x[0] = 0
         |    assert!(x[1] != 0, 0)
         |    assert!(z != 0i, 0)
         |  }
         |
         |  pub fn bar(z: ByteVec) -> () {
         |    assert!(y[1], 0)
         |    assert!(z != #, 0)
         |  }
         |}
         |""".stripMargin
    val contract = Compiler.compileContract(code).rightValue
    contract is StatefulContract(
      6,
      methods = AVector(
        Method[StatefulContext](
          isPublic = true,
          usePreapprovedAssets = false,
          useContractAssets = false,
          usePayToContractOnly = false,
          1,
          1,
          0,
          AVector[Instr[StatefulContext]](
            methodSelectorOf("foo(I256)->()"),
            U256Const0,
            StoreMutField(0.toByte),
            U256Const0,
            StoreMutField(1.toByte),
            LoadMutField(2.toByte),
            U256Const0,
            U256Neq,
            U256Const0,
            AssertWithErrorCode
          ) ++
            AVector(LoadLocal(0.toByte), I256Const0, I256Neq, U256Const0, AssertWithErrorCode)
        ),
        Method[StatefulContext](
          isPublic = true,
          usePreapprovedAssets = false,
          useContractAssets = false,
          usePayToContractOnly = false,
          1,
          1,
          0,
          AVector[Instr[StatefulContext]](
            methodSelectorOf("bar(ByteVec)->()"),
            LoadImmField(2.toByte),
            U256Const0,
            AssertWithErrorCode
          ) ++
            AVector(
              LoadLocal(0.toByte),
              BytesConst(Val.ByteVec(ByteString.empty)),
              ByteVecNeq,
              U256Const0,
              AssertWithErrorCode
            )
        )
      )
    )
  }

  it should "load both mutable and immutable fields by index" in {
    val code =
      s"""
         |Contract Foo(mut a: U256, mut x: [U256; 2], b: Bool, y: [Bool; 2]) {
         |  pub fn foo(z: I256, c: U256) -> () {
         |    a = 0
         |    x[0] = 0
         |    assert!(x[c + 1] != 0, 0)
         |    assert!(z != 0i, 0)
         |  }
         |
         |  pub fn bar(z: ByteVec, c: U256) -> () {
         |    assert!(y[c + 1], 0)
         |    assert!(z != #, 0)
         |  }
         |}
         |""".stripMargin
    val contract = Compiler.compileContract(code).rightValue
    contract is StatefulContract(
      6,
      methods = AVector(
        Method[StatefulContext](
          isPublic = true,
          usePreapprovedAssets = false,
          useContractAssets = false,
          usePayToContractOnly = false,
          argsLength = 2,
          localsLength = 2,
          returnLength = 0,
          instrs = AVector[Instr[StatefulContext]](
            methodSelectorOf("foo(I256,U256)->()"),
            U256Const0,
            StoreMutField(0.toByte),
            U256Const0,
            StoreMutField(1.toByte),
            LoadLocal(1.toByte),
            U256Const1,
            U256Add,
            Dup,
            U256Const2,
            U256Lt,
            Assert,
            U256Const1,
            U256Add,
            LoadMutFieldByIndex,
            U256Const0,
            U256Neq,
            U256Const0,
            AssertWithErrorCode
          ) ++
            AVector(LoadLocal(0.toByte), I256Const0, I256Neq, U256Const0, AssertWithErrorCode)
        ),
        Method[StatefulContext](
          isPublic = true,
          usePreapprovedAssets = false,
          useContractAssets = false,
          usePayToContractOnly = false,
          2,
          2,
          0,
          AVector[Instr[StatefulContext]](
            methodSelectorOf("bar(ByteVec,U256)->()"),
            LoadLocal(1.toByte),
            U256Const1,
            U256Add,
            Dup,
            U256Const2,
            U256Lt,
            Assert,
            U256Const1,
            U256Add,
            LoadImmFieldByIndex,
            U256Const0,
            AssertWithErrorCode
          ) ++
            AVector(
              LoadLocal(0.toByte),
              BytesConst(Val.ByteVec(ByteString.empty)),
              ByteVecNeq,
              U256Const0,
              AssertWithErrorCode
            )
        )
      )
    )
  }

  // TODO Test error position
  it should "compile exp expressions" in {
    def code(baseType: String, expType: String, op: String, retType: String): String = {
      s"""
         |Contract Foo() {
         |  pub fn foo(base: $baseType, exp: $expType) -> $retType {
         |    return base $op exp
         |  }
         |}
         |""".stripMargin
    }

    Compiler.compileContract(code("I256", "I256", "**", "I256")).leftValue.message is
      "Invalid param types List(I256, I256) for ** operator"
    Compiler.compileContract(code("U256", "U256", "**", "U256")).isRight is true
    Compiler.compileContract(code("U256", "U256", "**", "I256")).leftValue.message is
      s"""Invalid return types "List(U256)" for func foo, expected "List(I256)""""
    Compiler.compileContract(code("I256", "U256", "**", "I256")).isRight is true
    Compiler.compileContract(code("I256", "U256", "**", "U256")).leftValue.message is
      s"""Invalid return types "List(I256)" for func foo, expected "List(U256)""""

    Compiler.compileContract(code("I256", "I256", "|**|", "I256")).leftValue.message is
      "|**| accepts U256 only"
    Compiler.compileContract(code("U256", "U256", "|**|", "U256")).isRight is true
    Compiler.compileContract(code("I256", "U256", "|**|", "U256")).leftValue.message is
      "Invalid param types List(I256, U256) for |**| operator"
    Compiler.compileContract(code("U256", "U256", "|**|", "I256")).leftValue.message is
      """Invalid return types "List(U256)" for func foo, expected "List(I256)""""
  }

  it should "compile check equality operation" in {
    def code(inputType: String, op: String): String = {
      s"""
         |Contract Foo() {
         |  pub fn foo(a: $inputType, b: $inputType) -> () {
         |    if (a $op b) {
         |      emit Debug(`hello`)
         |    }
         |  }
         |}
         |""".stripMargin
    }

    def success(inputType: String, op: String) = {
      Compiler.compileContract(code(inputType, op)).isRight is true
    }

    def fail(inputType: String, op: String) = {
      Compiler
        .compileContract(code(inputType, op))
        .leftValue
        .message is s"Expect I256/U256 for $op operator"
    }

    Seq(">", "<", "<=", ">=", "==", "!=").foreach(success("I256", _))
    Seq(">", "<", "<=", ">=", "==", "!=").foreach(success("U256", _))

    Seq("==", "!=").foreach(success("Address", _))
    Seq("==", "!=").foreach(success("ByteVec", _))
    Seq("==", "!=").foreach(success("Bool", _))
    Seq(">", "<", "<=", ">=").foreach(fail("Address", _))
    Seq(">", "<", "<=", ">=").foreach(fail("ByteVec", _))
    Seq(">", "<", "<=", ">=").foreach(fail("Bool", _))
  }

  it should "compile Schnorr address lockup script" in {
    val (script, warnings) =
      Compiler.compileAssetScript(Address.schnorrAddressLockupScript).rightValue
    warnings.isEmpty is true
    script is StatelessScript.unsafe(
      AVector(
        Method[StatelessContext](
          isPublic = true,
          usePreapprovedAssets = false,
          useContractAssets = false,
          usePayToContractOnly = false,
          argsLength = 0,
          localsLength = 0,
          returnLength = 0,
          instrs = AVector[Instr[StatelessContext]](
            TxId,
            TemplateVariable("publicKey", Val.ByteVec, 0),
            GetSegregatedSignature,
            VerifyBIP340Schnorr
          )
        )
      )
    )
    script.toTemplateString() is "0101000000000458{0}8685"
  }

  it should "check mutable field assignments" in {
    def unassignedErrorMsg(contract: String, fields: Seq[String]): String = {
      s"There are unassigned mutable fields in contract $contract: ${fields.mkString(",")}"
    }

    {
      info("Check assignment for mutable field")
      val code =
        s"""
           |Contract $$Foo$$(mut a: U256) {
           |  pub fn foo() -> U256 {
           |    return a
           |  }
           |}
           |""".stripMargin
      testContractError(code, unassignedErrorMsg("Foo", Seq("a")))
    }

    {
      info("No error if field is assigned")
      val code =
        s"""
           |Contract Foo(mut a: U256) {
           |  pub fn foo() -> () {
           |    a = 0
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("Check assignment for multiple mutable fields")
      val code =
        s"""
           |Contract $$Foo$$(mut a: U256, mut b: U256) {
           |  pub fn foo() -> (U256, U256) {
           |    return a, b
           |  }
           |}
           |""".stripMargin
      testContractError(code, unassignedErrorMsg("Foo", Seq("a", "b")))
    }

    {
      info("Check assignment for mutable array field")
      val code =
        s"""
           |Contract $$Foo$$(mut a: [U256; 2]) {
           |  pub fn foo() -> [U256; 2] {
           |    return a
           |  }
           |}
           |""".stripMargin
      testContractError(code, unassignedErrorMsg("Foo", Seq("a")))
    }

    {
      info("Check assignment for multiple mutable array fields")
      val code =
        s"""
           |Contract $$Foo$$(mut a: [U256; 2], mut b: [U256; 2]) {
           |  pub fn foo() -> [[U256; 2]; 2] {
           |    return [a, b]
           |  }
           |}
           |""".stripMargin
      testContractError(code, unassignedErrorMsg("Foo", Seq("a", "b")))
    }

    {
      info("No error if array field is assigned")
      val code =
        s"""
           |Contract Foo(mut a: [U256; 2]) {
           |  @using(updateFields = true)
           |  pub fn foo() -> () {
           |    a = [0, 0]
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("No error if array element is assigned(case 0)")
      val code =
        s"""
           |Contract Foo(mut a: [U256; 2]) {
           |  @using(updateFields = true)
           |  pub fn foo() -> () {
           |    a[0] = 0
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("No error if array element is assigned(case 1)")
      val code =
        s"""
           |Contract Foo(mut a: [U256; 2]) {
           |  @using(updateFields = true)
           |  pub fn foo(index: U256) -> () {
           |    a[index] = 0
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }
  }

  it should "check mutable local vars assignment" in {
    def unassignedErrorMsg(contract: String, func: String, fields: Seq[String]): String = {
      s"There are unassigned mutable local vars in function $contract.$func: ${fields.mkString(",")}"
    }

    {
      info("Check assignment for mutable local vars")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn $$foo$$() -> U256 {
           |    let mut a = 0
           |    return a
           |  }
           |}
           |""".stripMargin
      testContractError(
        code,
        unassignedErrorMsg(
          "Foo",
          "foo",
          Seq("foo.a")
        )
      )
    }

    {
      info("No error if local vars is assigned")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> U256 {
           |    let mut a = 0
           |    a = 1
           |    return a
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("Check assignment for multiple mutable local vars")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn $$foo$$() -> (U256, U256) {
           |    let mut a = 0
           |    let mut b = 0
           |    return a, b
           |  }
           |}
           |""".stripMargin
      testContractError(
        code,
        unassignedErrorMsg(
          "Foo",
          "foo",
          Seq("foo.a", "foo.b")
        )
      )
    }

    {
      info("Check assignment for mutable local array var")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn $$foo$$() -> [U256; 2] {
           |    let mut a = [0, 0]
           |    return a
           |  }
           |}
           |""".stripMargin
      testContractError(code, unassignedErrorMsg("Foo", "foo", Seq("foo.a")))
    }

    {
      info("Check assignment for multiple mutable local array vars")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn $$foo$$() -> [[U256; 2]; 2] {
           |    let mut a = [0, 0]
           |    let mut b = [0, 0]
           |    return [a, b]
           |  }
           |}
           |""".stripMargin
      testContractError(code, unassignedErrorMsg("Foo", "foo", Seq("foo.a", "foo.b")))
    }

    {
      info("No error if local array var is assigned")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> () {
           |    let mut a = [0, 0]
           |    a = [1, 1]
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("No error if array element is assigned(case 0)")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> () {
           |    let mut a = [0, 0]
           |    a[0] = 1
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }

    {
      info("No error if array element is assigned(case 1)")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo(index: U256) -> () {
           |    let mut a = [0, 0]
           |    a[index] = 1
           |  }
           |}
           |""".stripMargin
      Compiler.compileContract(code).isRight is true
    }
  }

  it should "generate code for std id" in {
    def code(contractAnnotation: String, interfaceAnnotation: String): String =
      s"""
         |$contractAnnotation
         |Contract Bar() implements Foo {
         |  pub fn foo() -> () {}
         |}
         |
         |$interfaceAnnotation
         |Interface Foo {
         |  pub fn foo() -> ()
         |}
         |""".stripMargin

    Compiler.compileContract(code("", "")).rightValue.fieldLength is 0
    Compiler.compileContract(code("", "@std(id = #0001)")).rightValue.fieldLength is 1
    Compiler
      .compileContract(code("@std(enabled = true)", "@std(id = #0001)"))
      .rightValue
      .fieldLength is 1
    Compiler
      .compileContract(code("@std(enabled = false)", "@std(id = #0001)"))
      .rightValue
      .fieldLength is 0
  }

  it should "use built-in contract functions" in {
    def code(
        contractAnnotation: String,
        interfaceAnnotation: String,
        input0: String,
        input1: String
    ): String =
      s"""
         |$contractAnnotation
         |Contract Bar(a: U256, @unused mut b: I256) implements Foo {
         |  @using(checkExternalCaller = false)
         |  pub fn foo() -> () {
         |    let (bs0, bs1) = Bar.encodeFields!($input0, $input1)
         |    assert!(bs0 == #, 0)
         |    assert!(bs1 == #, 0)
         |  }
         |}
         |
         |$interfaceAnnotation
         |Interface Foo {
         |  @using(checkExternalCaller = false)
         |  pub fn foo() -> ()
         |}
         |""".stripMargin

    Compiler.compileContract(code("", "", "1", "2i")).rightValue.fieldLength is 2
    Compiler.compileContract(code("", "@std(id = #0001)", "1", "2i")).rightValue.fieldLength is 3
    Compiler
      .compileContract(code("@std(enabled = true)", "@std(id = #0001)", "1", "2i"))
      .rightValue
      .fieldLength is 3
    Compiler
      .compileContract(code("@std(enabled = false)", "@std(id = #0001)", "1", "2i"))
      .rightValue
      .fieldLength is 2
  }

  it should "check whether a function is static or not" in {
    def code(testCode: String) = {
      s"""
         |Contract Foo() {
         |  pub fn foo(bar: Bar) -> () {
         |    ${testCode}
         |    return
         |  }
         |}
         |Contract Bar() {
         |  pub fn bar() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    }
    testContractFullError(
      code(s"let x = bar.$$encodeFields!$$()"),
      s"""Expected non-static function, got "Bar.encodeFields""""
    )
    testContractFullError(
      code(s"bar.$$encodeFields!$$()"),
      s"""Expected non-static function, got "Bar.encodeFields""""
    )
    testContractFullError(
      code(s"let x = Bar.$$bar$$()"),
      s"""Expected static function, got "Bar.bar""""
    )
    testContractFullError(code(s"Bar.$$bar$$()"), s"""Expected static function, got "Bar.bar"""")
  }

  it should "fail when using wrong operator on addresses" in {
    def code(operator: String) = {
      s"""
         |Contract Foo() {
         |  pub fn foo(a1: Address, a2: Address) -> () {
         |    if($$a1 $operator a2$$) {
         |      return
         |    } else {
         |    return
         |    }
         |  }
         |}
         |""".stripMargin
    }
    def test(operator: String) = {
      testContractError(
        code(operator),
        s"""Expect I256/U256 for $operator operator"""
      )
    }

    test("<")
    test(">")
    test(">=")
    test("<=")
  }

  it should "check parent std interface id" in {
    val code = s"""
                  |@std(id = #0005)
                  |Interface Foo {
                  |    pub fn foo() -> ()
                  |}
                  |
                  |@std(id = #000401)
                  |$$Interface Bar extends Foo {
                  |    pub fn foo() -> ()
                  |}$$
                  |""".stripMargin

    testContractFullError(code, "The std id of interface Bar should start with 0005")
  }

  it should "report args type error for function at the call site" in {
    val code =
      s"""
         |Contract Foo(barContract: BarContract) {
         |  pub fn foo() -> () {
         |    $$barContract.bar(#00)$$
         |  }
         |}
         |
         |Contract BarContract() {
         |  pub fn bar(address: Address, byteVec: ByteVec) -> () {
         |    assert!(byteVecToAddress!(byteVec) == address, 0)
         |  }
         |}
         |""".stripMargin

    testContractFullError(
      code,
      "Invalid args type \"List(ByteVec)\" for func bar, expected \"List(Address, ByteVec)\""
    )
  }

  it should "check if function return values are used" in {
    def test(call: String, warnings: AVector[String]) = {
      val code =
        s"""
           |Contract Foo(@unused bar: Bar) {
           |  pub fn f() -> () {
           |    $call
           |  }
           |  pub fn f1() -> U256 {
           |    return 1
           |  }
           |  pub fn f2() -> () {}
           |}
           |Contract Bar() {
           |  pub fn bar0() -> U256 {
           |    return 1
           |  }
           |  pub fn bar1() -> () {}
           |}
           |""".stripMargin
      Compiler.compileContractFull(code).rightValue.warnings is warnings
    }

    test("f2()", AVector.empty)
    test("bar.bar1()", AVector.empty)
    test("panic!()", AVector.empty)
    test("assert!(true, 0)", AVector.empty)
    test("let _ = f1()", AVector.empty)
    test("let _ = bar.bar0()", AVector.empty)
    test("let (_, _) = Bar.encodeFields!()", AVector.empty)
    test(
      "f1()",
      AVector(
        "The return values of the function \"Foo.f1\" are not used. If this is intentional, consider using anonymous variables to suppress this warning."
      )
    )
    test(
      "bar.bar0()",
      AVector(
        "The return values of the function \"Bar.bar0\" are not used. If this is intentional, consider using anonymous variables to suppress this warning."
      )
    )
    test(
      "Bar.encodeFields!()",
      AVector(
        "The return values of the function \"Bar.encodeFields\" are not used. If this is intentional, consider using anonymous variables to suppress this warning."
      )
    )
  }

  it should "compile struct" in {
    {
      info("Field does not exist in struct")
      val code =
        s"""
           |struct Foo { x: U256 }
           |
           |Contract C() {
           |  fn func(foo: Foo) -> U256 {
           |    return foo.$$y$$
           |  }
           |}
           |""".stripMargin

      testContractError(code, "Field y does not exist in struct Foo")
    }

    {
      info("Type does not exist")
      val code =
        s"""
           |Contract C(foo: $$Foo$$) {
           |  fn func() -> () {
           |  }
           |}
           |""".stripMargin

      testContractError(code, "Contract Foo does not exist")
    }

    {
      info("Duplicate struct definitions")
      val code =
        s"""
           |struct Foo { x: U256 }
           |$$struct Foo { y: ByteVec }$$
           |
           |Contract C() {
           |  fn func() -> () {}
           |}
           |""".stripMargin

      testContractError(
        code,
        "These TxScript/Contract/Interface/Struct are defined multiple times: Foo"
      )
    }

    {
      info("Assign to struct with invalid type")
      val code =
        s"""
           |struct Foo { mut x: U256 }
           |Contract C() {
           |  pub fn f() -> () {
           |    let mut foo = Foo { x: 1 }
           |    $$foo = 2$$
           |  }
           |}
           |""".stripMargin

      testContractError(code, "Cannot assign \"U256\" to \"Foo\"")
    }

    {
      info("Assign to struct field")
      def code(stmt: String) =
        s"""
           |struct Foo {
           |  x: U256,
           |  mut y: U256
           |}
           |Contract C() {
           |  pub fn f() -> () {
           |    let mut foo = Foo { x: 0, y: 1 }
           |    $stmt
           |  }
           |}
           |""".stripMargin
      testContractError(
        code(s"$$foo.x = 1$$"),
        "Cannot assign to immutable field x in struct Foo."
      )
      Compiler.compileContractFull(code("foo.y = 1")).isRight is true
    }

    {
      info("Assign to immutable nested struct field")
      def code(stmt: String, mut: String = "") =
        s"""
           |struct Bar { x: U256 }
           |struct Foo { $mut bar: Bar }
           |Contract C() {
           |  pub fn f() -> () {
           |    let mut foo = Foo { bar: Bar { x: 0 } }
           |    $stmt
           |  }
           |}
           |""".stripMargin
      testContractError(
        code(s"$$foo.bar.x = 1$$"),
        "Cannot assign to immutable field bar in struct Foo."
      )
      testContractError(
        code(s"$$foo.bar.x = 1$$", "mut"),
        "Cannot assign to immutable field x in struct Bar."
      )
      testContractError(
        code(s"$$foo.bar = Bar{x: 1}$$"),
        "Cannot assign to immutable field bar in struct Foo."
      )
      testContractError(
        code(s"$$foo = Foo{bar: Bar{x: 1}}$$"),
        "Cannot assign to variable foo. Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$foo = Foo{bar: Bar{x: 1}}$$", "mut"),
        "Cannot assign to variable foo. Assignment only works when all of the (nested) fields are mutable."
      )
    }

    {
      info("Assign to struct in array")
      def code(stmt: String, fieldMut: String, varMut: String) =
        s"""
           |struct Foo { $fieldMut x: U256 }
           |Contract C() {
           |  pub fn f() -> () {
           |    let $varMut foos = [[Foo { x : 1 }; 2]; 2]
           |    $stmt
           |  }
           |}
           |""".stripMargin
      testContractError(
        code(s"$$foos = [[Foo { x : 2 }; 2]; 2]$$", "", ""),
        "Cannot assign to immutable variable foos."
      )
      testContractError(
        code(s"$$foos = [[Foo { x : 2 }; 2]; 2]$$", "", "mut"),
        "Cannot assign to variable foos. Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$foos[0] = [Foo { x : 2 }; 2]$$", "", ""),
        "Cannot assign to immutable variable foos."
      )
      testContractError(
        code(s"$$foos[0] = [Foo { x : 2 }; 2]$$", "", "mut"),
        "Cannot assign to immutable element in array foos. Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$foos[0][0] = Foo { x : 2 }$$", "", ""),
        "Cannot assign to immutable variable foos."
      )
      testContractError(
        code(s"$$foos[0][0] = Foo { x : 2 }$$", "", "mut"),
        "Cannot assign to immutable element in array foos. Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$foos[0][0].x = 2$$", "", ""),
        "Cannot assign to immutable variable foos."
      )
      testContractError(
        code(s"$$foos[0][0].x = 2$$", "", "mut"),
        "Cannot assign to immutable field x in struct Foo."
      )
      Compiler
        .compileContractFull(code("foos = [[Foo { x : 2 }; 2]; 2]", "mut", "mut"))
        .isRight is true
      Compiler
        .compileContractFull(code("foos[0] = [Foo { x : 2 }; 2]", "mut", "mut"))
        .isRight is true
      Compiler.compileContractFull(code("foos[0][0] = Foo { x : 2 }", "mut", "mut")).isRight is true
      Compiler.compileContractFull(code("foos[0][0].x = 2", "mut", "mut")).isRight is true
    }

    {
      info("Assign to nested array in struct")
      def code(stmt: String, mut: String = "") =
        s"""
           |struct Bar { $mut x: U256 }
           |struct Foo { mut bars: [Bar; 2] }
           |Contract C() {
           |  pub fn f() -> () {
           |    let mut foo = Foo { bars: [Bar {x: 0}; 2] }
           |    $stmt
           |  }
           |}
           |""".stripMargin
      testContractError(
        code(s"$$foo = Foo{bars: [Bar{x: 1}; 2]}$$"),
        "Cannot assign to variable foo. Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$foo.bars = [Bar{x: 1}; 2]$$"),
        "Cannot assign to field bars in struct Foo. Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$foo.bars[0] = Bar{x: 1}$$"),
        "Cannot assign to immutable element in array Foo.bars. Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$foo.bars[0].x = 2$$"),
        "Cannot assign to immutable field x in struct Bar."
      )
      Compiler.compileContractFull(code("foo = Foo{bars: [Bar{x: 1}; 2]}", "mut")).isRight is true
      Compiler.compileContractFull(code("foo.bars = [Bar{x: 1}; 2]", "mut")).isRight is true
      Compiler.compileContractFull(code("foo.bars[0].x = 2", "mut")).isRight is true
    }

    {
      info("Create struct with invalid fields")
      val code =
        s"""
           |struct Foo { x: U256 }
           |
           |Contract C() {
           |  fn func() -> () {
           |    let foo = $$Foo {
           |      x: 1,
           |      y: 2
           |    }$$
           |  }
           |}
           |""".stripMargin

      testContractError(code, "Invalid struct fields, expect List(x:U256)")
    }

    {
      info("Compare struct")
      val code =
        s"""
           |struct Foo { x: U256 }
           |
           |Contract C() {
           |  pub fn f(foo0: Foo, foo1: Foo) -> Bool {
           |    return foo0 == foo1
           |  }
           |}
           |""".stripMargin

      Compiler.compileContractFull(code).leftValue.message is
        s"Invalid param types List(Foo, Foo) for == operator"
    }

    {
      info("Compare struct variable")
      val code =
        s"""
           |struct Foo { a: U256 }
           |Contract Bar() {
           |  pub fn f(foo0: Foo, foo1: Foo) -> () {
           |    assert!(foo0 == foo1, 0)
           |  }
           |}
           |""".stripMargin
      Compiler
        .compileContractFull(code)
        .leftValue
        .message is "Invalid param types List(Foo, Foo) for == operator"
    }

    {
      info("Circular references")
      val code0 =
        s"""
           |struct Foo {
           |  bar: Bar
           |}
           |struct Bar {
           |  baz: Baz
           |}
           |struct Baz {
           |  foo: $$Foo$$
           |}
           |Contract C(foo: Foo) {
           |  pub fn f() -> () {}
           |}
           |""".stripMargin
      testContractError(code0, "These structs \"List(Foo, Bar, Baz)\" have circular references")

      val code1 =
        s"""
           |struct Foo {x: $$Foo$$}
           |Contract C(foo: Foo) {
           |  pub fn f() -> () {}
           |}
           |""".stripMargin
      testContractError(code1, "These structs \"List(Foo)\" have circular references")
    }

    {
      info("Immutable struct field")
      def code(fields: String) =
        s"""
           |struct Baz { a: U256 }
           |struct Qux { mut a: U256 }
           |struct Foo { x: U256, $fields }
           |Contract Bar(mut foo: Foo) {
           |  pub fn f() -> U256 {
           |    return foo.x
           |  }
           |}
           |""".stripMargin

      Compiler.compileContractFull(code("y: U256")).rightValue.warnings is
        AVector("The struct Foo is immutable, you can remove the `mut` from Bar.foo")
      Compiler.compileContractFull(code("y: [U256; 2]")).rightValue.warnings is
        AVector("The struct Foo is immutable, you can remove the `mut` from Bar.foo")
      Compiler.compileContractFull(code("y: Baz")).rightValue.warnings is
        AVector("The struct Foo is immutable, you can remove the `mut` from Bar.foo")
      Compiler.compileContractFull(code("y: [Baz; 2]")).rightValue.warnings is
        AVector("The struct Foo is immutable, you can remove the `mut` from Bar.foo")
      Compiler.compileContractFull(code("mut y: Baz")).rightValue.warnings is
        AVector("The struct Foo is immutable, you can remove the `mut` from Bar.foo")
      Compiler.compileContractFull(code("mut y: [Baz; 2]")).rightValue.warnings is
        AVector("The struct Foo is immutable, you can remove the `mut` from Bar.foo")
      Compiler.compileContractFull(code("y: Qux")).rightValue.warnings is
        AVector("The struct Foo is immutable, you can remove the `mut` from Bar.foo")
      Compiler.compileContractFull(code("y: [Qux; 2]")).rightValue.warnings is
        AVector("The struct Foo is immutable, you can remove the `mut` from Bar.foo")
      Compiler.compileContractFull(code("mut y: U256")).rightValue.warnings.isEmpty is true
      Compiler.compileContractFull(code("mut y: Qux")).rightValue.warnings.isEmpty is true
      Compiler.compileContractFull(code("mut y: [Qux; 2]")).rightValue.warnings.isEmpty is true
    }

    {
      info("Variable does not exist")
      val code =
        s"""
           |struct Foo { x: U256 }
           |Contract Bar() {
           |  pub fn func() -> Foo {
           |    return Foo { $$x$$ }
           |  }
           |}
           |""".stripMargin
      testContractError(code, "Variable func.x does not exist or is used before declaration")
    }

    {
      info("Invalid variable type")
      val code =
        s"""
           |struct Foo { x: U256 }
           |Contract Bar() {
           |  pub fn func() -> Foo {
           |    let x = true
           |    return $$Foo { x }$$
           |  }
           |}
           |""".stripMargin
      testContractError(code, "Invalid struct fields, expect List(x:U256)")
    }

    {
      info("Invalid expr type in struct destruction")
      def code(expr: String) =
        s"""
           |struct Foo { x: U256, y: U256 }
           |struct Bar { a: U256 }
           |Contract Baz() {
           |  pub fn func() -> U256 {
           |    let Foo { a, b } = $$$expr$$
           |    return a + b
           |  }
           |}
           |""".stripMargin
      testContractError(code("0"), "Expected struct type \"Foo\", got \"U256\"")
      testContractError(code("Bar { a: 0 }"), "Expected struct type \"Foo\", got \"Bar\"")
      testContractError(
        code("[0; 2]"),
        "Expected struct type \"Foo\", got \"FixedSizeArray(U256,2)\""
      )
    }

    {
      info("Invalid struct field in struct destruction")
      def code(varDeclaration: String, stmt: String = "") =
        s"""
           |struct Foo { x: U256, y: U256 }
           |Contract Baz() {
           |  pub fn func() -> U256 {
           |    let Foo { $varDeclaration } = Foo { x: 0, y: 0 }
           |    $stmt
           |    return 0
           |  }
           |}
           |""".stripMargin
      testContractError(code(s"$$a$$: x1"), "Field a does not exist in struct Foo")
      testContractError(code(s"mut $$a$$: x1"), "Field a does not exist in struct Foo")
      Compiler.compileContract(code("x")).isRight is true
      Compiler.compileContract(code("mut x: x1", "x1 = 2")).isRight is true
    }

    {
      info("Variable already exists")
      def code(varDeclaration: String) =
        s"""
           |struct Foo { x: U256, y: U256 }
           |Contract Baz() {
           |  pub fn func() -> U256 {
           |    let x = 0
           |    let Foo { $varDeclaration } = Foo { x: 0, y: 0 }
           |    return x
           |  }
           |}
           |""".stripMargin
      testContractError(code(s"$$x$$"), "Local variables have the same name: x")
      Compiler.compileContract(code("x: x1")).isRight is true
    }

    {
      info("Assign to immutable variable")
      def code(mut: String = "") =
        s"""
           |struct Foo { x: U256, y: U256 }
           |Contract Bar() {
           |  pub fn func(foo: Foo) -> () {
           |    let Foo { $mut x } = foo
           |    $$x = 0$$
           |  }
           |}
           |""".stripMargin
      testContractError(code(), "Cannot assign to immutable variable x.")
      Compiler.compileContractFull(code("mut").replace("$", "")).isRight is true
    }
  }

  it should "load from array/struct literal" in new Fixture {
    val code =
      s"""
         |struct Baz { x: U256, y: U256 }
         |struct Foo { baz: Baz }
         |Contract Bar() {
         |  pub fn foo() -> () {
         |    assert!([[0, 1], [2, 3]][1][0] == 2, 0)
         |    assert!(([[0, 1], [2, 3]][1])[0] == 2, 0)
         |    assert!(Foo{baz: Baz{x: 0, y: 1}}.baz.y == 1, 0)
         |    assert!((Foo{baz: Baz{x: 0, y: 1}}.baz).y == 1, 0)
         |  }
         |}
         |""".stripMargin

    test(code)
  }

  it should "test struct" in new Fixture {
    {
      info("Struct as contract fields")
      val code =
        s"""
           |struct Foo {
           |  mut x: U256,
           |  mut y: ByteVec
           |}
           |Contract Bar(mut foo: Foo) {
           |  @using(checkExternalCaller = false)
           |  pub fn f() -> () {
           |    assert!(foo.x == 1, 0)
           |    assert!(foo.y == #0011, 0)
           |    foo.x = 2
           |    foo.y = #0022
           |    assert!(foo.x == 2, 0)
           |    assert!(foo.y == #0022, 0)
           |    foo = Foo { y: #0033, x: 3 }
           |    assert!(foo.x == 3, 0)
           |    assert!(foo.y == #0033, 0)
           |  }
           |}
           |""".stripMargin
      test(code, mutFields = AVector(Val.U256(1), Val.ByteVec(Hex.unsafe("0011"))))
    }

    {
      info("Struct as function parameters and return values")
      val code =
        s"""
           |struct Foo {
           |  mut x: U256,
           |  mut y: ByteVec
           |}
           |Contract Bar() {
           |  pub fn f0() -> () {
           |    let mut foo = f2()
           |    foo = Foo { x: 2, y: #0022 }
           |    f1(foo)
           |  }
           |
           |  fn f1(foo: Foo) -> () {
           |    assert!(foo.x == 2, 0)
           |    assert!(foo.y == #0022, 0)
           |  }
           |
           |  fn f2() -> Foo {
           |    return Foo {
           |      x: 1,
           |      y: #0011
           |    }
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("Struct as local variables")
      val code =
        s"""
           |struct Foo {
           |  mut x: U256,
           |  mut y: ByteVec
           |}
           |Contract Bar() {
           |  pub fn f() -> () {
           |    let foo0 = Foo {
           |      x: 1,
           |      y: #0011
           |    }
           |    let mut foo1 = foo0
           |    assert!(foo1.x == 1, 0)
           |    assert!(foo1.y == #0011, 0)
           |    foo1 = Foo { x: 2, y: #0022 }
           |    assert!(foo1.x == 2, 0)
           |    assert!(foo1.y == #0022, 0)
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("Load mutable and immutable struct fields correctly")
      val code =
        s"""
           |struct TokenBalance {
           |  tokenId: ByteVec,
           |  mut amount: U256
           |}
           |struct Balances {
           |  mut totalAmount: U256,
           |  mut tokens: [TokenBalance; 2]
           |}
           |Contract UserAccount(
           |  @unused id: ByteVec,
           |  @unused mut age: U256,
           |  @unused mut balances: Balances,
           |  @unused name: ByteVec
           |) {
           |  pub fn getId() -> ByteVec {
           |    return id
           |  }
           |  pub fn getAge() -> U256 {
           |    return age
           |  }
           |  pub fn getBalances() -> Balances {
           |    return balances
           |  }
           |  pub fn getName() -> ByteVec {
           |    return name
           |  }
           |}
           |""".stripMargin

      val immFields = AVector[Val](
        Val.ByteVec(Hex.unsafe("01")), // id
        Val.ByteVec(Hex.unsafe("02")), // tokenId0
        Val.ByteVec(Hex.unsafe("03")), // tokenId1
        Val.ByteVec(Hex.unsafe("04"))  // name
      )
      val mutFields = AVector[Val](
        Val.U256(10), // age
        Val.U256(20), // totalAmount
        Val.U256(30), // amount0
        Val.U256(40)  // amount1
      )
      test(code, AVector.empty, AVector(immFields(0)), immFields, mutFields, methodIndex = 0)
      test(code, AVector.empty, AVector(mutFields(0)), immFields, mutFields, methodIndex = 1)
      test(
        code,
        AVector.empty,
        AVector(
          mutFields(1),
          immFields(1),
          mutFields(2),
          immFields(2),
          mutFields(3)
        ),
        immFields,
        mutFields,
        methodIndex = 2
      )
      test(code, AVector.empty, AVector(immFields(3)), immFields, mutFields, methodIndex = 3)
    }

    {
      info("Read/write local and field struct vars")
      val code =
        s"""
           |struct Foo {
           |  a: U256,
           |  mut b: U256
           |}
           |Contract Baz(mut foos0: [Foo; 2], mut foos1: [Foo; 2]) {
           |  pub fn f0() -> () {
           |    let mut local = [Foo{a: 0, b: 1}; 2]
           |    for (let mut i = 0; i < 2; i = i + 1) {
           |      foos0[i].b = 1
           |      foos1[i].b = 1
           |    }
           |
           |    for (let mut j = 0; j < 2; j = j + 1) {
           |      assert!(local[j].a == 0, 0)
           |      assert!(local[j].b == 1, 0)
           |      assert!(foos0[j].a == 0, 0)
           |      assert!(foos0[j].b == 1, 0)
           |      assert!(foos1[j].a == 0, 0)
           |      assert!(foos1[j].b == 1, 0)
           |    }
           |
           |    local[0].b = 2
           |    local[1].b = 3
           |    assert!(local[1].b == 3 && local[0].b == 2, 0)
           |  }
           |}
           |""".stripMargin

      val immFields: AVector[Val] = AVector.fill(4)(Val.U256(0))
      val mutFields: AVector[Val] = AVector.fill(4)(Val.U256(0))
      test(code, immFields = immFields, mutFields = mutFields)
    }

    {
      info("Load/store array field by variable index")
      val code =
        s"""
           |struct Foo {
           |  mut a: U256,
           |  b: U256,
           |  mut c: U256
           |}
           |Contract Bar(mut foos: [[Foo; 3]; 2]) {
           |  pub fn f0(index: U256) -> [Foo; 3] {
           |    f1()
           |    for (let mut i = 0; i < 2; i = i + 1) {
           |      for (let mut j = 0; j < 3; j = j + 1)  {
           |        assert!(foos[i][j].a == i + j, 0)
           |        assert!(foos[i][j].c == i * j, 0)
           |      }
           |    }
           |    return foos[index]
           |  }
           |
           |  fn f1() -> () {
           |    for (let mut i = 0; i < 2; i = i + 1) {
           |      for (let mut j = 0; j < 3; j = j + 1)  {
           |        foos[i][j].a = i + j
           |        foos[i][j].c = i * j
           |      }
           |    }
           |  }
           |}
           |""".stripMargin
      val immFields: AVector[Val] = AVector.fill(6)(Val.U256(0))
      val mutFields: AVector[Val] = AVector.fill(12)(Val.U256(0))
      val result0: AVector[Val]   = AVector(0, 0, 0, 1, 0, 0, 2, 0, 0).map(v => Val.U256(v))
      test(code, AVector(Val.U256(0)), result0, immFields, mutFields)
      val result1: AVector[Val] = AVector(1, 0, 0, 2, 0, 1, 3, 0, 2).map(v => Val.U256(v))
      test(code, AVector(Val.U256(1)), result1, immFields, mutFields)
    }

    {
      info("Load/store struct field by variable index")
      val code =
        s"""
           |struct Foo {
           |  mut a: U256,
           |  b: U256,
           |  mut c: U256
           |}
           |struct Baz {
           |  x: U256,
           |  mut y: [Foo; 3]
           |}
           |Contract Bar(mut baz: Baz) {
           |  pub fn f0(index: U256) -> Foo {
           |    f1()
           |    for (let mut i = 0; i < 3; i = i + 1) {
           |      assert!(baz.y[i].a == i, 0)
           |      assert!(baz.y[i].c == i * 2, 0)
           |    }
           |    return baz.y[index]
           |  }
           |
           |  fn f1() -> () {
           |    for (let mut i = 0; i < 3; i = i + 1) {
           |      baz.y[i].a = i
           |      baz.y[i].c = i * 2
           |    }
           |  }
           |}
           |""".stripMargin
      val immFields: AVector[Val] = AVector.fill(4)(Val.U256(0))
      val mutFields: AVector[Val] = AVector.fill(6)(Val.U256(0))
      val result0: AVector[Val]   = AVector(0, 0, 0).map(v => Val.U256(v))
      test(code, AVector(Val.U256(0)), result0, immFields, mutFields)
      val result1: AVector[Val] = AVector(1, 0, 2).map(v => Val.U256(v))
      test(code, AVector(Val.U256(1)), result1, immFields, mutFields)
      val result2: AVector[Val] = AVector(2, 0, 4).map(v => Val.U256(v))
      test(code, AVector(Val.U256(2)), result2, immFields, mutFields)
    }

    {
      info("Load immutable struct field")
      val code =
        s"""
           |struct Foo {
           |  a: U256,
           |  mut b: U256,
           |  mut c: U256
           |}
           |struct Bar {
           |  x: U256,
           |  mut y: U256,
           |  foo0: [Foo; 2],
           |  mut foo1: [Foo; 2]
           |}
           |Contract Baz(mut a: U256, b: U256, bar: Bar) { // only `a` is mutable in contract `Baz`
           |  pub fn f0() -> () {
           |    assert!(a == 0, 0)
           |    a = 2
           |    assert!(a == 2, 0)
           |    assert!(b == 1, 0)
           |    assert!(bar.x == 2, 0)
           |    assert!(bar.y == 3, 0)
           |    assert!(bar.foo0[0].a == 4, 0)
           |    assert!(bar.foo0[0].b == 5, 0)
           |    assert!(bar.foo0[0].c == 6, 0)
           |    assert!(bar.foo0[1].a == 7, 0)
           |    assert!(bar.foo0[1].b == 8, 0)
           |    assert!(bar.foo0[1].c == 9, 0)
           |    assert!(bar.foo1[0].a == 10, 0)
           |    assert!(bar.foo1[0].b == 11, 0)
           |    assert!(bar.foo1[0].c == 12, 0)
           |    assert!(bar.foo1[1].a == 13, 0)
           |    assert!(bar.foo1[1].b == 14, 0)
           |    assert!(bar.foo1[1].c == 15, 0)
           |
           |    let mut number = 4
           |    for (let mut i = 0; i < 2; i = i + 1) {
           |      assert!(bar.foo0[i].a == number, 0)
           |      assert!(bar.foo0[i].b == number + 1, 0)
           |      assert!(bar.foo0[i].c == number + 2, 0)
           |      number = number + 3
           |    }
           |
           |    for (let mut j = 0; j < 2; j = j + 1) {
           |      assert!(bar.foo1[j].a == number, 0)
           |      assert!(bar.foo1[j].b == number + 1, 0)
           |      assert!(bar.foo1[j].c == number + 2, 0)
           |      number = number + 3
           |    }
           |
           |    assert!(number == 16, 0)
           |  }
           |}
           |""".stripMargin
      val allFields: AVector[Val] = AVector.from(0 until 16).map(v => Val.U256(v))
      test(code, immFields = allFields.tail, mutFields = AVector(allFields.head))
    }

    {
      info("Nested struct")
      val code =
        s"""
           |struct Foo {
           |  mut x: U256,
           |  mut y: ByteVec
           |}
           |struct Bar {
           |  a: Bool,
           |  mut b: [Foo; 2],
           |  mut c: Foo
           |}
           |Contract Baz(mut bar: Bar) {
           |  @using(checkExternalCaller = false)
           |  pub fn f() -> () {
           |    let mut bar0 = f1()
           |    assert!(!bar0.a, 0)
           |    assert!(bar0.b[0].x == 0 && bar0.b[0].y == #00, 0)
           |    assert!(bar0.b[1].x == 1 && bar0.b[1].y == #01, 0)
           |    assert!(bar0.c.x == 2 && bar0.c.y == #02, 0)
           |    bar0.b[0] = Foo { y: #02, x: 2 }
           |    assert!(bar0.b[0].x == 2 && bar0.b[0].y == #02, 0)
           |    assert!(bar0.b[1].x == 1 && bar0.b[1].y == #01, 0)
           |    assert!(bar0.c.x == 2 && bar0.c.y == #02, 0)
           |    bar0.b = [Foo { x: 3, y: #03 }; 2]
           |    assert!(bar0.b[0].x == 3 && bar0.b[0].y == #03, 0)
           |    assert!(bar0.b[1].x == 3 && bar0.b[1].y == #03, 0)
           |    assert!(bar0.c.x == 2 && bar0.c.y == #02, 0)
           |    bar0.c = Foo { y: #04, x: 4 }
           |    assert!(bar0.c.x == 4 && bar0.c.y == #04, 0)
           |
           |    assert!(bar.a == bar0.a, 0)
           |    bar.b = bar0.b
           |    bar.c = bar0.c
           |    assert!(bar.b[0].x == 3 && bar.b[0].y == #03, 0)
           |    assert!(bar.b[1].x == 3 && bar.b[1].y == #03, 0)
           |    assert!(bar.c.x == 4 && bar.c.y == #04, 0)
           |    bar.b[1] = Foo { y: #04, x: 4 }
           |    assert!(bar.b[1].x == 4 && bar.b[1].y == #04, 0)
           |    assert!(bar.b[0].x == 3 && bar.b[0].y == #03, 0)
           |    assert!(bar.c.x == 4 && bar.c.y == #04, 0)
           |    bar.b[0] = Foo { y: #05, x: 5 }
           |    assert!(bar.b[1].x == 4 && bar.b[1].y == #04, 0)
           |    assert!(bar.b[0].x == 5 && bar.b[0].y == #05, 0)
           |    assert!(bar.c.x == 4 && bar.c.y == #04, 0)
           |    bar.b = [Foo { x: 6, y: #06 }; 2]
           |    assert!(bar.b[1].x == 6 && bar.b[1].y == #06, 0)
           |    assert!(bar.b[0].x == 6 && bar.b[0].y == #06, 0)
           |    assert!(bar.c.x == 4 && bar.c.y == #04, 0)
           |    bar.c = Foo { x: 7, y: #07 }
           |    assert!(bar.b[1].x == 6 && bar.b[1].y == #06, 0)
           |    assert!(bar.b[0].x == 6 && bar.b[0].y == #06, 0)
           |    assert!(bar.c.x == 7 && bar.c.y == #07, 0)
           |
           |    f2(0, Foo { x: 8, y: #08 })
           |    assert!(bar.b[0].x == 8 && bar.b[0].y == #08, 0)
           |    f2(1, Foo { x: 9, y: #09 })
           |    assert!(bar.b[1].x == 9 && bar.b[1].y == #09, 0)
           |
           |    bar.b[0].x = 10
           |    bar.b[1].y = #10
           |    assert!(bar.b[0].x == 10 && bar.b[0].y == #08, 0)
           |    assert!(bar.b[1].x == 9 && bar.b[1].y == #10, 0)
           |  }
           |
           |  fn f1() -> Bar {
           |    return Bar {
           |      a: false,
           |      b: [Foo { x: 0, y: #00 }, Foo { x: 1, y: #01 }],
           |      c: Foo { x: 2, y: #02 }
           |    }
           |  }
           |
           |  fn f2(i: U256, foo: Foo) -> () {
           |    bar.b[i] = foo
           |  }
           |}
           |""".stripMargin

      test(
        code,
        immFields = AVector(Val.False),
        mutFields = AVector(
          Val.U256(0),
          Val.ByteVec(Hex.unsafe("00")),
          Val.U256(0),
          Val.ByteVec(Hex.unsafe("00")),
          Val.U256(0),
          Val.ByteVec(Hex.unsafe("00"))
        )
      )
    }

    {
      info("Create a struct using variables as fields")
      val code =
        s"""
           |struct Foo { x: U256, y: ByteVec }
           |struct Bar { a: Bool, b: U256, foo: Foo }
           |Contract Baz() {
           |  pub fn f() -> () {
           |    let x = 0
           |    let y = #00
           |    let foo = Foo { x, y }
           |    let a = true
           |    let bar = Bar { a, b: 1, foo }
           |    assert!(bar.a, 0)
           |    assert!(bar.b == 1, 0)
           |    assert!(bar.foo.x == 0, 0)
           |    assert!(bar.foo.y == #00, 0)
           |  }
           |}
           |""".stripMargin
      test(code)
    }

    {
      info("Struct destruction")
      val code =
        s"""
           |struct Foo { mut x: U256, y: U256 }
           |struct Bar { a: U256, b: [U256; 2], foo: Foo }
           |Contract Baz() {
           |  pub fn func() -> () {
           |    let fooInstance = Foo { x: 0, y: 1 }
           |    let Foo { x, y } = fooInstance
           |    assert!(x == 0 && y == 1, 0)
           |
           |    let Foo { x: x0, y: y0 } = fooInstance
           |    assert!(x0 == 0 && y0 == 1, 0)
           |
           |    let Foo { mut x: x1, mut y: y1 } = fooInstance
           |    assert!(x1 == 0 && y1 == 1, 0)
           |    x1 = 1
           |    y1 = 2
           |    assert!(x1 == 1 && y1 == 2, 0)
           |
           |    let Foo { x: x2, y: y2 } = Foo { y: 2, x: 1 }
           |    assert!(x2 == 1 && y2 == 2, 0)
           |
           |    let bar = Bar { a: 0, b: [1, 2], foo: Foo { x: 3, y: 4 } }
           |    let Bar { a, b, foo } = bar
           |    assert!(a == 0 && b[0] == 1 && b[1] == 2 && foo.x == 3 && foo.y == 4, 0)
           |    let Bar { a: a0, b: b0, foo: foo0 } = bar
           |    assert!(a0 == 0 && b0[0] == 1 && b0[1] == 2 && foo0.x == 3 && foo0.y == 4, 0)
           |    let Bar { b: b1, foo: foo1 } = bar
           |    assert!(b1[0] == 1 && b1[1] == 2 && foo1.x == 3 && foo1.y == 4, 0)
           |    let Bar { foo: foo2 } = bar
           |    assert!(foo2.x == 3 && foo2.y == 4, 0)
           |    let Bar { a: a3, foo: foo3 } = bar
           |    assert!(a3 == 0 && foo3.x == 3 && foo3.y == 4, 0)
           |    let Bar { a: a4, mut b: b4, mut foo: foo4 } = bar
           |    assert!(a4 == 0 && b4[0] == 1 && b4[1] == 2 && foo4.x == 3 && foo4.y == 4, 0)
           |    b4 = [5, 6]
           |    foo4.x = 7
           |    assert!(a4 == 0 && b4[0] == 5 && b4[1] == 6 && foo4.x == 7 && foo4.y == 4, 0)
           |  }
           |}
           |""".stripMargin
      test(code)
    }
  }

  it should "compile map" in {
    {
      info("Invalid map type for Map.insert")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn f(address: Address) -> () {
           |    let map = 0
           |    $$map$$.insert!(address, 0, 0)
           |  }
           |}
           |""".stripMargin
      testContractError(code, "Expected map type, got U256")
    }

    {
      info("Invalid args for Map.insert")
      def code(args: String) =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map
           |  pub fn f(address: Address) -> () {
           |    $$map.insert!($args)$$
           |  }
           |  pub fn f1(address: Address) -> (Address, U256, U256) {
           |    return address, 0, 0
           |  }
           |}
           |""".stripMargin
      testContractError(
        code("address, 1, #00"),
        "Invalid args type List(Address, U256, ByteVec), expected List(Address, U256, U256)"
      )
      testContractError(
        code("address, #00, 1"),
        "Invalid args type List(Address, ByteVec, U256), expected List(Address, U256, U256)"
      )
      testContractError(
        code("1, 1, 1"),
        "Invalid args type List(U256, U256, U256), expected List(Address, U256, U256)"
      )
      testContractError(code("f1(address)"), "Invalid args length, expected 3, got 1")
      testContractError(code("address, 1, 1, 1"), "Invalid args length, expected 3, got 4")
    }

    {
      info("Invalid map type for Map.remove")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn f(address: Address) -> () {
           |    let map = 0
           |    $$map$$.remove!(address, 0)
           |  }
           |}
           |""".stripMargin
      testContractError(code, "Expected map type, got U256")
    }

    {
      info("Invalid args for Map.remove")
      def code(args: String) =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map
           |  pub fn f(address: Address) -> () {
           |    $$map.remove!($args)$$
           |  }
           |  pub fn f1(address: Address) -> (Address, U256) {
           |    return address, 0
           |  }
           |}
           |""".stripMargin
      testContractError(
        code("#00, 1"),
        "Invalid args type List(ByteVec, U256), expected List(Address, U256)"
      )
      testContractError(
        code("address, #00"),
        "Invalid args type List(Address, ByteVec), expected List(Address, U256)"
      )
      testContractError(code("f1(address)"), "Invalid args length, expected 2, got 1")
      testContractError(code("address, 1, 1"), "Invalid args length, expected 2, got 3")
    }

    {
      info("Invalid map key or value type")
      def code(stmt: String) =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map
           |  pub fn foo() -> () {
           |    $stmt
           |  }
           |}
           |""".stripMargin
      testContractError(
        code(s"let _ = map$$[#00]$$"),
        "Invalid map key type \"ByteVec\", expected \"U256\""
      )
      testContractError(
        code(s"map$$[#00]$$ = 1"),
        "Invalid map key type \"ByteVec\", expected \"U256\""
      )
      testContractError(code(s"$$map[0] = #00$$"), "Cannot assign \"ByteVec\" to \"U256\"")
    }

    {
      info("Invalid map value(struct) assignment")
      def code(stmt: String, mut0: String = "", mut1: String = "") =
        s"""
           |struct Foo { $mut0 a: U256 }
           |struct Bar {
           |  $mut1 x: U256,
           |  mut y: [Foo; 2]
           |}
           |Contract Baz() {
           |  mapping[U256, Bar] map
           |  pub fn f() -> () {
           |    $stmt
           |  }
           |}
           |""".stripMargin

      testContractError(
        code(s"$$map[0] = Bar{x: 0, y: [Foo{a: 0}; 2]}$$"),
        "Cannot assign to value in map \"map\". Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$map[0].x = 1$$"),
        "Cannot assign to immutable field x in struct Bar."
      )
      testContractError(
        code(s"$$map[0].y = [Foo{a: 0}; 2]$$"),
        "Cannot assign to field y in struct Bar. Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$map[0].y[0] = Foo{a: 0}$$"),
        "Cannot assign to immutable element in array Bar.y. Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$map[0].y[0].a = 1$$"),
        "Cannot assign to immutable field a in struct Foo."
      )
      Compiler.compileContractFull(code("map[0].x = 1", "", "mut")).isRight is true
      Compiler.compileContractFull(code("map[0].y[0] = Foo{a: 0}", "mut")).isRight is true
      Compiler.compileContractFull(code("map[0].y[0].a = 1", "mut")).isRight is true
      Compiler.compileContractFull(code("map[0].y = [Foo{a: 0}; 2]", "mut")).isRight is true
      Compiler
        .compileContractFull(code("map[0] = Bar{x: 0, y: [Foo{a: 0}; 2]}", "mut", "mut"))
        .isRight is true
    }

    {
      info("Invalid map value(array) assignment")
      def code(stmt: String, mut0: String = "", mut1: String = "") =
        s"""
           |struct Foo { $mut0 a: U256 }
           |struct Bar {
           |  $mut1 x: U256,
           |  mut y: [Foo; 2]
           |}
           |Contract Baz() {
           |  mapping[U256, [Bar; 2]] map
           |  pub fn f() -> () {
           |    $stmt
           |  }
           |}
           |""".stripMargin

      testContractError(
        code(s"$$map[0] = [Bar{x: 0, y: [Foo{a: 0}; 2]}; 2]$$"),
        "Cannot assign to value in map \"map\". Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$map[0][0].x = 1$$"),
        "Cannot assign to immutable field x in struct Bar."
      )
      testContractError(
        code(s"$$map[0][0].y = [Foo{a: 0}; 2]$$"),
        "Cannot assign to field y in struct Bar. Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$map[0][0].y[0] = Foo{a: 0}$$"),
        "Cannot assign to immutable element in array Bar.y. Assignment only works when all of the (nested) fields are mutable."
      )
      testContractError(
        code(s"$$map[0][0].y[0].a = 1$$"),
        "Cannot assign to immutable field a in struct Foo."
      )
      Compiler.compileContractFull(code("map[0][0].x = 1", "", "mut")).isRight is true
      Compiler.compileContractFull(code("map[0][0].y[0] = Foo{a: 0}", "mut")).isRight is true
      Compiler.compileContractFull(code("map[0][0].y[0].a = 1", "mut")).isRight is true
      Compiler.compileContractFull(code("map[0][0].y = [Foo{a: 0}; 2]", "mut")).isRight is true
      Compiler
        .compileContractFull(code("map[0][0] = Bar{x: 0, y: [Foo{a: 0}; 2]}", "mut", "mut"))
        .isRight is true
      Compiler
        .compileContractFull(code("map[0] = [Bar{x: 0, y: [Foo{a: 0}; 2]}; 2]", "mut", "mut"))
        .isRight is true
    }

    {
      info("Invalid map type for Map.contains")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn f() -> () {
           |    let map = 0
           |    let _ = $$map$$.contains!(0)
           |  }
           |}
           |""".stripMargin
      testContractError(code, "Expected map type, got U256")
    }

    {
      info("Invalid key type for Map.contains")
      val code =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map
           |  pub fn f() -> () {
           |    let _ = $$map.contains!(#00)$$
           |  }
           |}
           |""".stripMargin
      testContractError(code, "Invalid args type List(ByteVec), expected List(U256)")
    }

    {
      info("Assign to map variable")
      val code =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map0
           |  mapping[U256, U256] map1
           |  pub fn f() -> () {
           |    $$map0 = map1$$
           |  }
           |}
           |""".stripMargin
      testContractError(code, "Cannot assign to map variable map0.")
    }

    {
      info("Cannot define local map variables")
      val code =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map0
           |  mapping[U256, ByteVec] map1
           |  pub fn f() -> () {
           |    let mut $$localMap0$$ = map0
           |  }
           |}
           |""".stripMargin
      testContractError(code, "Cannot define local map variable localMap0")
    }

    {
      info("The number of struct fields exceeds the maximum limit")
      def code(size: Int, mut: String) =
        s"""
           |struct Foo { $mut a: [U256; $size] }
           |Contract Bar() {
           |  mapping[U256, Foo] map
           |  pub fn bar(address: Address) -> () {
           |    map.insert!(address, 1, $$Foo { a: [0; $size] }$$)
           |  }
           |}
           |""".stripMargin

      testContractError(code(256, "mut"), "The number of struct fields exceeds the maximum limit")
      testContractError(code(256, ""), "The number of struct fields exceeds the maximum limit")
      // we have an extra immutable field `parentContractId`
      testContractError(code(255, ""), "The number of struct fields exceeds the maximum limit")
      Compiler.compileContractFull(code(255, "mut").replace("$", "")).isRight is true
      Compiler.compileContractFull(code(254, "").replace("$", "")).isRight is true
    }

    {
      info("Use a variable to store path if the load/store method is called multiple times")
      val code =
        s"""
           |Contract Foo() {
           |  mapping[U256, [U256; 2]] map
           |  pub fn read() -> [U256; 2] {
           |    return map[0]
           |  }
           |  pub fn write() -> () {
           |    map[0] = [0; 2]
           |  }
           |}
           |""".stripMargin

      val methods = Compiler.compileContractFull(code).rightValue.code.methods
      methods(0).argsLength is 0
      methods(0).localsLength is 1
      methods(1).argsLength is 0
      methods(1).localsLength is 1
    }

    {
      info("Generate codes for path if the load/store method is called only once")
      val code =
        s"""
           |Contract Foo() {
           |  mapping[U256, [U256; 2]] map
           |  pub fn read() -> U256 {
           |    return map[0][0]
           |  }
           |  pub fn write() -> () {
           |    map[0][0] = 0
           |  }
           |}
           |""".stripMargin

      val methods = Compiler.compileContractFull(code).rightValue.code.methods
      methods(0).argsLength is 0
      methods(0).localsLength is 0
      methods(1).argsLength is 0
      methods(1).localsLength is 0
    }

    {
      info("Duplicated map definitions")
      val code0 =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map
           |  $$mapping[U256, U256] map$$
           |  pub fn foo() -> () {}
           |}
           |""".stripMargin
      testContractError(code0, "These maps are defined multiple times: map")

      val code1 =
        s"""
           |Contract Foo() extends Bar() {
           |  $$mapping[U256, U256] map$$
           |  pub fn foo() -> () {}
           |}
           |Abstract Contract Bar() {
           |  mapping[U256, U256] map
           |  pub fn bar() -> () {}
           |}
           |""".stripMargin
      testContractError(code1, "These maps are defined multiple times: map")
    }

    {
      info("Unused maps")
      val code =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map
           |  pub fn foo() -> () {}
           |}
           |""".stripMargin
      Compiler.compileContractFull(code).rightValue.warnings is AVector(
        "Found unused maps in Foo: map"
      )
    }

    {
      info("Check external caller for map update")
      def code(statement: String, annotation: String = "") =
        s"""
           |Contract Foo(@unused address: Address) {
           |  mapping[U256, U256] map
           |  $annotation
           |  pub fn foo() -> () {
           |    $statement
           |  }
           |}
           |""".stripMargin

      val warnings = AVector(Warnings.noCheckExternalCallerMsg("Foo", "foo"))
      val updateStatements =
        Seq("map.insert!(address, 0, 0)", "map.remove!(address, 0)", "map[0] = 0")
      updateStatements.foreach { statement =>
        Compiler.compileContractFull(code(statement)).rightValue.warnings is warnings
        Compiler
          .compileContractFull(code(statement, "@using(checkExternalCaller = false)"))
          .rightValue
          .warnings is AVector.empty[String]
      }
      Compiler.compileContractFull(code("let _ = map[0]")).rightValue.warnings is
        AVector.empty[String]
      Compiler.compileContractFull(code("let _ = map.contains!(0)")).rightValue.warnings is
        AVector.empty[String]
    }

    {
      info("Map cannot have the same name as the contract field")
      val code =
        s"""
           |Contract Foo(@unused counters: [U256; 2]) {
           |  mapping[U256, U256] $$counters$$
           |  pub fn foo() -> () {}
           |}
           |""".stripMargin

      testContractError(code, "The map counters cannot have the same name as the contract field")
    }

    {
      info("Local variable has the same name as map variable")
      val code =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] counters
           |  pub fn foo() -> [U256; 2] {
           |    let $$counters$$ = [0; 2]
           |    return counters
           |  }
           |}
           |""".stripMargin
      testContractError(code, "Global variable has the same name as local variable: counters")
    }
  }

  it should "report friendly error for non-primitive types for consts" in new Fixture {
    {
      info("Array as constant")
      val code =
        s"""
           |Contract C() {
           |  const V = $$[0, 0]$$
           |  pub fn f() -> () {}
           |}
           |""".stripMargin
      testContractError(
        code,
        "Expected constant value with primitive types Bool/I256/U256/ByteVec/Address, arrays are not supported"
      )
    }

    {
      info("Struct as constant")
      val code =
        s"""
           |struct Foo { x: U256 }
           |Contract C() {
           |  const V = $$Foo {x: 0}$$
           |  pub fn f() -> () {}
           |}
           |""".stripMargin
      testContractError(
        code,
        "Expected constant value with primitive types Bool/I256/U256/ByteVec/Address, structs are not supported"
      )
    }

    {
      info("Contract instance as constant")
      val code =
        s"""
           |Contract Foo() { pub fn f() -> () {} }
           |Contract C(fooId: ByteVec) {
           |  const V = $$Foo(fooId)$$
           |  pub fn f() -> () {}
           |}
           |""".stripMargin
      testContractError(
        code,
        "Expected constant value with primitive types Bool/I256/U256/ByteVec/Address, contract instances are not supported"
      )
    }

    {
      info("Other expressions as constant")
      val code =
        s"""
           |Contract C() {
           |  const V = $$if (true) 2 else 3$$
           |  pub fn f() -> () {}
           |}
           |""".stripMargin

      testContractError(
        code,
        "Expected constant value with primitive types Bool/I256/U256/ByteVec/Address, other expressions are not supported"
      )
    }
  }

  it should "test constant expressions" in {
    def code(expr: String) =
      s"""
         |Contract Foo(b: U256) {
         |  const A = 1
         |  const B = 2
         |  const C = -1i
         |  const D = 2i
         |  const E = #00
         |  const F = false
         |  const G = $expr
         |  const H = @${Address.p2pkh(PublicKey.generate).toBase58}
         |
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin

    {
      info("invalid const expressions")
      testContractError(code(s"$$G$$"), "Variable G does not exist or is used before declaration")
      testContractError(code(s"$$H$$"), "Variable H does not exist or is used before declaration")
      testContractError(
        code(s"A + $$I$$"),
        "Variable I does not exist or is used before declaration"
      )
      testContractError(
        code(s"A + $$b$$"),
        "Constant variable b does not exist or is used before declaration"
      )
      testContractError(code(s"$$A + C$$"), "Invalid param types List(U256, I256) for + operator")
      testContractError(code(s"$$A - B$$"), "U256 overflow")
      testContractError(code(s"$$A - C$$"), "Invalid param types List(U256, I256) for - operator")
      testContractError(code(s"$$A * C$$"), "Invalid param types List(U256, I256) for * operator")
      testContractError(code(s"$$A / C$$"), "Invalid param types List(U256, I256) for / operator")
      testContractError(code(s"$$A % C$$"), "Invalid param types List(U256, I256) for % operator")
      testContractError(
        code(s"$$A |+| C$$"),
        "Invalid param types List(U256, I256) for |+| operator"
      )
      testContractError(
        code(s"$$A |-| C$$"),
        "Invalid param types List(U256, I256) for |-| operator"
      )
      testContractError(
        code(s"$$A |*| C$$"),
        "Invalid param types List(U256, I256) for |*| operator"
      )
      testContractError(
        code(s"$$A |**| C$$"),
        "Invalid param types List(U256, I256) for |**| operator"
      )
      testContractError(code(s"$$B + D$$"), "Invalid param types List(U256, I256) for + operator")
      testContractError(code(s"$$B - D$$"), "Invalid param types List(U256, I256) for - operator")
      testContractError(
        code(s"$$E ++ F$$"),
        "Invalid param types List(ByteVec, Bool) for ++ operator"
      )
      testContractError(
        code(s"$$B ** E$$"),
        "Invalid param types List(U256, ByteVec) for ** operator"
      )
      testContractError(
        code(s"$$E << 2$$"),
        "Invalid param types List(ByteVec, U256) for << operator"
      )
      testContractError(code(s"$$F >> 2$$"), "Invalid param types List(Bool, U256) for >> operator")
      testContractError(code(s"$$A ^ C$$"), "Invalid param types List(U256, I256) for ^ operator")
      testContractError(code(s"$$!E$$"), "Invalid param types List(ByteVec) for ! operator")
      testContractError(code(s"$$A == C$$"), "Invalid param types List(U256, I256) for == operator")
      testContractError(code(s"$$B != D$$"), "Invalid param types List(U256, I256) for != operator")
      testContractError(
        code(s"$$F && E$$"),
        "Invalid param types List(Bool, ByteVec) for && operator"
      )
      testContractError(
        code(s"$$F || E$$"),
        "Invalid param types List(Bool, ByteVec) for || operator"
      )
      testContractError(
        code(s"$$A <= E$$"),
        "Invalid param types List(U256, ByteVec) for <= operator"
      )
      testContractError(code(s"$$A < F$$"), "Invalid param types List(U256, Bool) for < operator")
      testContractError(code(s"$$C >= B$$"), "Invalid param types List(I256, U256) for >= operator")
      testContractError(code(s"$$C > B$$"), "Invalid param types List(I256, U256) for > operator")
    }

    {
      info("valid constant expressions")
      Compiler.compileContract(code("A + B")).isRight is true
      Compiler.compileContract(code("C + D")).isRight is true
      Compiler.compileContract(code("B - A")).isRight is true
      Compiler.compileContract(code("C - D")).isRight is true
      Compiler.compileContract(code("A * B")).isRight is true
      Compiler.compileContract(code("A / B")).isRight is true
      Compiler.compileContract(code("A % B")).isRight is true
      Compiler.compileContract(code(s"""b`hello` ++ E""")).isRight is true
      Compiler.compileContract(code("A ** B")).isRight is true
      Compiler.compileContract(code("A |+| B")).isRight is true
      Compiler.compileContract(code("A |-| B")).isRight is true
      Compiler.compileContract(code("A |*| B")).isRight is true
      Compiler.compileContract(code("A |**| B")).isRight is true
      Compiler.compileContract(code("A << 2")).isRight is true
      Compiler.compileContract(code("B << 2")).isRight is true
      Compiler.compileContract(code("A ^ B")).isRight is true
      Compiler.compileContract(code("!F")).isRight is true
      Compiler.compileContract(code("A == 2")).isRight is true
      Compiler.compileContract(code("A != B")).isRight is true
      Compiler.compileContract(code("(A == 2) && F")).isRight is true
      Compiler.compileContract(code("(A > 2) || F")).isRight is true
      Compiler.compileContract(code("A <= B")).isRight is true
      Compiler.compileContract(code("C < D")).isRight is true
      Compiler.compileContract(code("A >= B")).isRight is true
      Compiler.compileContract(code("C > D")).isRight is true
    }
  }

  it should "compile successfully when statements in contract body are not in strict order" in new Fixture {
    val statements = Seq(
      "mapping[U256, U256] map",
      "event E(v: U256)",
      "const V = 1",
      "enum FooErrorCodes { Error0 = 0 }",
      "pub fn f() -> () {}"
    )

    def verify(success: Boolean, indexes: Int*) = {
      val code =
        s"""
           |Contract C() {
           |  ${statements(indexes(0))}
           |  ${statements(indexes(1))}
           |  ${statements(indexes(2))}
           |  ${statements(indexes(3))}
           |  ${statements(indexes(4))}
           |}
           |""".stripMargin

      if (success) {
        Compiler.compileContract(code).rightValue
      } else {
        Compiler
          .compileContract(code)
          .leftValue
          .message is "Contract statements should be in the order of `maps`, `events`, `consts`, `enums` and `methods`"
      }
    }

    verify(success = true, 0, 1, 2, 3, 4)

    (Seq(0, 1, 2, 3, 4).permutations.toSet - Seq(0, 1, 2, 3, 4)).foreach { permutation =>
      verify(success = false, permutation: _*)
    }
  }

  it should "rearrange funcs based on predefined method index" in {
    def checkContractFuncs(code: String, funcs: Seq[String]) = {
      val result   = Compiler.compileMultiContract(code).rightValue
      val contract = result.contracts.head
      contract.funcs.map(_.name) is funcs
    }

    {
      val code =
        s"""
           |Contract Bar() implements Foo {
           |  pub fn f0() -> () {}
           |  pub fn f1() -> () {}
           |}
           |Interface Foo {
           |  pub fn f0() -> ()
           |  pub fn f1() -> ()
           |}
           |""".stripMargin
      checkContractFuncs(code, Seq("f0", "f1"))
    }

    {
      val code =
        s"""
           |Contract Bar() implements Foo {
           |  pub fn f0() -> () {}
           |  pub fn f1() -> () {}
           |}
           |Interface Foo {
           |  @using(methodIndex = 1)
           |  pub fn f0() -> ()
           |  @using(methodIndex = 0)
           |  pub fn f1() -> ()
           |}
           |""".stripMargin
      checkContractFuncs(code, Seq("f1", "f0"))
    }

    {
      val code =
        s"""
           |Contract Bar() implements Foo {
           |  pub fn f0() -> () {}
           |  pub fn f1() -> () {}
           |  pub fn f2() -> () {}
           |}
           |Interface Foo {
           |  @using(methodIndex = 2)
           |  pub fn f0() -> ()
           |  pub fn f1() -> ()
           |}
           |""".stripMargin
      checkContractFuncs(code, Seq("f1", "f2", "f0"))
    }

    {
      val code =
        s"""
           |Contract Bar() implements Foo {
           |  pub fn f4() -> () {}
           |  pub fn f0() -> () {}
           |  pub fn f1() -> () {}
           |  pub fn f2() -> () {}
           |  pub fn f3() -> () {}
           |}
           |Interface Foo {
           |  pub fn f0() -> ()
           |  pub fn f1() -> ()
           |  @using(methodIndex = 4)
           |  pub fn f2() -> ()
           |  @using(methodIndex = 0)
           |  pub fn f3() -> ()
           |}
           |""".stripMargin
      checkContractFuncs(code, Seq("f3", "f0", "f1", "f4", "f2"))
    }

    {
      def code(index: Int) =
        s"""
           |Contract Impl() implements Baz {
           |  pub fn f0() -> () {}
           |  pub fn f1() -> () {}
           |  pub fn f2() -> () {}
           |  pub fn f3() -> () {}
           |  pub fn f4() -> () {}
           |  pub fn f5() -> () {}
           |}
           |Interface Foo {
           |  @using(methodIndex = 1)
           |  pub fn f0() -> ()
           |  @using(methodIndex = 2)
           |  pub fn f1() -> ()
           |}
           |Interface Bar extends Foo {
           |  pub fn f2() -> ()
           |}
           |Interface Baz extends Bar {
           |  @using(methodIndex = $index)
           |  pub fn f3() -> ()
           |}
           |""".stripMargin
      checkContractFuncs(code(3), Seq("f2", "f0", "f1", "f3", "f4", "f5"))
      checkContractFuncs(code(4), Seq("f2", "f0", "f1", "f4", "f3", "f5"))
      checkContractFuncs(code(5), Seq("f2", "f0", "f1", "f4", "f5", "f3"))
    }

    {
      val code =
        s"""
           |Contract Impl() implements Bar {
           |  pub fn f0() -> () {}
           |  pub fn f1() -> () {}
           |  pub fn f2() -> () {}
           |}
           |Interface Foo {
           |  @using(methodIndex = 2)
           |  pub fn f0() -> ()
           |  pub fn f1() -> ()
           |}
           |Interface Bar extends Foo {
           |  @using(methodIndex = 1)
           |  pub fn f2() -> ()
           |}
           |""".stripMargin
      checkContractFuncs(code, Seq("f1", "f2", "f0"))
    }
  }

  it should "throw an error if the predefined method index is invalid" in {
    {
      def code(index: Int) =
        s"""
           |Contract Bar() implements Foo {
           |  pub fn f0() -> () {}
           |  pub fn f1() -> () {}
           |}
           |Interface Foo {
           |  @using(methodIndex = $index)
           |  pub fn f0() -> ()
           |  pub fn f1() -> ()
           |}
           |""".stripMargin

      Compiler.compileContract(code(3)).leftValue.message is
        "The method index of these functions is out of bound: f0, total number of methods: 2"
      Compiler.compileContract(code(4)).leftValue.message is
        "The method index of these functions is out of bound: f0, total number of methods: 2"
      Compiler.compileContract(replace(code(1))).isRight is true
      Compiler.compileContract(replace(code(0))).isRight is true
    }

    {
      def code(index0: Int = 0, index1: Int = 2): String =
        s"""
           |Contract Impl() implements Bar {
           |  pub fn f0() -> () {}
           |  pub fn f1() -> () {}
           |  pub fn f2() -> () {}
           |  pub fn f3() -> () {}
           |}
           |Interface Foo {
           |  @using(methodIndex = $index0)
           |  pub fn f0() -> ()
           |  pub fn f1() -> ()
           |}
           |Interface Bar extends Foo {
           |  @using(methodIndex = $index1)
           |  pub fn f2() -> ()
           |}
           |""".stripMargin

      Compiler.compileContract(code()).isRight is true
      Compiler.compileContract(code(index0 = 1)).isRight is true
      Compiler.compileContract(code(index0 = 2, index1 = 3)).isRight is true
      Compiler.compileContract(code(index0 = 3)).isRight is true
      Compiler.compileContract(code(index0 = 4)).leftValue.message is
        "The method index of these functions is out of bound: f0, total number of methods: 4"
      Compiler.compileContract(code(index1 = 0)).leftValue.message is
        "Function Bar.f2 have invalid predefined method index 0"
      Compiler.compileContract(code(index1 = 1)).leftValue.message is
        "Function Bar.f2 have invalid predefined method index 1"
      Compiler.compileContract(code(index1 = 3)).isRight is true
      Compiler.compileContract(code(index1 = 4)).leftValue.message is
        "The method index of these functions is out of bound: f2, total number of methods: 4"
    }

    {
      def code(index: Int) =
        s"""
           |Contract Impl() implements Bar {
           |  pub fn f0() -> () {}
           |  pub fn f1() -> () {}
           |  pub fn f2() -> () {}
           |}
           |Interface Foo {
           |  pub fn f0() -> ()
           |  pub fn f1() -> ()
           |}
           |Interface Bar extends Foo {
           |  @using(methodIndex = $index)
           |  pub fn f2() -> ()
           |}
           |""".stripMargin
      Compiler.compileContract(code(0)).leftValue.message is
        "Function Bar.f2 have invalid predefined method index 0"
      Compiler.compileContract(code(1)).leftValue.message is
        "Function Bar.f2 have invalid predefined method index 1"
      Compiler.compileContract(code(2)).isRight is true
    }

    {
      def code(index0: Int, index1: Int = 2) =
        s"""
           |Contract Impl() implements Bar {
           |  pub fn f0() -> () {}
           |  pub fn f1() -> () {}
           |  pub fn f2() -> () {}
           |  pub fn f3() -> () {}
           |}
           |Interface Foo {
           |  @using(methodIndex = $index1)
           |  pub fn f0() -> ()
           |  pub fn f1() -> ()
           |  pub fn f2() -> ()
           |}
           |Interface Bar extends Foo {
           |  @using(methodIndex = $index0)
           |  pub fn f3() -> ()
           |}
           |""".stripMargin

      Compiler.compileContract(code(0)).leftValue.message is
        "Function Bar.f3 have invalid predefined method index 0"
      Compiler.compileContract(code(1)).leftValue.message is
        "Function Bar.f3 have invalid predefined method index 1"
      Compiler.compileContract(code(2)).leftValue.message is
        "Function Bar.f3 have invalid predefined method index 2"
      Compiler.compileContract(code(3)).isRight is true
      Compiler.compileContract(code(2, 3)).isRight is true
    }
  }

  it should "throw an error if there are duplicate method indexes" in {
    val code0 =
      s"""
         |Contract Bar() implements Foo {
         |  pub fn f0() -> () {}
         |  pub fn f1() -> () {}
         |}
         |Interface Foo {
         |  @using(methodIndex = 0)
         |  pub fn f0() -> ()
         |  @using(methodIndex = 0)
         |  pub fn f1() -> ()
         |}
         |""".stripMargin
    Compiler.compileContract(code0).leftValue.message is
      s"Function Foo.f1 have invalid predefined method index 0"

    val code1 =
      s"""
         |Contract FooBar() implements Bar {
         |  pub fn foo() -> () {}
         |  pub fn bar() -> () {}
         |}
         |Interface Foo {
         |  @using(methodIndex = 1)
         |  pub fn foo() -> ()
         |}
         |Interface Bar extends Foo {
         |  @using(methodIndex = 1)
         |  pub fn bar() -> ()
         |}
         |""".stripMargin
    Compiler.compileContract(code1).leftValue.message is
      s"Function Bar.bar have invalid predefined method index 1"
  }

  it should "assign correct method index to interface functions" in {
    def createFunc(name: String, methodIndex: Option[Int] = None): Ast.FuncDef[StatefulContext] =
      Ast.FuncDef(
        Seq.empty,
        Ast.FuncId(name, false),
        isPublic = false,
        usePreapprovedAssets = false,
        Ast.NotUseContractAssets,
        usePayToContractOnly = false,
        useCheckExternalCaller = false,
        useUpdateFields = false,
        methodIndex,
        Seq.empty,
        Seq.empty,
        None
      )

    def checkFuncIndexes(funcs: Seq[Ast.FuncDef[StatefulContext]], indexes: Map[String, Byte]) = {
      val result = Compiler.SimpleFunc.from(funcs, true)
      result.foreach { func => func.index is indexes(func.name) }
    }

    val funcs0 = Seq(
      createFunc("f0"),
      createFunc("f1"),
      createFunc("f2")
    )
    checkFuncIndexes(funcs0, Map("f0" -> 0, "f1" -> 1, "f2" -> 2))

    val funcs1 = Seq(
      createFunc("f0"),
      createFunc("f1", Some(0)),
      createFunc("f2"),
      createFunc("f3", Some(1))
    )
    checkFuncIndexes(
      funcs1,
      Map("f1" -> 0, "f3" -> 1, "f0" -> 2, "f2" -> 3)
    )

    val funcs2 = Seq(
      createFunc("f0", Some(3)),
      createFunc("f1"),
      createFunc("f2", Some(1)),
      createFunc("f3")
    )
    checkFuncIndexes(
      funcs2,
      Map("f1" -> 0, "f2" -> 1, "f3" -> 2, "f0" -> 3)
    )

    val funcs3 = Seq(
      createFunc("f0", Some(1)),
      createFunc("f1"),
      createFunc("f2"),
      createFunc("f3", Some(5))
    )
    checkFuncIndexes(
      funcs3,
      Map("f1" -> 0, "f0" -> 1, "f2" -> 2, "f3" -> 5)
    )
  }

  it should "generate the right asset modifiers for functions" in {
    val code =
      s"""
         |Contract Foo() {
         |  @using(preapprovedAssets = false, assetsInContract = false, payToContractOnly = true)
         |  pub fn foo0() -> () {
         |    transferTokenToSelf!(callerAddress!(), ALPH, 1 alph)
         |  }
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn foo1() -> () {
         |    transferTokenToSelf!(callerAddress!(), ALPH, 1 alph)
         |  }
         |}
         |""".stripMargin
    val contract = Compiler.compileContract(code).rightValue
    val method0  = contract.methods(0)
    method0.usePreapprovedAssets is false
    method0.useContractAssets is false
    method0.usePayToContractOnly is true
    val method1 = contract.methods(1)
    method1.usePreapprovedAssets is true
    method1.useContractAssets is true
    method1.usePayToContractOnly is false
  }

  it should "check if the function pay to contract" in {
    def code(payToContractOnly: String = "false", stmt: String = "return"): String =
      s"""
         |Contract Foo() {
         |  $$@using(payToContractOnly = $payToContractOnly, checkExternalCaller = false)
         |  pub fn foo() -> () {
         |    $stmt
         |  }$$
         |}
         |""".stripMargin
    Compiler.compileContractFull(replace(code())).rightValue.warnings.isEmpty is true

    val statements = Seq(
      "transferTokenToSelf!(callerAddress!(), ALPH, 1 alph)",
      "transferTokenToSelf!(callerAddress!(), selfTokenId!(), 1 alph)",
      "transferToken!(callerAddress!(), selfAddress!(), ALPH, 1 alph)"
    )
    statements.foreach { stmt =>
      Compiler.compileContractFull(replace(code("true", stmt))).isRight is true
    }
    testContractError(
      code("true"),
      "Function \"Foo.foo\" does not pay to the contract, but the annotation `payToContractOnly` is enabled."
    )
    statements.dropRight(1).foreach { stmt =>
      testContractError(
        code("false", stmt),
        "Function \"Foo.foo\" transfers assets to the contract, please set either `assetsInContract` or `payToContractOnly` to true."
      )
    }
  }

  it should "generate method selector instr" in {
    def code(useMethodSelector: String = "false") =
      s"""
         |@using(methodSelector = $useMethodSelector)
         |Interface I {
         |  pub fn foo() -> ()
         |  pub fn bar() -> ()
         |}
         |Contract Foo() implements I {
         |  pub fn foo() -> () { return }
         |  pub fn bar() -> () { return }
         |}
         |""".stripMargin

    val compiled0 = Compiler.compileContractFull(code(), 1).rightValue.code
    compiled0.methods.foreach(_.instrs.head isnot a[MethodSelector])

    val compiled1 = Compiler.compileContractFull(code("true"), 1).rightValue
    val funcs     = compiled1.ast.funcs
    compiled1.code.methods(0).instrs.head is MethodSelector(funcs(0).methodSelector.get)
    compiled1.code.methods(1).instrs.head is MethodSelector(funcs(1).methodSelector.get)
  }

  "contract" should "use method selector by default" in {
    val code0 =
      s"""
         |Contract Foo() {
         |  pub fn func0() -> () { return }
         |  pub fn func1() -> () { return }
         |}
         |""".stripMargin

    val result0 = Compiler.compileContractFull(code0).rightValue
    result0.code.methods.foreach(_.instrs.head is a[MethodSelector])

    val code1 =
      s"""
         |Contract Foo() implements Bar {
         |  pub fn func0() -> () { return }
         |  pub fn func1() -> () { return }
         |}
         |@using(methodSelector = false)
         |Interface Bar {
         |  pub fn func0() -> ()
         |}
         |""".stripMargin
    val result1 = Compiler.compileContractFull(code1).rightValue
    result1.code.methods(0).instrs.head isnot a[MethodSelector]
    result1.code.methods(1).instrs.head is a[MethodSelector]

    val code2 =
      s"""
         |Contract Foo() implements Bar {
         |  pub fn func0() -> () { return }
         |  pub fn func1() -> () { return }
         |}
         |@using(methodSelector = true)
         |Interface Bar {
         |  pub fn func0() -> ()
         |}
         |""".stripMargin
    val result2 = Compiler.compileContractFull(code2).rightValue
    result2.code.methods.foreach(_.instrs.head is a[MethodSelector])

    val code3 =
      s"""
         |Contract Foo() implements Bar {
         |  pub fn func0() -> () { return }
         |  pub fn func1() -> () { return }
         |}
         |Interface Bar {
         |  pub fn func0() -> ()
         |}
         |""".stripMargin
    val result3 = Compiler.compileContractFull(code3).rightValue
    result3.code.methods(0).instrs.head isnot a[MethodSelector]
    result3.code.methods(1).instrs.head is a[MethodSelector]
  }

  it should "not use method selector for private functions" in {
    val code0 =
      s"""
         |Contract Foo() {
         |  pub fn func0() -> () {
         |    func1()
         |  }
         |  fn func1() -> () { return }
         |}
         |""".stripMargin
    val result0 = Compiler.compileContractFull(code0).rightValue
    result0.code.methods(0).instrs.head is a[MethodSelector]
    result0.code.methods(1).instrs.head isnot a[MethodSelector]

    val code1 =
      s"""
         |Contract Foo() implements IFoo {
         |  pub fn func0() -> () {
         |    func1()
         |  }
         |  fn func1() -> () { return }
         |}
         |@using(methodSelector = true)
         |Interface IFoo {
         |  pub fn func0() -> ()
         |  fn func1() -> ()
         |}
         |""".stripMargin
    val result1 = Compiler.compileContractFull(code1).rightValue
    result1.code.methods(0).instrs.head is a[MethodSelector]
    result1.code.methods(1).instrs.head isnot a[MethodSelector]
  }

  it should "call by method selector" in {
    val fooCode =
      s"""
         |@using(methodSelector = true)
         |Interface I {
         |  pub fn f0() -> U256
         |  pub fn f1() -> U256
         |}
         |Contract Foo() implements I {
         |  pub fn f0() -> U256 { return 0 }
         |  pub fn f1() -> U256 { return 1 }
         |}
         |""".stripMargin

    val compiledFoo     = Compiler.compileContractFull(fooCode, 1).rightValue
    val funcs           = compiledFoo.ast.funcs
    val globalState     = Ast.GlobalState(Seq.empty)
    val methodSelector0 = funcs(0).getMethodSelector(globalState)
    val methodSelector1 = funcs(1).getMethodSelector(globalState)
    compiledFoo.code.methods(0).instrs.head is MethodSelector(methodSelector0)
    compiledFoo.code.methods(1).instrs.head is MethodSelector(methodSelector1)
    val fooObj = prepareContract(compiledFoo.code, AVector.empty, AVector.empty)._1

    def bar(expr: String) = {
      val barCode =
        s"""
           |Contract Bar(fooId: ByteVec) {
           |  pub fn bar() -> () {
           |    let foo = $expr
           |    assert!(foo.f0() == 0, 0)
           |    assert!(foo.f1() == 1, 0)
           |  }
           |}
           |$fooCode
           |""".stripMargin

      Compiler.compileContractFull(barCode).rightValue.code
    }

    val instrs =
      Seq(CallExternalBySelector(methodSelector0), CallExternalBySelector(methodSelector1))
    val bar0 = bar("Foo(fooId)")
    bar0.methods.head.instrs.exists(instrs.contains) is false
    val (bar0Obj, context0) =
      prepareContract(bar0, AVector(Val.ByteVec(fooObj.contractId.bytes)), AVector.empty)
    StatefulVM.execute(context0, bar0Obj, AVector.empty).isRight is true

    val bar1 = bar("I(fooId)")
    bar1.methods.head.instrs.exists(instrs.contains) is true
    val (bar1Obj, context1) =
      prepareContract(bar1, AVector(Val.ByteVec(fooObj.contractId.bytes)), AVector.empty)
    StatefulVM.execute(context1, bar1Obj, AVector.empty).isRight is true
  }

  it should "test sort interfaces" in {
    def createInterface(name: String, useMethodSelector: Boolean): Ast.ContractInterface = {
      Ast.ContractInterface(
        None,
        useMethodSelector,
        Ast.TypeId(name),
        Seq.empty,
        Seq.empty,
        Seq.empty
      )
    }

    def test(
        parentsCache: mutable.Map[Ast.TypeId, Seq[Ast.ContractWithState]],
        interfaces: Seq[Ast.ContractInterface],
        result: Seq[Ast.ContractInterface]
    ) = {
      val newInterfaces = interfaces.map { i =>
        i.copy(inheritances = parentsCache(i.ident).map(p => Ast.InterfaceInheritance(p.ident)))
      }
      Ast.MultiContract
        .sortInterfaces(
          parentsCache,
          newInterfaces
        )
        .map(_.ident) is result.map(_.ident)
    }

    {
      info("sort interfaces by the method selector annotation")
      val foo = createInterface("Foo", true)
      val bar = createInterface("Bar", true)
      val baz = createInterface("Baz", false)
      test(
        mutable.Map(bar.ident -> Seq.empty, baz.ident -> Seq.empty, foo.ident -> Seq(bar, baz)),
        Seq(foo, bar, baz),
        Seq(baz, bar, foo)
      )
    }

    {
      info("sort interfaces by the parent length")
      val foo = createInterface("Foo", true)
      val bar = createInterface("Bar", true)
      val baz = createInterface("Baz", true)
      val qux = createInterface("Qux", true)
      test(
        mutable.Map(
          foo.ident -> Seq(bar, baz, qux),
          bar.ident -> Seq(baz, qux),
          baz.ident -> Seq(qux),
          qux.ident -> Seq.empty
        ),
        Seq(foo, bar, baz, qux),
        Seq(qux, baz, bar, foo)
      )
    }

    {
      info("sort interfaces by name")
      val foo = createInterface("Foo", true)
      val bar = createInterface("Bar", true)
      val baz = createInterface("Baz", true)
      test(
        mutable.Map(foo.ident -> Seq.empty, bar.ident -> Seq.empty, baz.ident -> Seq.empty),
        Seq(foo, bar, baz),
        Seq(bar, baz, foo)
      )
    }

    {
      info("sort interfaces by order")
      val foo  = createInterface("Foo", true)
      val bar  = createInterface("Bar", false)
      val baz  = createInterface("Baz", false)
      val qux  = createInterface("Qux", true)
      val quz  = createInterface("Quz", true)
      val fred = createInterface("Fred", true)
      test(
        mutable.Map(
          foo.ident  -> Seq(bar, baz, qux, quz, fred),
          bar.ident  -> Seq(baz),
          baz.ident  -> Seq.empty,
          qux.ident  -> Seq(quz),
          quz.ident  -> Seq.empty,
          fred.ident -> Seq.empty
        ),
        Seq(foo, bar, baz, qux, quz, fred),
        Seq(baz, bar, fred, quz, qux, foo)
      )
    }

    {
      info("interfaces are chained and not use method selector")
      val foo = createInterface("Foo", false)
      val bar = createInterface("Bar", false)
      val baz = createInterface("Baz", false)
      val qux = createInterface("Qux", false)
      test(
        mutable.Map(
          foo.ident -> Seq(bar, baz, qux),
          bar.ident -> Seq(baz, qux),
          baz.ident -> Seq(qux),
          qux.ident -> Seq.empty
        ),
        Seq(foo, bar, baz, qux),
        Seq(qux, baz, bar, foo)
      )
    }

    {
      info("throw an error if parents are not chained")
      val foo = createInterface("Foo", false)
      val bar = createInterface("Bar", false)
      val baz = createInterface("Baz", false)
      val qux = createInterface("Qux", false)
      intercept[Compiler.Error](
        test(
          mutable.Map(
            foo.ident -> Seq(bar, baz, qux),
            bar.ident -> Seq(qux),
            baz.ident -> Seq(qux),
            qux.ident -> Seq.empty
          ),
          Seq(foo, bar, baz, qux),
          Seq(qux, baz, bar, foo)
        )
      ).message is "Interface Baz does not inherit from Bar, please annotate Baz with @using(methodSelector = true) annotation"
    }

    {
      info("throw an error if the interface not use method selector but parents does")
      val code =
        s"""
           |Interface $$Foo$$ extends Bar {
           |  pub fn foo() -> ()
           |}
           |@using(methodSelector = true)
           |Interface Bar {
           |  pub fn bar() -> ()
           |}
           |""".stripMargin

      testMultiContractError(
        code,
        "Interface Foo does not use method selector, but its parent Bar use method selector"
      )
    }

    {
      info("interface use method selector inherit from an interface not use method selector")
      val code =
        s"""
           |Contract Baz(id: ByteVec) {
           |  pub fn func0() -> U256 {
           |    return Foo(id).func0()
           |  }
           |  pub fn func1() -> U256 {
           |    return Foo(id).func1()
           |  }
           |  pub fn func2() -> U256 {
           |    return Bar(id).func0()
           |  }
           |  pub fn func3() -> U256 {
           |    return Bar(id).func1()
           |  }
           |  pub fn func4() -> U256 {
           |    return Bar(id).func2()
           |  }
           |  pub fn func5() -> U256 {
           |    return Bar(id).func3()
           |  }
           |}
           |@using(methodSelector = false)
           |Interface Foo {
           |  pub fn func0() -> U256
           |  pub fn func1() -> U256
           |}
           |@using(methodSelector = true)
           |Interface Bar extends Foo {
           |  pub fn func2() -> U256
           |  pub fn func3() -> U256
           |}
           |""".stripMargin

      val compiled = Compiler.compileContractFull(code).rightValue.code
      compiled.methods
        .take(4)
        .foreach(_.instrs.exists(_.isInstanceOf[CallExternalBySelector]) is false)
      compiled.methods
        .drop(4)
        .foreach(_.instrs.exists(_.isInstanceOf[CallExternalBySelector]) is true)
    }
  }

  it should "not generate method selector instr for TxScript" in {
    val code =
      s"""
         |TxScript Main {
         |  foo()
         |
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin

    val compiled = Compiler.compileTxScriptFull(code).rightValue.code
    compiled.methods.foreach(_.instrs.head isnot a[MethodSelector])
  }

  "contract" should "support multiple inheritance" in {
    {
      info("inherit from both interfaces that use and not use method selector")
      val code: String =
        s"""
           |Contract Impl() implements Foo, Bar {
           |  pub fn bar() -> U256 { return 0 }
           |  pub fn foo() -> U256 { return 1 }
           |}
           |@using(methodSelector = false)
           |Interface Foo {
           |  pub fn foo() -> U256
           |}
           |@using(methodSelector = true)
           |Interface Bar {
           |  pub fn bar() -> U256
           |}
           |""".stripMargin
      val result = Compiler.compileContractFull(code).rightValue
      result.ast.funcs.map(_.name) is Seq("foo", "bar")
      val compiled = result.code
      compiled.methods(0).instrs.head isnot a[MethodSelector]
      compiled.methods(1).instrs.head is MethodSelector(result.ast.funcs(1).methodSelector.get)
    }

    {
      info("inherit from multiple interfaces")
      val code: String =
        s"""
           |Contract Impl() implements Foo, Bar {
           |  pub fn baz() -> U256 { return 0 }
           |  pub fn foo() -> U256 { return 1 }
           |  pub fn bar() -> U256 { return 2 }
           |}
           |@using(methodSelector = true)
           |Interface Foo {
           |  pub fn foo() -> U256
           |}
           |@using(methodSelector = true)
           |Interface Bar {
           |  pub fn bar() -> U256
           |}
           |""".stripMargin

      val compiled = Compiler.compileContractFull(code).rightValue
      compiled.ast.funcs.map(_.name) is Seq("bar", "foo", "baz")
      compiled.code.methods.take(2).foreach(_.instrs.head is a[MethodSelector])
      compiled.code.methods.last.instrs isnot a[MethodSelector]
    }

    {
      info("diamond shaped parent interfaces")
      val code: String =
        s"""
           |Contract Impl() extends FooBarContract() implements FooBaz {
           |  pub fn baz() -> U256 {
           |     return 2
           |  }
           |}
           |@using(methodSelector = true)
           |Interface Foo {
           |  pub fn foo() -> U256
           |}
           |@using(methodSelector = true)
           |Interface FooBar extends Foo {
           |  pub fn bar() -> U256
           |}
           |@using(methodSelector = true)
           |Interface FooBaz extends Foo {
           |  pub fn baz() -> U256
           |}
           |
           |Abstract Contract FooBarContract() implements FooBar {
           |   pub fn foo() -> U256 {
           |      return 0
           |   }
           |   pub fn bar() -> U256 {
           |      return 1
           |   }
           |}
           |""".stripMargin

      val compiled = Compiler.compileContractFull(code).rightValue
      compiled.ast.funcs.map(_.name) is Seq("foo", "bar", "baz")
      compiled.code.methods.foreach(_.instrs.head is a[MethodSelector])
    }

    {
      info("unrelated parent interfaces")
      val code =
        s"""
           |Contract Impl() extends FooBarContract() implements Foo {
           |  pub fn baz() -> (U256, U256) {
           |     return 1, 2
           |  }
           |}
           |@using(methodSelector = true)
           |Interface Foo {
           |  pub fn foo() -> U256
           |}
           |@using(methodSelector = true)
           |Interface Bar {
           |  pub fn bar() -> U256
           |}
           |
           |Abstract Contract FooBarContract() implements Bar {
           |   pub fn foo() -> U256 {
           |      return 0
           |   }
           |   pub fn bar() -> U256 {
           |      return 1
           |   }
           |}
           |""".stripMargin

      val compiled = Compiler.compileContractFull(code).rightValue
      compiled.ast.funcs.map(_.name) is Seq("bar", "foo", "baz")
      compiled.code.methods.take(2).foreach(_.instrs.head is a[MethodSelector])
      compiled.code.methods.last.instrs isnot a[MethodSelector]
    }
  }

  it should "throw an error if there are conflicting method selectors" in {
    val code =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {}
         |  pub fn $$bar() -> () {}
         |}
         |""".stripMargin

    Compiler.compileContract(replace(code)).isRight is true

    val multiContracts = Compiler.compileMultiContract(replace(code)).rightValue
    val foo            = multiContracts.contracts.head.asInstanceOf[Ast.Contract]
    foo.funcs(0).methodSelector = Some(foo.funcs(1).getMethodSelector(multiContracts.globalState))

    val state = Compiler.State.buildFor(multiContracts, 0)(CompilerOptions.Default)
    val error = intercept[Compiler.Error](foo.genMethods(state))
    error.message is "Function bar's method selector conflicts with function foo's method selector. Please use a new function name."
    error.position is code.indexOf("$")
  }
}
