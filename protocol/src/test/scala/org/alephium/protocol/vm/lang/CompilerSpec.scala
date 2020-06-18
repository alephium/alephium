package org.alephium.protocol.vm.lang

import org.scalatest.Assertion

import org.alephium.crypto.ED25519
import org.alephium.protocol.{ALF, vm}
import org.alephium.protocol.vm.{StatelessContext, StatelessScript, StatelessVM, Val}
import org.alephium.serde._
import org.alephium.util.{AVector, AlephiumSpec, I256, I64, U256, U64}

class CompilerSpec extends AlephiumSpec {
  import Ast._

  it should "parse lexer" in {
    fastparse.parse("5", Lexer.typedNum(_)).get.value is Val.U64(U64.unsafe(5))
    fastparse.parse("-5i", Lexer.typedNum(_)).get.value is Val.I64(I64.from(-5))
    fastparse.parse("5U", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(5))
    fastparse.parse("-5I", Lexer.typedNum(_)).get.value is Val.I256(I256.from(-5))
    fastparse.parse("x", Lexer.ident(_)).get.value is Ast.Ident("x")
    fastparse.parse("U64", Lexer.tpe(_)).get.value is Val.U64
    fastparse.parse("x: U64", Parser.argument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Val.U64, isMutable = false)
    fastparse.parse("mut x: U64", Parser.argument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Val.U64, isMutable = true)
    fastparse.parse("// comment", Lexer.lineComment(_)).isSuccess is true
    fastparse.parse("add", Lexer.callId(_)).get.value is Ast.CallId("add", false)
    fastparse.parse("add!", Lexer.callId(_)).get.value is Ast.CallId("add", true)
  }

