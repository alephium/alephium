package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.Val
import org.alephium.util.AlephiumSpec

class ParserSpec extends AlephiumSpec {
  import Ast._

  it should "parse lexer" in {
    fastparse.parse("x", Lexer.ident(_)).get.value is Ast.Ident("x")
    fastparse.parse("U64", Lexer.tpe(_)).get.value is Val.U64
    fastparse.parse("x: U64", Parser.argument(_)).get.value is Ast.Argument(Ast.Ident("x"), Val.U64)
    fastparse
      .parse("def add(x: U64, y: U64): U64 { return x }\n", Parser.statement(_))
      .isSuccess is true
  }

  it should "parse exprs" in {
    fastparse.parse("x + y", Parser.expr(_)).get.value is
      Binop(Add, Variable(Ident("x"), None, None), Variable(Ident("y"), None, None))
    fastparse.parse("(x + y)", Parser.expr(_)).get.value is
      ParenExpr(Binop(Add, Variable(Ident("x"), None, None), Variable(Ident("y"), None, None)))
  }

  it should "parse statements" in {
    fastparse.parse("x = 1", Parser.statement(_)).isSuccess is true
    fastparse.parse("return x, y", Parser.statement(_)).isSuccess is true
    fastparse.parse("return x + y", Parser.statement(_)).isSuccess is true
    fastparse.parse("return (x + y)", Parser.statement(_)).isSuccess is true
    fastparse
      .parse("def add(x: U64, y: U64): U64 { return x + y }", Parser.statement(_))
      .isSuccess is true
  }

  it should "parse contracts" in {
    val contract =
      s"""
         |var x = 1u
         |var y = 2u
         |
         |def add(x: U64, y: U64): U64 {
         |  return (x + y)
         |}
         |
         |def add(): U64 {
         |  return (x + y)
         |}
         |
         |def add(d: U64) {
         |  var z = d
         |  x = x + z
         |  y = y + z
         |  return
         |}
         |""".stripMargin
    fastparse.parse(contract, Parser.contract(_)).isSuccess is true
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
         |def add(alf_amount: U64) -> (U64) {
         |  val token_amount = alf_amount * token_reserve / alf_reserve + 1
         |  val liquidity_mint = alf_amount * total_liquidity / alf_reserve
         |  alf_reserve += alf_amount
         |  token_reserve -= token_amount
         |  total_liquidity += liquidity_mint
         |  update(token_box)
         |  update(alf_box)
         |}
         |""".stripMargin
    contract.nonEmpty is true
  }
}
