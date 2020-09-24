package org.alephium.protocol.vm.lang

import org.alephium.crypto.Byte32
import org.alephium.protocol.vm.{StatefulContext, StatelessContext, Val}
import org.alephium.util.{AlephiumSpec, Hex, I256, I64, U256, U64}

class ParserSpec extends AlephiumSpec {
  import Ast._

  it should "parse lexer" in {
    val byte32 = Byte32.generate.toHexString

    fastparse.parse("5", Lexer.typedNum(_)).get.value is Val.U64(U64.unsafe(5))
    fastparse.parse("-5i", Lexer.typedNum(_)).get.value is Val.I64(I64.from(-5))
    fastparse.parse("5U", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(5))
    fastparse.parse("-5I", Lexer.typedNum(_)).get.value is Val.I256(I256.from(-5))
    fastparse.parse(s"@$byte32", Lexer.bytes(_)).get.value is Val.ByteVec(
      Hex.asArraySeq(byte32).get)
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
    fastparse.parse("x.bar(x)", StatefulParser.contractCallExpr(_)).get.value is
      ContractCallExpr(
        Variable(Ident("x")),
        FuncId("bar", false),
        List(Variable(Ident("x")))
      )
    fastparse.parse("Foo(x).bar(x)", StatefulParser.contractCallExpr(_)).get.value is
      ContractCallExpr(
        ContractConv[StatefulContext](Ast.TypeId("Foo"), Variable(Ident("x"))),
        FuncId("bar", false),
        List(Variable(Ident("x")))
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
    fastparse.parse("foo.add(x, y)", StatefulParser.statement(_)).isSuccess is true
    fastparse.parse("Foo(x).add(x, y)", StatefulParser.statement(_)).isSuccess is true
    fastparse
      .parse("if x >= 1 { y = y + x } else { y = 0 }", StatelessParser.statement(_))
      .isSuccess is true
  }

  it should "parse functions" in {
    val parsed0 = fastparse
      .parse("fn add(x: U64, y: U64) -> (U64, U64) { return x + y, x - y }",
             StatelessParser.func(_))
      .get
      .value
    parsed0.id is Ast.FuncId("add", false)
    parsed0.isPublic is false
    parsed0.isPayable is false
    parsed0.args.size is 2
    parsed0.rtypes is Seq(Type.U64, Type.U64)

    val parsed1 = fastparse
      .parse("pub payable fn add(x: U64, y: U64) -> (U64, U64) { return x + y, x - y }",
             StatelessParser.func(_))
      .get
      .value
    parsed1.id is Ast.FuncId("add", false)
    parsed1.isPublic is true
    parsed1.isPayable is true
    parsed1.args.size is 2
    parsed1.rtypes is Seq(Type.U64, Type.U64)
  }

}