  it should "parse exprs" in {
    fastparse.parse("x + y", Parser.expr(_)).get.value is
      Binop(Add, Variable(Ident("x")), Variable(Ident("y")))
    fastparse.parse("x >= y", Parser.expr(_)).get.value is
      Binop(Ge, Variable(Ident("x")), Variable(Ident("y")))
    fastparse.parse("(x + y)", Parser.expr(_)).get.value is
      ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))
    fastparse.parse("(x + y) + (x + y)", Parser.expr(_)).get.value is
      Binop(Add,
            ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y")))),
            ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y")))))
    fastparse.parse("x + y * z + u", Parser.expr(_)).get.value is
      Binop(
        Add,
        Binop(Add, Variable(Ident("x")), Binop(Mul, Variable(Ident("y")), Variable(Ident("z")))),
        Variable(Ident("u")))
    fastparse.parse("x < y <= y < z", Parser.expr(_)).get.value is
      Binop(
        And,
        Binop(
          And,
          Binop(Lt, Variable(Ident("x")), Variable(Ident("y"))),
          Binop(Le, Variable(Ident("y")), Variable(Ident("y")))
        ),
        Binop(Lt, Variable(Ident("y")), Variable(Ident("z")))
      )
    fastparse.parse("x && y || z", Parser.expr(_)).get.value is
      Binop(Or, Binop(And, Variable(Ident("x")), Variable(Ident("y"))), Variable(Ident("z")))
    fastparse.parse("foo(x)", Parser.expr(_)).get.value is
      Call(CallId("foo", false), List(Variable(Ident("x"))))
    fastparse.parse("foo!(x)", Parser.expr(_)).get.value is
      Call(CallId("foo", true), List(Variable(Ident("x"))))
    fastparse.parse("foo(x + y) + bar!(x + y)", Parser.expr(_)).get.value is
      Binop(
        Add,
        Call(CallId("foo", false), List(Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))),
        Call(CallId("bar", true), List(Binop(Add, Variable(Ident("x")), Variable(Ident("y")))))
      )
  }

  it should "parse return" in {
    fastparse.parse("return x, y", Parser.ret(_)).isSuccess is true
    fastparse.parse("return x + y", Parser.ret(_)).isSuccess is true
    fastparse.parse("return (x + y)", Parser.ret(_)).isSuccess is true
  }

  it should "parse statements" in {
    fastparse.parse("let x = 1", Parser.statement(_)).isSuccess is true
    fastparse.parse("x = 1", Parser.statement(_)).isSuccess is true
    fastparse.parse("x = true", Parser.statement(_)).isSuccess is true
    fastparse.parse("add(x, y)", Parser.statement(_)).isSuccess is true
    fastparse
      .parse("if (x >= 1) { y = y + x } else { y = 0 }", Parser.statement(_))
      .isSuccess is true
  }

  it should "parse functions" in {
    fastparse
      .parse("fn add(x: U64, y: U64) -> (U64, U64) { return x + y, x - y }", Parser.func(_))
      .isSuccess is true
  }

  it should "parse contracts" in {
    val contract =
      s"""
         |// comment
         |contract Foo(mut x: U64, mut y: U64, c: U64) {
         |  // comment
         |  fn add0(a: U64, b: U64) -> (U64) {
         |    return (a + b)
         |  }
         |
         |  fn add1() -> (U64) {
         |    return (x + y)
         |  }
         |
         |  fn add2(d: U64) -> () {
         |    let mut z = 0u
         |    z = d
         |    x = x + z // comment
         |    y = y + z // comment
         |    return
         |  }
         |}
         |""".stripMargin
    fastparse.parse(contract, Parser.contract(_)).isSuccess is true

    val ast = fastparse.parse(contract, Parser.contract(_)).get.value
    ast.check()
  }

  it should "infer types" in {
    def check(xMut: String,
              a: String,
              aType: String,
              b: String,
              bType: String,
              rType: String,
              fname: String,
              validity: Boolean = false) = {
      val contract =
        s"""
         |contract Foo($xMut x: U64) {
         |  fn add($a: $aType, $b: $bType) -> ($rType) {
         |    x = a + b
         |    return (a - b)
         |  }
         |
         |  fn $fname() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
      val ast = fastparse.parse(contract, Parser.contract(_)).get.value
      if (validity) ast.check() else assertThrows[Compiler.Error](ast.check())
    }

    check("mut", "a", "U64", "b", "U64", "U64", "foo", true)
    check("", "a", "U64", "b", "U64", "U64", "foo")
    check("mut", "x", "U64", "b", "U64", "U64", "foo")
    check("mut", "a", "U64", "x", "U64", "U64", "foo")
    check("mut", "a", "I64", "b", "U64", "U64", "foo")
    check("mut", "a", "U64", "b", "I64", "U64", "foo")
    check("mut", "a", "U64", "b", "U64", "I64", "foo")
    check("mut", "a", "U64", "b", "U64", "U64, U64", "foo")
    check("mut", "a", "U64", "b", "U64", "U64", "add")
  }

  trait Fixture {
    def test(input: String,
             args: AVector[Val],
             output: AVector[Val] = AVector.empty,
             fields: AVector[Val] = AVector.empty): Assertion = {
      val ast      = fastparse.parse(input, Parser.contract(_)).get.value
      val ctx      = ast.check()
      val contract = ast.toIR(ctx)

      deserialize[StatelessScript](serialize(contract)) isE contract
      StatelessVM.execute(StatelessContext.test, contract, fields, args) isE output
    }
  }

  it should "generate IR code" in new Fixture {
    val input =
      s"""
         |contract Foo(x: U64) {
         |
         |  fn add(a: U64) -> (U64) {
         |    return square(x) + square(a)
         |  }
         |
         |  fn square(n: U64) -> (U64) {
         |    return n * n
         |  }
         |}
         |""".stripMargin

    test(input,
         AVector(Val.U64(U64.Two)),
         AVector(Val.U64(U64.unsafe(5))),
         AVector(Val.U64(U64.One)))
  }

  it should "verify signature" in {
    val input =
      s"""
         |contract P2PKH(hash: Byte32) {
         |  fn verify(pk: Byte32) -> () {
         |    checkEq!(hash, keccak256!(pk))
         |    checkSignature!(pk)
         |    return
         |  }
         |}
         |""".stripMargin
    val ast      = fastparse.parse(input, Parser.contract(_)).get.value
    val ctx      = ast.check()
    val contract = ast.toIR(ctx)

    deserialize[StatelessScript](serialize(contract)) isE contract

    val (priKey, pubKey) = ED25519.generatePriPub()
    val pubKeyHash       = ALF.Hash.hash(pubKey.bytes)
    val signature        = ED25519.sign(ALF.Hash.zero.bytes, priKey)
    StatelessVM.execute(
      StatelessContext(ALF.Hash.zero, vm.Stack.unsafe(AVector(signature), 1)),
      contract,
      AVector(Val.Byte32(pubKeyHash.toByte32)),
      AVector(Val.Byte32(pubKey.toByte32))
    ) isE AVector.empty[Val]
  }

  it should "converse values" in new Fixture {
    test(
      s"""
         |contract Conversion() {
         |  fn main() -> () {
         |    let mut x = 5u
         |    x = u64!(5i)
         |    x = u64!(5I)
         |    x = u64!(5U)
         |    return
         |  }
         |}
         |""".stripMargin,
      AVector.empty
    )
  }

  it should "test the following typical examples" in new Fixture {
    test(
      s"""
         |contract Main() {
         |
         |  fn main() -> () {
         |    let an_i64 = 5i // Suffix annotation
         |    let an_u64 = 5u
         |    let an_i256 = 5I
         |    let an_u256 = 5U
         |
         |    // Or a default will be used.
         |    let default_integer = 7   // `U64`
         |
         |    // A mutable variable's value can be changed.
         |    let mut another_i64 = 5i
         |    let mut another_u64 = 5u
         |    let mut another_i256 = 5I
         |    let mut another_u256 = 5U
         |    another_i64 = 6i
         |    another_u64 = 6u
         |    another_i256 = 6I
         |    another_u256 = 6U
         |
         |    let mut bool = true
         |    bool = false
         |
         |    return
         |  }
         |}
         |""".stripMargin,
      AVector.empty
    )

    test(
      s"""
         |contract Fibonacci() {
         |  fn f(n: I64) -> (I64) {
         |    if (n < 2i) {
         |      return n
         |    }
         |    else {
         |      return f(n-1i) + f(n-2i)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.I64(I64.from(10))),
      AVector[Val](Val.I64(I64.from(55)))
    )

    test(
      s"""
         |contract Fibonacci() {
         |  fn f(n: U64) -> (U64) {
         |    if (n < 2) {
         |      return n
         |    }
         |    else {
         |      return f(n-1) + f(n-2)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.U64(U64.unsafe(10))),
      AVector[Val](Val.U64(U64.unsafe(55)))
    )

    test(
      s"""
         |contract Fibonacci() {
         |  fn f(n: I256) -> (I256) {
         |    if (n < 2I) {
         |      return n
         |    }
         |    else {
         |      return f(n-1I) + f(n-2I)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.I256(I256.from(10))),
      AVector[Val](Val.I256(I256.from(55)))
    )

    test(
      s"""
         |contract Fibonacci() {
         |  fn f(n: U256) -> (U256) {
         |    if (n < 2U) {
         |      return n
         |    }
         |    else {
         |      return f(n-1U) + f(n-2U)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.U256(U256.unsafe(10))),
      AVector[Val](Val.U256(U256.unsafe(55)))
    )

    test(
      s"""
         |contract Test() {
         |  fn main() -> (Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool) {
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
      AVector[Val](Val.True,
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
                   Val.True)
    )

    test(
      s"""
         |contract Foo() {
         |  fn f(mut n: U64) -> (U64) {
         |    if (n < 2) {
         |      n = n + 1
         |    }
         |    return n
         |  }
         |}
         |""".stripMargin,
      AVector(Val.U64(U64.unsafe(2))),
      AVector[Val](Val.U64(U64.unsafe(2)))
    )
  }

  it should "execute quasi uniswap" in new Fixture {
    val contract =
      s"""
         |contract Uniswap(
         |  mut alfReserve: U64,
         |  mut btcReserve: U64
         |) {
         |  fn exchange(alfAmount: U64) -> (U64) {
         |    let tokenAmount = u64!(u256!(btcReserve) * u256!(alfAmount) / u256!(alfReserve + alfAmount))
         |    alfReserve = alfReserve + alfAmount
         |    btcReserve = btcReserve - tokenAmount
         |    return tokenAmount
         |  }
         |}
         |""".stripMargin

    test(
      contract,
      AVector(Val.U64(U64.unsafe(1000))),
      AVector(Val.U64(U64.unsafe(99))),
      AVector(Val.U64(U64.unsafe(1000000)), Val.U64(U64.unsafe(100000)))
    )

    test(
      contract,
      AVector(Val.U64(U64.unsafe(1000))),
      AVector(Val.U64(U64.unsafe(99))),
      AVector(Val.U64(U64.MaxValue divUnsafe U64.unsafe(10)),
              Val.U64(U64.MaxValue divUnsafe U64.unsafe(100)))
    )
  }

  it should "test operator precedence" in new Fixture {
    val contract =
      s"""
         |contract Operator() {
         |  fn main() -> (U64, Bool, Bool) {
         |    let x = 1 + 2 * 3 - 2 / 2
         |    let y = 1 < 2 <= 2 < 3
         |    let z = !false && false || false
         |
         |    return x, y, z
         |  }
         |}
         |""".stripMargin
    test(contract, AVector.empty, AVector(Val.U64(U64.unsafe(6)), Val.True, Val.False))
  }
}
