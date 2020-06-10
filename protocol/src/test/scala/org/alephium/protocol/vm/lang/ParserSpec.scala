package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.{StatelessVM, Val}
import org.alephium.util.{AVector, AlephiumSpec, U64}

class ParserSpec extends AlephiumSpec {
  import Ast._

  it should "parse lexer" in {
    fastparse.parse("x", Lexer.ident(_)).get.value is Ast.Ident("x")
    fastparse.parse("U64", Lexer.tpe(_)).get.value is Val.U64
    fastparse.parse("x: U64", Parser.argument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Val.U64)
    fastparse
      .parse("fn add(x: U64, y: U64) -> (U64) { return x }\n", Parser.statement(_))
      .isSuccess is true
  }

  it should "parse exprs" in {
    fastparse.parse("x + y", Parser.expr(_)).get.value is
      Binop(Add, Variable(Ident("x")), Variable(Ident("y")))
    fastparse.parse("(x + y)", Parser.expr(_)).get.value is
      ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))
  }

  it should "parse return" in {
    fastparse.parse("return x, y", Parser.ret(_)).isSuccess is true
    fastparse.parse("return x + y", Parser.ret(_)).isSuccess is true
    fastparse.parse("return (x + y)", Parser.ret(_)).isSuccess is true
  }

  it should "parse statements" in {
    fastparse.parse("x = 1", Parser.statement(_)).isSuccess is true
    fastparse
      .parse("fn add(x: U64, y: U64) -> (U64, U64) { return x + y, x - y }", Parser.statement(_))
      .isSuccess is true
  }

  it should "parse contracts" in {
    val contract =
      s"""
         |var x = 1u
         |var y = 2u
         |let c = 100u
         |
         |fn add0(a: U64, b: U64) -> (U64) {
         |  return (a + b)
         |}
         |
         |fn add1() -> (U64) {
         |  return (x + y)
         |}
         |
         |fn add2(d: U64) -> () {
         |  var z = d
         |  x = x + z
         |  y = y + z
         |  return
         |}
         |""".stripMargin
    fastparse.parse(contract, Parser.contract(_)).isSuccess is true

    val ast = fastparse.parse(contract, Parser.contract(_)).get.value
    ast.check()
  }

  it should "infer types" in {
    def check(xMut: String,
              xVal: String,
              a: String,
              aType: String,
              b: String,
              bType: String,
              rType: String,
              fname: String,
              validity: Boolean = false) = {
      val contract = s"""
         |$xMut x = $xVal
         |fn add($a: $aType, $b: $bType) -> ($rType) {
         |  x = a + b
         |  return (a - b)
         |}
         |
         |fn $fname() -> () {
         |  return
         |}
         |""".stripMargin
      val ast      = fastparse.parse(contract, Parser.contract(_)).get.value
      if (validity) ast.check() else assertThrows[Checker.Error](ast.check())
    }

    check("var", "100u", "a", "U64", "b", "U64", "U64", "foo", true)
    check("let", "100u", "a", "U64", "b", "U64", "U64", "foo")
    check("var", "100i", "a", "U64", "b", "U64", "U64", "foo")
    check("var", "100u", "x", "U64", "b", "U64", "U64", "foo")
    check("var", "100u", "a", "U64", "x", "U64", "U64", "foo")
    check("var", "100u", "a", "I64", "b", "U64", "U64", "foo")
    check("var", "100u", "a", "U64", "b", "I64", "U64", "foo")
    check("var", "100u", "a", "U64", "b", "U64", "I64", "foo")
    check("var", "100u", "a", "U64", "b", "U64", "U64, U64", "foo")
    check("var", "100u", "a", "U64", "b", "U64", "U64", "add")
  }

  it should "parse UniSwap" in {
    val contract =
      s"""
         |var alf_reserve = 100u
         |var token_reserve = 100u
         |var total_liquidity = 100u
         |var token_box = U64._
         |var alf_box = U64._
         |
         |fn add(alf_amount: U64) -> (U64) {
         |  val token_amount = alf_amount * token_reserve / alf_reserve + 1
         |  val liquidity_mint = alf_amount * total_liquidity / alf_reserve
         |  alf_reserve += alf_amount
         |  token_reserve -= token_amount
         |  total_liquidity += liquidity_mint
         |  update(token_box)
         |  update(alf_box)
         |  return liquidity_mint
         |}
         |""".stripMargin
    contract.nonEmpty is true
  }

  it should "generate IR code" in {
    val input =
      s"""
         |var x = 0u
         |
         |fn add(a: U64) -> (U64) {
         |  return x + a
         |}
         |""".stripMargin
    val ast      = fastparse.parse(input, Parser.contract(_)).get.value
    val ctx      = ast.check()
    val contract = ast.toIR(ctx)
    StatelessVM.execute(contract, AVector(Val.U64(U64.One)), AVector(Val.U64(U64.Two))) isE Val.U64(
      U64.unsafe(3))
  }
}
