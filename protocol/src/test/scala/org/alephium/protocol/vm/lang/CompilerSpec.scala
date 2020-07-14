package org.alephium.protocol.vm.lang

import org.scalatest.Assertion

import org.alephium.crypto.{Byte32, ED25519}
import org.alephium.protocol.ALF
import org.alephium.protocol.vm._
import org.alephium.serde._
import org.alephium.util._

// scalastyle:off no.equal
class CompilerSpec extends AlephiumSpec {
  import Ast._

  it should "parse lexer" in {
    val byte32 = Byte32.generate.toHexString.toUpperCase

    fastparse.parse("5", Lexer.typedNum(_)).get.value is Val.U64(U64.unsafe(5))
    fastparse.parse("-5i", Lexer.typedNum(_)).get.value is Val.I64(I64.from(-5))
    fastparse.parse("5U", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(5))
    fastparse.parse("-5I", Lexer.typedNum(_)).get.value is Val.I256(I256.from(-5))
    fastparse.parse(s"@$byte32", Lexer.byte32(_)).get.value is Val.Byte32(
      Byte32.unsafe(Hex.from(byte32).get))
    fastparse.parse("x", Lexer.ident(_)).get.value is Ast.Ident("x")
    fastparse.parse("U64", Lexer.typeId(_)).get.value is Ast.TypeId("U64")
    fastparse.parse("Foo", Lexer.typeId(_)).get.value is Ast.TypeId("Foo")
    fastparse.parse("x: U64", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U64, isMutable = false)
    fastparse.parse("mut x: U64", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U64, isMutable = true)
    fastparse.parse("// comment", Lexer.lineComment(_)).isSuccess is true
    fastparse.parse("add", Lexer.funcId(_)).get.value is Ast.FuncId("add", false)
    fastparse.parse("add!", Lexer.funcId(_)).get.value is Ast.FuncId("add", true)
  }

  it should "parse exprs" in {
    fastparse.parse("x + y", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](Add, Variable(Ident("x")), Variable(Ident("y")))
    fastparse.parse("x >= y", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](Ge, Variable(Ident("x")), Variable(Ident("y")))
    fastparse.parse("(x + y)", StatelessParser.expr(_)).get.value is
      ParenExpr[StatelessContext](Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))
    fastparse.parse("(x + y) + (x + y)", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](Add,
                              ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y")))),
                              ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y")))))
    fastparse.parse("x + y * z + u", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        Binop(Add, Variable(Ident("x")), Binop(Mul, Variable(Ident("y")), Variable(Ident("z")))),
        Variable(Ident("u")))
    fastparse.parse("x < y <= y < z", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        And,
        Binop(
          And,
          Binop(Lt, Variable(Ident("x")), Variable(Ident("y"))),
          Binop(Le, Variable(Ident("y")), Variable(Ident("y")))
        ),
        Binop(Lt, Variable(Ident("y")), Variable(Ident("z")))
      )
    fastparse.parse("x && y || z", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](Or,
                              Binop(And, Variable(Ident("x")), Variable(Ident("y"))),
                              Variable(Ident("z")))
    fastparse.parse("foo(x)", StatelessParser.expr(_)).get.value is
      CallExpr[StatelessContext](FuncId("foo", false), List(Variable(Ident("x"))))
    fastparse.parse("Foo(x)", StatelessParser.expr(_)).get.value is
      ContractConv[StatelessContext](Ast.TypeId("Foo"), Variable(Ident("x")))
    fastparse.parse("foo!(x)", StatelessParser.expr(_)).get.value is
      CallExpr[StatelessContext](FuncId("foo", true), List(Variable(Ident("x"))))
    fastparse.parse("foo(x + y) + bar!(x + y)", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        CallExpr(FuncId("foo", false),
                 List(Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))),
        CallExpr(FuncId("bar", true), List(Binop(Add, Variable(Ident("x")), Variable(Ident("y")))))
      )
  }

  it should "parse return" in {
    fastparse.parse("return x, y", StatelessParser.ret(_)).isSuccess is true
    fastparse.parse("return x + y", StatelessParser.ret(_)).isSuccess is true
    fastparse.parse("return (x + y)", StatelessParser.ret(_)).isSuccess is true
  }

  it should "parse statements" in {
    fastparse.parse("let x = 1", StatelessParser.statement(_)).isSuccess is true
    fastparse.parse("x = 1", StatelessParser.statement(_)).isSuccess is true
    fastparse.parse("x = true", StatelessParser.statement(_)).isSuccess is true
    fastparse.parse("add(x, y)", StatelessParser.statement(_)).isSuccess is true
    fastparse.parse("foo.add(x, y)", StatelessParser.statement(_)).isSuccess is true
    fastparse
      .parse("if x >= 1 { y = y + x } else { y = 0 }", StatelessParser.statement(_))
      .isSuccess is true
  }

  it should "parse functions" in {
    fastparse
      .parse("fn add(x: U64, y: U64) -> (U64, U64) { return x + y, x - y }",
             StatelessParser.func(_))
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
    Compiler.compileContract(contract).isRight is true
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
      Compiler.compileContract(contract).isRight is validity
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

  it should "parse multiple contracts" in {
    val input =
      s"""
         |contract Foo() {
         |  fn foo(bar: Bar) -> () {
         |    return bar.foo()
         |  }
         |  
         |  fn bar() -> () {
         |    return
         |  }
         |}
         |
         |contract Bar() {
         |  fn bar(foo: Foo) -> () {
         |    return foo.bar()
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

  trait Fixture {
    def test(input: String,
             args: AVector[Val],
             output: AVector[Val] = AVector.empty,
             fields: AVector[Val] = AVector.empty): Assertion = {
      val contract = Compiler.compileContract(input).toOption.get

      deserialize[StatefulContract](serialize(contract)) isE contract
      val context = StatefulContext(ALF.Hash.zero, WorldState.mock)
      val obj     = contract.toObject(ALF.Hash.zero, fields)
      StatefulVM.execute(context, obj, 0, args) isE output
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
    def input(hash: ALF.Hash) =
      s"""
         |AssetScript P2PKH() {
         |  fn verify(pk: Byte32) -> () {
         |    let hash = @${hash.toHexString}
         |    checkEq!(hash, keccak256!(pk))
         |    checkSignature!(pk)
         |    return
         |  }
         |}
         |""".stripMargin

    val (priKey, pubKey) = ED25519.generatePriPub()
    val pubKeyHash       = ALF.Hash.hash(pubKey.bytes)
    val signature        = ED25519.sign(ALF.Hash.zero.bytes, priKey)

    val script = Compiler.compileAssetScript(input(pubKeyHash)).toOption.get
    deserialize[StatelessScript](serialize(script)) isE script

    val args = AVector[Val](Val.Byte32(pubKey.toByte32))
    StatelessVM
      .runAssetScript(WorldState.mock, ALF.Hash.zero, script, args, signature)
      .isRight is true
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

  it should "test while" in new Fixture {
    test(
      s"""
         |contract While() {
         |  fn main() -> (U64) {
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
      AVector(Val.U64(U64.unsafe(35)))
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
         |    if n < 2i {
         |      return n
         |    } else {
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
         |    if n < 2 {
         |      return n
         |    } else {
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
         |    if n < 2I {
         |      return n
         |    } else {
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
         |    if n < 2U {
         |      return n
         |    } else {
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
         |    if n < 2 {
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
