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

import akka.util.ByteString
import fastparse._
import org.scalacheck.Gen

import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.{StatefulContext, StatelessContext, Val}
import org.alephium.ralph.ArithOperator._
import org.alephium.ralph.LogicalOperator._
import org.alephium.ralph.TestOperator._
import org.alephium.ralph.error.CompilerError
import org.alephium.util.{AlephiumSpec, AVector, Hex, I256, U256}

// scalastyle:off file.size.limit
class ParserSpec(fileURI: Option[java.net.URI]) extends AlephiumSpec {
  import Ast._

  val StatelessParser = new StatelessParser(fileURI)
  val StatefulParser  = new StatefulParser(fileURI)
  /*
   * parse and check if source index is set on successful parse
   * check if fileURI is set on successful parse
   *
   * @return: the initial parsed result
   */
  def parse[A <: Positioned](
      code: String,
      p: fastparse.P[_] => fastparse.P[A]
  ): fastparse.Parsed[A] = {
    val result = fastparse.parse(code, p)
    if (result.isSuccess) {
      result.get.value.sourceIndex.get.fileURI is fileURI
    }
    result
  }

  it should "parse exprs" in {
    parse("x + y", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](Add, Variable(Ident("x")), Variable(Ident("y")))
    parse("x >= y", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](Ge, Variable(Ident("x")), Variable(Ident("y")))
    parse("(x + y)", StatelessParser.expr(_)).get.value is
      ParenExpr[StatelessContext](Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))
    parse("(x + y) + (x + y)", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y")))),
        ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))
      )
    parse("x * y ** z + u", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        Binop(Mul, Variable(Ident("x")), Binop(Exp, Variable(Ident("y")), Variable(Ident("z")))),
        Variable(Ident("u"))
      )
    parse("x / y |**| z + u", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        Binop(Div, Variable(Ident("x")), Binop(ModExp, Variable(Ident("y")), Variable(Ident("z")))),
        Variable(Ident("u"))
      )
    parse("x + y * z + u", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        Binop(Add, Variable(Ident("x")), Binop(Mul, Variable(Ident("y")), Variable(Ident("z")))),
        Variable(Ident("u"))
      )
    parse("x < y <= y < z", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](Lt, Variable(Ident("x")), Variable(Ident("y")))
    parse("x && y || z", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Or,
        Binop(And, Variable(Ident("x")), Variable(Ident("y"))),
        Variable(Ident("z"))
      )
    parse("(x * if (true) a else b) / y", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Div,
        ParenExpr(
          Binop(
            Mul,
            Variable(Ident("x")),
            IfElseExpr(
              Seq(IfBranchExpr(Const(Val.True), Variable(Ident("a")))),
              ElseBranchExpr(Variable(Ident("b")))
            )
          )
        ),
        Variable(Ident("y"))
      )
    parse("[if (a) b else c; 2]", StatelessParser.expr(_)).get.value is
      CreateArrayExpr2[StatelessContext](
        IfElseExpr(
          Seq(IfBranchExpr(Variable(Ident("a")), Variable(Ident("b")))),
          ElseBranchExpr(Variable(Ast.Ident("c")))
        ),
        Const(Val.U256(U256.Two))
      )
    parse("[if (a) b else c, if (a) b else c]", StatelessParser.expr(_)).get.value is
      CreateArrayExpr1[StatelessContext](
        Seq(
          IfElseExpr(
            Seq(IfBranchExpr(Variable(Ident("a")), Variable(Ident("b")))),
            ElseBranchExpr(Variable(Ast.Ident("c")))
          ),
          IfElseExpr(
            Seq(IfBranchExpr(Variable(Ident("a")), Variable(Ident("b")))),
            ElseBranchExpr(Variable(Ast.Ident("c")))
          )
        )
      )
    parse("foo(ErrorCodes.Error)", StatefulParser.expr(_)).get.value is
      CallExpr[StatefulContext](
        FuncId("foo", false),
        Seq.empty,
        Seq(EnumFieldSelector(TypeId("ErrorCodes"), Ident("Error")))
      )
    parse("Foo { x: 1, y: false }", StatefulParser.expr(_)).get.value is
      StructCtor[StatefulContext](
        TypeId("Foo"),
        Seq(
          (Ident("x"), Some(Const(Val.U256(U256.unsafe(1))))),
          (Ident("y"), Some(Const(Val.False)))
        )
      )
    parse("(Foo { x: 1, y: false }).x", StatefulParser.expr(_)).get.value is
      LoadDataBySelectors[StatefulContext](
        ParenExpr(
          StructCtor(
            TypeId("Foo"),
            Seq(
              (Ident("x"), Some(Const(Val.U256(U256.unsafe(1))))),
              (Ident("y"), Some(Const(Val.False)))
            )
          )
        ),
        Seq(IdentSelector(Ident("x")))
      )
    parse("Foo { x: true, bar: Bar { y: false } }", StatefulParser.expr(_)).get.value is
      StructCtor[StatefulContext](
        TypeId("Foo"),
        Seq(
          (Ident("x"), Some(Const(Val.True))),
          (Ident("bar"), Some(StructCtor(TypeId("Bar"), Seq((Ident("y"), Some(Const(Val.False)))))))
        )
      )
    parse("Foo { x: true, bar: [Bar { y: false }; 2] }", StatefulParser.expr(_)).get.value is
      StructCtor[StatefulContext](
        TypeId("Foo"),
        Seq(
          (Ident("x"), Some(Const(Val.True))),
          (
            Ident("bar"),
            Some(
              CreateArrayExpr2(
                StructCtor(TypeId("Bar"), Seq((Ident("y"), Some(Const(Val.False))))),
                Const(Val.U256(U256.Two))
              )
            )
          )
        )
      )
    parse("Foo { x, y }", StatefulParser.expr(_)).get.value is
      StructCtor[StatefulContext](
        TypeId("Foo"),
        Seq((Ident("x"), None), (Ident("y"), None))
      )
    parse("Foo { x: true, y }", StatefulParser.expr(_)).get.value is
      StructCtor[StatefulContext](
        TypeId("Foo"),
        Seq((Ident("x"), Some(Const(Val.True))), (Ident("y"), None))
      )
    parse("a.b.c", StatefulParser.expr(_)).get.value is
      LoadDataBySelectors[StatefulContext](
        Variable(Ident("a")),
        Seq(Ast.IdentSelector(Ident("b")), Ast.IdentSelector(Ident("c")))
      )
    parse("a[0].b.c", StatefulParser.expr(_)).get.value is
      LoadDataBySelectors[StatefulContext](
        Variable(Ident("a")),
        Seq(
          Ast.IndexSelector(Const(Val.U256(U256.Zero))),
          Ast.IdentSelector(Ident("b")),
          Ast.IdentSelector(Ident("c"))
        )
      )
    parse("a.b[0].c", StatefulParser.expr(_)).get.value is
      LoadDataBySelectors[StatefulContext](
        Variable(Ident("a")),
        Seq(
          Ast.IdentSelector(Ident("b")),
          Ast.IndexSelector(Const(Val.U256(U256.Zero))),
          Ast.IdentSelector(Ident("c"))
        )
      )
    parse("a.b[0][1].c", StatefulParser.expr(_)).get.value is
      LoadDataBySelectors[StatefulContext](
        Variable(Ident("a")),
        Seq(
          Ast.IdentSelector(Ident("b")),
          Ast.IndexSelector(Const(Val.U256(U256.Zero))),
          Ast.IndexSelector(Const(Val.U256(U256.One))),
          Ast.IdentSelector(Ident("c"))
        )
      )
    parse("a[0].foo()", StatefulParser.expr(_)).get.value is
      ContractCallExpr(
        LoadDataBySelectors(Variable(Ident("a")), Seq(IndexSelector(Const(Val.U256(U256.Zero))))),
        FuncId("foo", false),
        Seq.empty,
        Seq.empty
      )
    parse("a[0][0].foo()", StatefulParser.expr(_)).get.value is
      ContractCallExpr(
        LoadDataBySelectors(
          Variable(Ident("a")),
          Seq.fill(2)(IndexSelector(Const(Val.U256(U256.Zero))))
        ),
        FuncId("foo", false),
        Seq.empty,
        Seq.empty
      )
    parse("a.b[0].c.foo()", StatefulParser.expr(_)).get.value is
      ContractCallExpr(
        LoadDataBySelectors(
          Variable(Ident("a")),
          Seq(
            IdentSelector(Ident("b")),
            IndexSelector(Const(Val.U256(U256.Zero))),
            IdentSelector(Ident("c"))
          )
        ),
        FuncId("foo", false),
        Seq.empty,
        Seq.empty
      )
    parse("a.b.foo()[0].bar()", StatefulParser.expr(_)).get.value is
      ContractCallExpr(
        LoadDataBySelectors(
          ContractCallExpr(
            LoadDataBySelectors(Variable(Ident("a")), Seq(IdentSelector(Ident("b")))),
            FuncId("foo", false),
            Seq.empty,
            Seq.empty
          ),
          Seq(IndexSelector(Const(Val.U256(U256.Zero))))
        ),
        FuncId("bar", false),
        Seq.empty,
        Seq.empty
      )
    parse("foo().a.bar().d[0]", StatefulParser.expr(_)).get.value is
      LoadDataBySelectors(
        ContractCallExpr(
          LoadDataBySelectors(
            CallExpr(FuncId("foo", false), Seq.empty, Seq.empty),
            Seq(IdentSelector(Ident("a")))
          ),
          FuncId("bar", false),
          Seq.empty,
          Seq.empty
        ),
        Seq(IdentSelector(Ident("d")), IndexSelector(Const(Val.U256(U256.Zero))))
      )
    parse("a.b.foo()[0].bar().c.d[0]", StatefulParser.expr(_)).get.value is
      LoadDataBySelectors(
        ContractCallExpr(
          LoadDataBySelectors(
            ContractCallExpr(
              LoadDataBySelectors(Variable(Ident("a")), Seq(IdentSelector(Ident("b")))),
              FuncId("foo", false),
              Seq.empty,
              Seq.empty
            ),
            Seq(IndexSelector(Const(Val.U256(U256.Zero))))
          ),
          FuncId("bar", false),
          Seq.empty,
          Seq.empty
        ),
        Seq(
          IdentSelector(Ident("c")),
          IdentSelector(Ident("d")),
          IndexSelector(Const(Val.U256(U256.Zero)))
        )
      )
  }

  it should "parse string literals" in {
    def test(testString: String) = {
      parse(s"b`$testString`", StatelessParser.expr(_)).get.value is
        Const[StatelessContext](
          Val.ByteVec(ByteString.fromString(testString))
        )
    }

    test("Foo")
    test(" Foo")
    test("Foo ")
    test(" Foo ")
    test("Foo Bar")
    test("")
    test(s"$$$${a}")
    test(s"Hello, $$$${a}$$$${b} $$$${c} $$$$$$$$ $$$$ !")
    test(s"Sharding is hard $$")
  }

  it should "parse function" in {
    info("Function")
    parse("foo(x)", StatelessParser.expr(_)).get.value is
      CallExpr[StatelessContext](FuncId("foo", false), Seq.empty, List(Variable(Ident("x"))))
    parse("Foo(x)", StatelessParser.expr(_)).get.value is
      ContractConv[StatelessContext](Ast.TypeId("Foo"), Variable(Ident("x")))
    parse("foo!(x)", StatelessParser.expr(_)).get.value is
      CallExpr[StatelessContext](FuncId("foo", true), Seq.empty, List(Variable(Ident("x"))))
    parse("foo(x + y) + bar!(x + y)", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        CallExpr(
          FuncId("foo", false),
          Seq.empty,
          List(Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))
        ),
        CallExpr(
          FuncId("bar", true),
          Seq.empty,
          List(Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))
        )
      )
    fastparse
      .parse("foo{ x -> ALPH: 1e-18 alph, token: 2; y -> ALPH: 3 }(z)", StatefulParser.expr(_))
      .get
      .value is
      CallExpr[StatefulContext](
        FuncId("foo", false),
        Seq(
          Ast.ApproveAsset(
            Variable(Ident("x")),
            Seq(
              Ast.ALPHTokenId()        -> Const(Val.U256(U256.One)),
              Variable(Ident("token")) -> Const(Val.U256(U256.Two))
            )
          ),
          Ast.ApproveAsset(
            Variable(Ident("y")),
            Seq(Ast.ALPHTokenId() -> Const(Val.U256(U256.unsafe(3))))
          )
        ),
        List(Variable(Ident("z")))
      )

    parse("foo(1)", StatelessParser.expr(_)).get.value is
      CallExpr[StatelessContext](FuncId("foo", false), Seq.empty, List(Const(Val.U256(U256.One))))

    parse("foo(#00)", StatelessParser.expr(_)).get.value is
      CallExpr[StatelessContext](
        FuncId("foo", false),
        Seq.empty,
        List(Const(Val.ByteVec(ByteString(Hex.unsafe("00")))))
      )

    parse("foo(b`Hello`)", StatelessParser.expr(_)).get.value is
      CallExpr[StatelessContext](
        FuncId("foo", false),
        Seq.empty,
        List(Const(Val.ByteVec(ByteString.fromString("Hello"))))
      )

    info("Braces syntax")
    fastparse.parse("{ x -> ALPH: 1 alph }", StatelessParser.approveAssets(_)).isSuccess is true
    fastparse.parse("{ x -> tokenId: 2 }", StatelessParser.approveAssets(_)).isSuccess is true
    fastparse
      .parse("{ x -> ALPH: 1 alph, tokenId: 2; y -> ALPH: 3 }", StatelessParser.approveAssets(_))
      .isSuccess is true

    info("Contract call")
    parse("x.bar(x)", StatefulParser.contractCallOrLoadData(_)).get.value is
      ContractCallExpr(
        Variable(Ident("x")),
        FuncId("bar", false),
        Seq.empty,
        List(Variable(Ident("x")))
      )
    fastparse
      .parse("Foo(x).bar{ z -> ALPH: 1 }(x)", StatefulParser.contractCallOrLoadData(_))
      .get
      .value is
      ContractCallExpr(
        ContractConv[StatefulContext](Ast.TypeId("Foo"), Variable(Ident("x"))),
        FuncId("bar", false),
        Seq(
          Ast.ApproveAsset(
            Variable(Ident("z")),
            Seq(ALPHTokenId() -> Const(Val.U256(U256.One)))
          )
        ),
        List(Variable(Ident("x")))
      )

    val emptyAssetsCode = "Foo(x).bar{ z -> }(x)"
    intercept[CompilerError.`Expected non-empty asset(s) for address`](
      parse(emptyAssetsCode, StatefulParser.contractCallOrLoadData(_))
    ).format(emptyAssetsCode) is
      """-- error (1:17): Syntax error
        |1 |Foo(x).bar{ z -> }(x)
        |  |                ^
        |  |                Expected non-empty asset(s) for address
        |""".stripMargin
  }

  it should "call expression" in {
    parse("foo(a)", StatefulParser.callExpr(_)).get.value is
      CallExpr(FuncId("foo", false), Seq.empty, Seq(Variable[StatefulContext](Ident("a"))))
    parse("Foo.foo(a)", StatefulParser.callExpr(_)).get.value is
      ContractStaticCallExpr(
        TypeId("Foo"),
        FuncId("foo", false),
        Seq.empty,
        Seq(Variable[StatefulContext](Ident("a")))
      )
    parse("foo!(a)", StatefulParser.callExpr(_)).get.value is
      CallExpr(FuncId("foo", true), Seq.empty, Seq(Variable[StatefulContext](Ident("a"))))
    parse("Foo.foo!(a)", StatefulParser.callExpr(_)).get.value is
      ContractStaticCallExpr(
        TypeId("Foo"),
        FuncId("foo", true),
        Seq.empty,
        Seq(Variable[StatefulContext](Ident("a")))
      )
  }

  it should "parse contract call" in {
    parse("a.b().c().d()", StatefulParser.contractCallOrLoadData(_)).get.value is
      ContractCallExpr(
        ContractCallExpr(
          ContractCallExpr(Variable(Ident("a")), FuncId("b", false), List(), List()),
          FuncId("c", false),
          List(),
          List()
        ),
        FuncId("d", false),
        List(),
        List()
      )

    parse("a().b().c()", StatefulParser.contractCallOrLoadData(_)).get.value is
      ContractCallExpr(
        ContractCallExpr(
          CallExpr(FuncId("a", false), List(), List()),
          FuncId("b", false),
          List(),
          List()
        ),
        FuncId("c", false),
        List(),
        List()
      )

    parse("a.b().c().d()", StatefulParser.contractCall(_)).get.value is
      ContractCall(
        ContractCallExpr(
          ContractCallExpr(Variable(Ident("a")), FuncId("b", false), List(), List()),
          FuncId("c", false),
          List(),
          List()
        ),
        FuncId("d", false),
        List(),
        List()
      )

    parse("a().b().c()", StatefulParser.contractCall(_)).get.value is
      ContractCall(
        ContractCallExpr(
          CallExpr(FuncId("a", false), List(), List()),
          FuncId("b", false),
          List(),
          List()
        ),
        FuncId("c", false),
        List(),
        List()
      )
  }

  it should "parse ByteVec" in {
    parse("# ++ #00", StatefulParser.expr(_)).get.value is
      Binop[StatefulContext](
        Concat,
        Const(Val.ByteVec(ByteString.empty)),
        Const(Val.ByteVec(Hex.unsafe("00")))
      )
    parse("let bytes = #", StatefulParser.statement(_)).get.value is
      VarDef[StatefulContext](
        Seq(Ast.NamedVar(false, Ident("bytes"))),
        Const(Val.ByteVec(ByteString.empty))
      )
    parse("ALPH", StatefulParser.expr(_)).get.value is ALPHTokenId[StatefulContext]()
  }

  it should "parse return" in {
    parse("return x, y", StatelessParser.ret(_)).isSuccess is true
    parse("return x + y", StatelessParser.ret(_)).isSuccess is true
    parse("return (x + y)", StatelessParser.ret(_)).isSuccess is true
    intercept[Compiler.Error](parse("return return", StatelessParser.ret(_))).message is
      "Consecutive return statements are not allowed"
  }

  it should "parse statements" in {
    parse("let x = 1", StatelessParser.statement(_)).isSuccess is true
    parse("x = 1", StatelessParser.statement(_)).isSuccess is true
    parse("x = true", StatelessParser.statement(_)).isSuccess is true
    parse("ConstantVar = 0", StatefulParser.statement(_)).isSuccess is false
    parse("ErrorCodes.Error = 0", StatefulParser.statement(_)).isSuccess is false
    parse("add(x, y)", StatelessParser.statement(_)).isSuccess is true
    parse("foo.add(x, y)", StatefulParser.statement(_)).isSuccess is true
    parse("Foo(x).add(x, y)", StatefulParser.statement(_)).isSuccess is true
    fastparse
      .parse("if (x >= 1) { y = y + x } else { y = 0 }", StatelessParser.statement(_))
      .isSuccess is true

    fastparse
      .parse("while (true) { x = x + 1 }", StatelessParser.statement(_))
      .get
      .value is a[Ast.While[StatelessContext]]
    fastparse
      .parse("for (let mut i = 0; i < 10; i = i + 1) { x = x + 1 }", StatelessParser.statement(_))
      .get
      .value is a[Ast.ForLoop[StatelessContext]]

    parse("a.b.foo()[0].bar()", StatefulParser.statement(_)).get.value is
      ContractCall(
        LoadDataBySelectors(
          ContractCallExpr(
            LoadDataBySelectors(Variable(Ident("a")), Seq(IdentSelector(Ident("b")))),
            FuncId("foo", false),
            Seq.empty,
            Seq.empty
          ),
          Seq(IndexSelector(Const(Val.U256(U256.Zero))))
        ),
        FuncId("bar", false),
        Seq.empty,
        Seq.empty
      )
    parse("foo().get().bar[0].set()", StatefulParser.statement(_)).get.value is
      ContractCall(
        LoadDataBySelectors(
          ContractCallExpr(
            CallExpr(FuncId("foo", false), Seq.empty, Seq.empty),
            FuncId("get", false),
            Seq.empty,
            Seq.empty
          ),
          Seq(IdentSelector(Ident("bar")), IndexSelector(Const(Val.U256(U256.Zero))))
        ),
        FuncId("set", false),
        Seq.empty,
        Seq.empty
      )
    intercept[Compiler.Error](parse("a.b.foo()[0]", StatefulParser.statement(_))).message is
      "Expected a statement"
  }

  it should "parse debug statements" in {
    parse(s"$${a}", StatelessParser.stringInterpolator(_)).get.value is
      Variable[StatelessContext](Ident("a"))
    parse(s"$${ x + y }", StatelessParser.stringInterpolator(_)).get.value is
      Binop[StatelessContext](Add, Variable(Ident("x")), Variable(Ident("y")))

    parse(s"emit Debug(``)", StatelessParser.debug(_)).get.value is
      Ast.Debug[StatelessContext](AVector(Val.ByteVec(ByteString.empty)), Seq.empty)
    parse(s"emit Debug(`$${a}`)", StatelessParser.debug(_)).get.value is
      Ast.Debug[StatelessContext](
        AVector(Val.ByteVec(ByteString.empty), Val.ByteVec(ByteString.empty)),
        Seq(Variable(Ident("a")))
      )
    fastparse
      .parse(s"emit Debug(`Hello, $${a}$${b} $${c} $$$$ $$` !`)", StatelessParser.debug(_))
      .get
      .value is
      Ast.Debug[StatelessContext](
        AVector(
          Val.ByteVec(ByteString.fromString("Hello, ")),
          Val.ByteVec(ByteString.empty),
          Val.ByteVec(ByteString.fromString(" ")),
          Val.ByteVec(ByteString.fromString(" $ ` !"))
        ),
        Seq(Variable(Ident("a")), Variable(Ident("b")), Variable(Ident("c")))
      )
  }

  it should "parse if-else statements" in {
    fastparse
      .parse("if (x) { return }", StatelessParser.statement(_))
      .get
      .value is
      Ast.IfElseStatement[StatelessContext](
        Seq(Ast.IfBranchStatement(Variable(Ast.Ident("x")), Seq(ReturnStmt(Seq.empty)))),
        None
      )

    val error = intercept[Compiler.Error](
      fastparse
        .parse("if (x) { return } else if (y) { return }", StatelessParser.statement(_))
    )
    error.message is "If ... else if constructs should be terminated with an else statement"

    fastparse
      .parse("if (x) { return } else if (y) { return } else {}", StatelessParser.statement(_))
      .get
      .value is
      Ast.IfElseStatement[StatelessContext](
        Seq(
          Ast.IfBranchStatement(Variable(Ast.Ident("x")), Seq(ReturnStmt(Seq.empty))),
          Ast.IfBranchStatement(Variable(Ast.Ident("y")), Seq(ReturnStmt(Seq.empty)))
        ),
        Some(Ast.ElseBranchStatement(Seq.empty))
      )
  }

  it should "parse if-else expressions" in {
    fastparse
      .parse("if (cond) 0 else 1", StatelessParser.expr(_))
      .get
      .value is
      Ast.IfElseExpr[StatelessContext](
        Seq(
          Ast.IfBranchExpr(Variable(Ast.Ident("cond")), Ast.Const(Val.U256(U256.Zero)))
        ),
        Ast.ElseBranchExpr(Ast.Const(Val.U256(U256.One)))
      )

    fastparse
      .parse("if (cond0) 0 else if (cond1) 1 else 2", StatelessParser.expr(_))
      .get
      .value is
      Ast.IfElseExpr[StatelessContext](
        Seq(
          Ast.IfBranchExpr(Variable(Ast.Ident("cond0")), Ast.Const(Val.U256(U256.Zero))),
          Ast.IfBranchExpr(Variable(Ast.Ident("cond1")), Ast.Const(Val.U256(U256.One)))
        ),
        Ast.ElseBranchExpr(Ast.Const(Val.U256(U256.Two)))
      )

    val missingElseCode = "if (cond0) 0"
    val error = intercept[CompilerError.`Expected else statement`](
      parse(missingElseCode, StatelessParser.expr(_))
    )
    error.format(missingElseCode) is
      """-- error (1:13): Syntax error
        |1 |if (cond0) 0
        |  |            ^
        |  |            Expected `else` statement
        |  |------------------------------------------------------------------------------------------
        |  |Description: `if/else` expressions require both `if` and `else` statements to be complete.
        |""".stripMargin
  }

  it should "parse annotations" in {
    parse("@using(x = true, y = false)", StatefulParser.annotation(_)).isSuccess is true
  }

  it should "parse functions" in {
    def parseFunc(code: String) = {
      fastparse.parse(code, StatelessParser.func(_))
    }

    val parsed0 =
      parseFunc("fn add(x: U256, y: U256) -> (U256, U256) { return x + y, x - y }").get.value
    parsed0.id is Ast.FuncId("add", false)
    parsed0.isPublic is false
    parsed0.usePreapprovedAssets is false
    parsed0.useAssetsInContract is Ast.NotUseContractAssets
    parsed0.usePayToContractOnly is false
    parsed0.args.size is 2
    parsed0.rtypes is Seq(Type.U256, Type.U256)

    val parsed1 = parseFunc(
      """@using(preapprovedAssets = true, updateFields = false)
        |pub fn add(x: U256, mut y: U256) -> (U256, U256) { return x + y, x - y }
        |""".stripMargin
    ).get.value
    parsed1.id is Ast.FuncId("add", false)
    parsed1.isPublic is true
    parsed1.usePreapprovedAssets is true
    parsed1.useAssetsInContract is Ast.NotUseContractAssets
    parsed1.usePayToContractOnly is false
    parsed1.useCheckExternalCaller is true
    parsed1.useUpdateFields is false
    parsed1.args.size is 2
    parsed1.rtypes is Seq(Type.U256, Type.U256)
    parsed1.signature is FuncSignature(
      FuncId("add", false),
      true,
      true,
      Seq((Type.U256, false), (Type.U256, true)),
      Seq(Type.U256, Type.U256)
    )

    info("Simple return type")
    val parsed2 =
      parseFunc(
        """@using(preapprovedAssets = true, assetsInContract = true)
          |pub fn add(x: U256, y: U256) -> U256 { return x + y }""".stripMargin
      ).get.value
    parsed2.id is Ast.FuncId("add", false)
    parsed2.isPublic is true
    parsed2.usePreapprovedAssets is true
    parsed2.useAssetsInContract is Ast.UseContractAssets
    parsed2.usePayToContractOnly is false
    parsed2.useCheckExternalCaller is true
    parsed2.useUpdateFields is false
    parsed2.args.size is 2
    parsed2.rtypes is Seq(Type.U256)
    parsed2.signature is FuncSignature(
      FuncId("add", false),
      true,
      true,
      Seq((Type.U256, false), (Type.U256, false)),
      Seq(Type.U256)
    )

    info("More use annotation")
    val parsed3 =
      parseFunc(
        """@using(assetsInContract = true, updateFields = true)
          |pub fn add(x: U256, y: U256) -> U256 { return x + y }""".stripMargin
      ).get.value
    parsed3.usePreapprovedAssets is false
    parsed3.useAssetsInContract is Ast.UseContractAssets
    parsed3.usePayToContractOnly is false
    parsed3.useCheckExternalCaller is true
    parsed3.useUpdateFields is true
    parsed3.signature is FuncSignature(
      FuncId("add", false),
      true,
      false,
      Seq((Type.U256, false), (Type.U256, false)),
      Seq(Type.U256)
    )

    info("Enforce using contract assets")
    val code = """@using(assetsInContract = enforced)
                 |pub fn add(x: U256, y: U256) -> U256 { return x + y }""".stripMargin
    val parsed4 = parseFunc(code).get.value
    parsed4.usePreapprovedAssets is false
    parsed4.useAssetsInContract is Ast.EnforcedUseContractAssets
    parsed4.usePayToContractOnly is false
    parsed4.useCheckExternalCaller is true
    parsed4.useUpdateFields is false
    parsed4.signature is FuncSignature(
      FuncId("add", false),
      true,
      false,
      Seq((Type.U256, false), (Type.U256, false)),
      Seq(Type.U256)
    )

    info("Use PayToContractOnly annotation")
    val parsed5 =
      parseFunc(
        """@using(payToContractOnly = true)
          |pub fn add(x: U256, y: U256) -> U256 { return x + y }""".stripMargin
      ).get.value
    parsed5.usePreapprovedAssets is false
    parsed5.useAssetsInContract is Ast.NotUseContractAssets
    parsed5.usePayToContractOnly is true
    parsed5.useCheckExternalCaller is true
    parsed5.useUpdateFields is false
    parsed5.signature is FuncSignature(
      FuncId("add", false),
      true,
      false,
      Seq((Type.U256, false), (Type.U256, false)),
      Seq(Type.U256)
    )

    val invalidCode = """@using(assetsInContract = enforced, assetsInContract = false)
                        |pub fn add(x: U256, y: U256) -> U256 { return x + y }""".stripMargin
    intercept[Compiler.Error](parseFunc(invalidCode)).message is
      "These keys are defined multiple times: assetsInContract"

    val invalidAssetsInContract =
      s"""@using($$assetsInContract = 1)
         |pub fn add(x: U256, y: U256) -> U256 { return x + y }""".stripMargin
    val error = intercept[Compiler.Error](parseFunc(invalidAssetsInContract.replace("$", "")))
    error.message is "Invalid assetsInContract annotation, expected true/false/enforced"
    error.position is invalidAssetsInContract.indexOf("$")

    val conflictedAnnotations =
      s"""@using($$assetsInContract = true, payToContractOnly = true)
         |pub fn add(x: U256, y: U256) -> U256 { return x + y }""".stripMargin
    val error1 = intercept[Compiler.Error](parseFunc(conflictedAnnotations.replace("$", "")))
    error1.message is "Can only enable one of the two annotations: @using(assetsInContract = true/enforced) or @using(payToContractOnly = true)"
    error1.position is invalidAssetsInContract.indexOf("$")
  }

  it should "parse bytes and address" in {
    val hash    = Hash.random
    val address = Address.p2pkh(PublicKey.generate)
    parse(
      s"foo.foo(#${hash.toHexString}, #${hash.toHexString}, @${address.toBase58})",
      StatefulParser.contractCall(_)
    ).get.value is a[ContractCall]
  }

  it should "parse array types" in {
    def check(str: String, arguments: Seq[Argument]) = {
      fastparse.parse(str, StatelessParser.funParams(_)).get.value is arguments
    }

    val funcArgs = List(
      "(mut a: [Bool; 2], b: [[Address; 3]; 2], c: [Foo; SIZE], d: U256)" ->
        Seq(
          Argument(
            Ident("a"),
            Type.FixedSizeArray(Type.Bool, Left(2)),
            isMutable = true,
            isUnused = false
          ),
          Argument(
            Ident("b"),
            Type.FixedSizeArray(Type.FixedSizeArray(Type.Address, Left(3)), Left(2)),
            isMutable = false,
            isUnused = false
          ),
          Argument(
            Ident("c"),
            Type.FixedSizeArray(Type.NamedType(TypeId("Foo")), Right(Variable(Ident("SIZE")))),
            isMutable = false,
            isUnused = false
          ),
          Argument(Ident("d"), Type.U256, isMutable = false, isUnused = false)
        )
    )

    funcArgs.foreach { case (str, args) =>
      check(str, args)
    }
  }

  def constantIndex[Ctx <: StatelessContext](value: Int): Ast.Const[Ctx] =
    Ast.Const[Ctx](Val.U256(U256.unsafe(value)))

  def checkParseExpr(str: String, expr: Ast.Expr[StatelessContext]) = {
    parse(str, StatelessParser.expr(_)).get.value is expr
  }

  def checkParseStat(str: String, stat: Ast.Statement[StatelessContext]) = {
    parse(str, StatelessParser.statement(_)).get.value is stat
  }

  it should "parse variable definitions" in {
    val states: List[(String, Ast.Statement[StatelessContext])] = List(
      "let (a, b) = foo()" -> Ast.VarDef(
        Seq(Ast.NamedVar(false, Ast.Ident("a")), Ast.NamedVar(false, Ast.Ident("b"))),
        Ast.CallExpr(Ast.FuncId("foo", false), Seq.empty, Seq.empty)
      ),
      "let (a, mut b) = foo()" -> Ast.VarDef(
        Seq(Ast.NamedVar(false, Ast.Ident("a")), Ast.NamedVar(true, Ast.Ident("b"))),
        Ast.CallExpr(Ast.FuncId("foo", false), Seq.empty, Seq.empty)
      ),
      "let (mut a, mut b) = foo()" -> Ast.VarDef(
        Seq(Ast.NamedVar(true, Ast.Ident("a")), Ast.NamedVar(true, Ast.Ident("b"))),
        Ast.CallExpr(Ast.FuncId("foo", false), Seq.empty, Seq.empty)
      ),
      "let _ = foo()" -> Ast.VarDef(
        Seq(Ast.AnonymousVar),
        Ast.CallExpr(Ast.FuncId("foo", false), Seq.empty, Seq.empty)
      ),
      "let (_, _) = foo()" -> Ast.VarDef(
        Seq(Ast.AnonymousVar, Ast.AnonymousVar),
        Ast.CallExpr(Ast.FuncId("foo", false), Seq.empty, Seq.empty)
      ),
      "let (_, a, b) = foo()" -> Ast.VarDef(
        Seq(
          Ast.AnonymousVar,
          Ast.NamedVar(false, Ast.Ident("a")),
          Ast.NamedVar(false, Ast.Ident("b"))
        ),
        Ast.CallExpr(Ast.FuncId("foo", false), Seq.empty, Seq.empty)
      ),
      "let (mut a, _, _) = foo()" -> Ast.VarDef(
        Seq(Ast.NamedVar(true, Ast.Ident("a")), Ast.AnonymousVar, Ast.AnonymousVar),
        Ast.CallExpr(Ast.FuncId("foo", false), Seq.empty, Seq.empty)
      )
    )
    states.foreach { case (code, ast) =>
      checkParseStat(code, ast)
    }
  }

  it should "parse array expression" in {
    val exprs: List[(String, Ast.Expr[StatelessContext])] = List(
      "a[0u][1u]" -> Ast
        .LoadDataBySelectors(
          Variable(Ast.Ident("a")),
          Seq(
            IndexSelector(constantIndex(0)),
            IndexSelector(constantIndex(1))
          )
        ),
      "a[i]" -> LoadDataBySelectors(
        Variable(Ident("a")),
        Seq(IndexSelector(Variable(Ident("i"))))
      ),
      "a[foo()]" -> Ast
        .LoadDataBySelectors(
          Variable(Ast.Ident("a")),
          Seq(IndexSelector(CallExpr(FuncId("foo", false), Seq.empty, Seq.empty)))
        ),
      "a[i + 1]" -> Ast.LoadDataBySelectors(
        Variable(Ast.Ident("a")),
        Seq(
          IndexSelector(
            Binop(ArithOperator.Add, Variable(Ast.Ident("i")), Const(Val.U256(U256.unsafe(1))))
          )
        )
      ),
      "!a[0][1]" -> Ast.UnaryOp(
        LogicalOperator.Not,
        Ast.LoadDataBySelectors(
          Variable(Ast.Ident("a")),
          Seq(
            IndexSelector(constantIndex(0)),
            IndexSelector(constantIndex(1))
          )
        )
      ),
      "[a, a]" -> Ast.CreateArrayExpr1(Seq(Variable(Ast.Ident("a")), Variable(Ast.Ident("a")))),
      "[a; 2]" -> Ast.CreateArrayExpr2(Variable(Ast.Ident("a")), Const(Val.U256(U256.Two))),
      "[[1, 1], [1, 1]]" -> Ast.CreateArrayExpr1(
        Seq.fill(2)(Ast.CreateArrayExpr1(Seq.fill(2)(Ast.Const(Val.U256(U256.One)))))
      ),
      "[[1; 2]; 2]" -> Ast.CreateArrayExpr2(
        Ast.CreateArrayExpr2(Const(Val.U256(U256.One)), Const(Val.U256(U256.Two))),
        Const(Val.U256(U256.Two))
      )
    )

    exprs.foreach { case (str, expr) =>
      checkParseExpr(str, expr)
    }
  }

  it should "parse assign statement" in {
    val stats: List[(String, Ast.Statement[StatelessContext])] = List(
      "a[0] = b" -> Assign(
        Seq(
          AssignmentSelectedTarget(
            Ident("a"),
            Seq(IndexSelector(constantIndex(0)))
          )
        ),
        Ast.Variable(Ast.Ident("b"))
      ),
      "a[0][1] = b[0]" -> Assign(
        Seq(
          AssignmentSelectedTarget(
            Ident("a"),
            Seq(
              IndexSelector(constantIndex(0)),
              IndexSelector(constantIndex(1))
            )
          )
        ),
        Ast.LoadDataBySelectors(Ast.Variable(Ast.Ident("b")), Seq(IndexSelector(constantIndex(0))))
      ),
      "a, b = foo()" -> Assign(
        Seq(AssignmentSimpleTarget(Ident("a")), AssignmentSimpleTarget(Ident("b"))),
        CallExpr(FuncId("foo", false), Seq.empty, Seq.empty)
      ),
      "a[i] = b" -> Assign(
        Seq(
          AssignmentSelectedTarget(
            Ident("a"),
            Seq(IndexSelector(Variable(Ident("i"))))
          )
        ),
        Ast.Variable(Ast.Ident("b"))
      ),
      "a[foo()] = b" -> Assign(
        Seq(
          AssignmentSelectedTarget(
            Ident("a"),
            Seq(IndexSelector(CallExpr(FuncId("foo", false), Seq.empty, Seq.empty)))
          )
        ),
        Ast.Variable(Ast.Ident("b"))
      ),
      "a[i + 1] = b" -> Assign(
        Seq(
          AssignmentSelectedTarget(
            Ident("a"),
            Seq(
              IndexSelector(
                Binop(ArithOperator.Add, Variable(Ident("i")), Const(Val.U256(U256.unsafe(1))))
              )
            )
          )
        ),
        Ast.Variable(Ast.Ident("b"))
      ),
      "a.b = c" -> Assign(
        Seq(
          AssignmentSelectedTarget(
            Ident("a"),
            Seq(IdentSelector(Ident("b")))
          )
        ),
        Ast.Variable(Ast.Ident("c"))
      ),
      "a[0].b = c" -> Assign(
        Seq(
          AssignmentSelectedTarget(
            Ident("a"),
            Seq(
              IndexSelector(constantIndex(0)),
              IdentSelector(Ident("b"))
            )
          )
        ),
        Ast.Variable(Ast.Ident("c"))
      ),
      "a.b[0] = c" -> Assign(
        Seq(
          AssignmentSelectedTarget(
            Ident("a"),
            Seq(
              Ast.IdentSelector(Ident("b")),
              Ast.IndexSelector(Const(Val.U256(U256.Zero)))
            )
          )
        ),
        Ast.Variable(Ast.Ident("c"))
      ),
      "a.b[0].c = d" -> Assign(
        Seq(
          AssignmentSelectedTarget(
            Ident("a"),
            Seq(
              Ast.IdentSelector(Ident("b")),
              Ast.IndexSelector(Const(Val.U256(U256.Zero))),
              Ast.IdentSelector(Ident("c"))
            )
          )
        ),
        Ast.Variable(Ast.Ident("d"))
      )
    )

    stats.foreach { case (str, ast) =>
      checkParseStat(str, ast)
    }
  }

  it should "parse event definition" in {
    {
      info("0 field")

      val eventRaw = "event Event()"
      parse(eventRaw, StatefulParser.eventDef(_)).get.value is EventDef(
        TypeId("Event"),
        Seq()
      )
    }

    {
      info("fields of primitive types")

      val eventRaw = "event Transfer(from: Address, to: Address, amount: U256)"
      val res      = parse(eventRaw, StatefulParser.eventDef(_)).get.value
      res is EventDef(
        TypeId("Transfer"),
        Seq(
          EventField(Ident("from"), Type.Address),
          EventField(Ident("to"), Type.Address),
          EventField(Ident("amount"), Type.U256)
        )
      )
    }

    {
      info("fields of array type")

      val eventRaw = "event Participants(addresses: [Address; 3])"
      parse(eventRaw, StatefulParser.eventDef(_)).get.value is EventDef(
        TypeId("Participants"),
        Seq(
          EventField(Ident("addresses"), Type.FixedSizeArray(Type.Address, Left(3)))
        )
      )
    }
  }

  it should "parse constant variable definition" in {
    val invalidDefinition = "const c = 1"
    val failure = fastparse
      .parse(invalidDefinition, StatefulParser.constantVarDef(_))
      .asInstanceOf[fastparse.Parsed.Failure]
    failure.trace().longMsg.contains("constant variables must start with an uppercase letter")

    val address = Address.p2pkh(PublicKey.generate)
    val definitions = Seq[(String, Expr[StatefulContext])](
      ("const C = true", Const(Val.True)),
      ("const C = 1", Const(Val.U256(U256.One))),
      ("const C = 1i", Const(Val.I256(I256.One))),
      ("const C = #11", Const(Val.ByteVec(Hex.unsafe("11")))),
      ("const C = b`hello`", Const(Val.ByteVec(ByteString.fromString("hello")))),
      (s"const C = @${address.toBase58}", Const(Val.Address(address.lockupScript))),
      ("const C = A + B", Binop(ArithOperator.Add, Variable(Ident("A")), Variable(Ident("B"))))
    )
    definitions.foreach { definition =>
      val constantVar = parse(definition._1, StatefulParser.constantVarDef(_)).get.value
      constantVar.ident.name is "C"
      constantVar.expr is definition._2
    }
  }

  it should "parse enum definitions" in {
    {
      info("Invalid enum type")
      val definition =
        s"""
           |enum errorCodes {
           |  Error = 0
           |}
           |""".stripMargin
      parse(definition, StatefulParser.enumDef(_)).isSuccess is false
    }

    {
      info("Invalid enum field name")
      val definition =
        s"""
           |enum ErrorCodes {
           |  error = 0
           |}
           |""".stripMargin
      val failure = fastparse
        .parse(definition, StatefulParser.enumDef(_))
        .asInstanceOf[fastparse.Parsed.Failure]
      failure.trace().longMsg.contains("constant variables must start with an uppercase letter")
    }

    {
      info("Invalid enum field type")
      val definition =
        s"""
           |enum ErrorCodes {
           |  Error0 = 0
           |  Error1 = #00
           |}
           |""".stripMargin
      val error = intercept[Compiler.Error](parse(definition, StatefulParser.enumDef(_)))
      error.message is "Fields have different types in Enum ErrorCodes"
    }

    {
      info("Invalid enum field values")
      val definition =
        s"""
           |enum ErrorCodes {
           |  Error0 = 0
           |  Error1 = 0
           |}
           |""".stripMargin
      val error = intercept[Compiler.Error](parse(definition, StatefulParser.enumDef(_)))
      error.message is "Fields have the same value in Enum ErrorCodes"
    }

    {
      info("Duplicated enum fields")
      val definition =
        s"""
           |enum ErrorCodes {
           |  Error = 0
           |  Error = 1
           |}
           |""".stripMargin
      val error = intercept[Compiler.Error](parse(definition, StatefulParser.enumDef(_)))
      error.message is "These enum fields are defined multiple times: Error"
    }

    {
      info("Enum definition with U256 fields")
      val definition =
        s"""
           |enum ErrorCodes {
           |  Error0 = 0
           |  Error1 = 1
           |}
           |""".stripMargin
      parse(definition, StatefulParser.enumDef(_)).get.value is EnumDef(
        TypeId("ErrorCodes"),
        Seq(
          EnumField(Ident("Error0"), Const[StatefulContext](Val.U256(U256.Zero))),
          EnumField(Ident("Error1"), Const[StatefulContext](Val.U256(U256.One)))
        )
      )
    }

    {
      info("Enum definition with ByteVec fields")
      val definition =
        s"""
           |enum ErrorCodes {
           |  Error0 = #00
           |  Error1 = #01
           |}
           |""".stripMargin
      parse(definition, StatefulParser.enumDef(_)).get.value is EnumDef(
        TypeId("ErrorCodes"),
        Seq(
          EnumField(Ident("Error0"), Const[StatefulContext](Val.ByteVec(Hex.unsafe("00")))),
          EnumField(Ident("Error1"), Const[StatefulContext](Val.ByteVec(Hex.unsafe("01"))))
        )
      )
    }

    {
      info("Enum definition with String Literals fields")
      val definition =
        s"""
           |enum ErrorCodes {
           |  Error0 = #00
           |  Error1 = b`hello`
           |  Error2 = b`world`
           |}
           |""".stripMargin
      parse(definition, StatefulParser.enumDef(_)).get.value is EnumDef(
        TypeId("ErrorCodes"),
        Seq(
          EnumField(Ident("Error0"), Const[StatefulContext](Val.ByteVec(Hex.unsafe("00")))),
          EnumField(
            Ident("Error1"),
            Const[StatefulContext](Val.ByteVec(ByteString.fromString("hello")))
          ),
          EnumField(
            Ident("Error2"),
            Const[StatefulContext](Val.ByteVec(ByteString.fromString("world")))
          )
        )
      )
    }
  }

  it should "parse contract inheritance" in {
    {
      info("Simple contract inheritance")
      def code(keyword: String): String =
        s"""
           |Contract Child(x: U256, y: U256) $keyword Parent0(x), Parent1(x) {
           |  fn foo() -> () {
           |  }
           |}
           |""".stripMargin

      val contract = Contract(
        None,
        None,
        false,
        TypeId("Child"),
        Seq.empty,
        Seq(
          Argument(Ident("x"), Type.U256, false, false),
          Argument(Ident("y"), Type.U256, false, false)
        ),
        Seq(
          FuncDef(
            Seq.empty,
            FuncId("foo", false),
            isPublic = false,
            usePreapprovedAssets = false,
            useAssetsInContract = Ast.NotUseContractAssets,
            usePayToContractOnly = false,
            useCheckExternalCaller = true,
            useUpdateFields = false,
            useMethodIndex = None,
            Seq.empty,
            Seq.empty,
            Some(Seq.empty)
          )
        ),
        Seq.empty,
        Seq.empty,
        Seq.empty,
        Seq.empty,
        List(
          ContractInheritance(TypeId("Parent0"), Seq(Ident("x"))),
          ContractInheritance(TypeId("Parent1"), Seq(Ident("x")))
        )
      )
      fastparse
        .parse(code(Keyword.`extends`.name), StatefulParser.contract(_))
        .get
        .value is contract
      parse(code(Keyword.`embeds`.name), StatefulParser.contract(_)).get.value is contract
    }

    {
      info("Contract event inheritance")
      val foo: String =
        s"""
           |Contract Foo() {
           |  event Foo(x: U256)
           |  event Foo2(x: U256)
           |
           |  pub fn foo() -> () {
           |    emit Foo(1)
           |    emit Foo2(2)
           |  }
           |}
           |""".stripMargin
      val bar: String =
        s"""
           |Contract Bar() extends Foo() {
           |  pub fn bar() -> () {}
           |}
           |$foo
           |""".stripMargin
      val extended =
        parse(bar, StatefulParser.multiContract(_)).get.value.extendedContracts()
      val barContract = extended.contracts(0)
      val fooContract = extended.contracts(1)
      fooContract.events.length is 2
      barContract.events.length is 2
    }

    {
      info("Contract constant variable inheritance")
      val foo: String =
        s"""
           |Contract Foo() {
           |  const C0 = 0
           |  const C1 = 1
           |  pub fn foo() -> () {}
           |}
           |""".stripMargin

      val bar: String =
        s"""
           |Contract Bar() extends Foo() {
           |  const C2 = 2
           |  pub fn bar() -> () {}
           |}
           |$foo
           |""".stripMargin
      val extended =
        parse(bar, StatefulParser.multiContract(_)).get.value.extendedContracts()
      val barContract = extended.contracts(0)
      val fooContract = extended.contracts(1)
      barContract.constantVars.length is 3
      fooContract.constantVars.length is 2
    }

    {
      info("Contract enum inheritance")
      val foo: String =
        s"""
           |Contract Foo() {
           |  enum FooErrorCodes {
           |    Error = 0
           |  }
           |  pub fn foo() -> () {}
           |}
           |""".stripMargin

      val bar: String =
        s"""
           |Contract Bar() extends Foo() {
           |  enum BarErrorCodes {
           |    Error = 0
           |  }
           |  pub fn bar() -> () {}
           |}
           |$foo
           |""".stripMargin
      val extended =
        parse(bar, StatefulParser.multiContract(_)).get.value.extendedContracts()
      val barContract = extended.contracts(0)
      val fooContract = extended.contracts(1)
      barContract.enums.length is 2
      fooContract.enums.length is 1
    }

    {
      info("Contract map inheritance")
      val foo: String =
        s"""
           |Contract Foo() {
           |  mapping[U256, U256] map0
           |
           |  pub fn foo() -> () {}
           |}
           |""".stripMargin

      val bar: String =
        s"""
           |Contract Bar() extends Foo() {
           |  mapping[U256, U256] map1
           |
           |  pub fn bar() -> () {}
           |}
           |$foo
           |""".stripMargin
      val extended =
        parse(bar, StatefulParser.multiContract(_)).get.value.extendedContracts()
      val barContract = extended.contracts(0)
      val fooContract = extended.contracts(1)
      barContract.maps.length is 2
      fooContract.maps.length is 1
    }

    {
      info("Contract inherit from std interface")
      val code =
        s"""
           |@std(id = #0001)
           |Interface Token {
           |  pub fn name() -> ByteVec
           |}
           |
           |Contract TokenImpl() implements Token {
           |  pub fn name() -> ByteVec {
           |    return #11
           |  }
           |}
           |""".stripMargin
      val extended =
        parse(code, StatefulParser.multiContract(_)).get.value.extendedContracts()
      val tokenInterface = extended.contracts(0).asInstanceOf[ContractInterface]
      val tokenImpl      = extended.contracts(1).asInstanceOf[Contract]
      tokenInterface.stdId is Some(Val.ByteVec(Hex.unsafe("414c50480001")))
      tokenImpl.stdInterfaceId is Some(Val.ByteVec(Hex.unsafe("414c50480001")))

      tokenInterface.funcs.forall(_.definedIn(tokenInterface.ident)) is true
      tokenImpl.funcs.forall(_.definedIn(tokenImpl.ident)) is true
    }

    {
      info("Std interface inheritance")
      def code(barAnnotation: String) =
        s"""
           |@std(id = #0001)
           |Interface Foo {
           |  pub fn foo() -> ()
           |}
           |
           |$barAnnotation
           |Interface Bar extends Foo {
           |  pub fn bar() -> ()
           |}
           |
           |Contract Impl() implements Bar {
           |  pub fn foo() -> () {}
           |  pub fn bar() -> () {}
           |}
           |""".stripMargin
      val extended0 =
        parse(code(""), StatefulParser.multiContract(_)).get.value.extendedContracts()
      val fooInterface0 = extended0.contracts(0).asInstanceOf[ContractInterface]
      val barInterface0 = extended0.contracts(1).asInstanceOf[ContractInterface]
      val implContract0 = extended0.contracts(2).asInstanceOf[Contract]
      fooInterface0.stdId is Some(Val.ByteVec(Hex.unsafe("414c50480001")))
      barInterface0.stdId is Some(Val.ByteVec(Hex.unsafe("414c50480001")))
      implContract0.stdInterfaceId is Some(Val.ByteVec(Hex.unsafe("414c50480001")))

      val extended1 = fastparse
        .parse(code("@std(id = #000101)"), StatefulParser.multiContract(_))
        .get
        .value
        .extendedContracts()
      val fooInterface1 = extended1.contracts(0).asInstanceOf[ContractInterface]
      val barInterface1 = extended1.contracts(1).asInstanceOf[ContractInterface]
      val implContract1 = extended1.contracts(2).asInstanceOf[Contract]
      fooInterface1.stdId is Some(Val.ByteVec(Hex.unsafe("414c50480001")))
      barInterface1.stdId is Some(Val.ByteVec(Hex.unsafe("414c5048000101")))
      implContract1.stdInterfaceId is Some(Val.ByteVec(Hex.unsafe("414c5048000101")))
    }
  }

  it should "test contract interface parser" in {
    {
      info("Parse interface")
      val code =
        s"""
           |Interface Child extends Parent {
           |  fn foo() -> ()
           |}
           |""".stripMargin
      parse(code, StatefulParser.interface(_)).get.value is ContractInterface(
        None,
        useMethodSelector = true,
        TypeId("Child"),
        Seq(
          FuncDef(
            Seq.empty,
            FuncId("foo", false),
            isPublic = false,
            usePreapprovedAssets = false,
            useAssetsInContract = Ast.NotUseContractAssets,
            usePayToContractOnly = false,
            useCheckExternalCaller = true,
            useUpdateFields = false,
            useMethodIndex = None,
            Seq.empty,
            Seq.empty,
            None
          )
        ),
        Seq.empty,
        Seq(InterfaceInheritance(TypeId("Parent")))
      )
    }

    {
      info("Parse std interface")
      def interface(annotations: String*): String =
        s"""
           |${annotations.mkString("\n")}
           |Interface Foo {
           |  pub fn foo() -> ()
           |}
           |""".stripMargin

      fastparse
        .parse(interface(""), StatefulParser.interface(_))
        .get
        .value
        .stdId is None
      fastparse
        .parse(interface("@std(id = #0001)"), StatefulParser.interface(_))
        .get
        .value
        .stdId is Some(Val.ByteVec(Hex.unsafe("414c50480001")))
      intercept[Compiler.Error](
        parse(interface("@unknown(updateFields = true)"), StatefulParser.interface(_))
      ).message is "Invalid annotation unknown, interface only supports these annotations: std,using"
      intercept[Compiler.Error](
        parse(
          interface("@std(id = #0001)", "@unknown(updateFields = true)"),
          StatefulParser.interface(_)
        )
      ).message is "Invalid annotation unknown, interface only supports these annotations: std,using"
      intercept[Compiler.Error](
        parse(
          interface("@std(id = #0001, updateFields = true)"),
          StatefulParser.interface(_)
        )
      ).message is "Invalid keys for @std annotation: updateFields"
      intercept[Compiler.Error](
        parse(interface("@std(id = #)"), StatefulParser.interface(_))
      ).message is "The field id of the @std annotation must be a non-empty ByteVec"
      intercept[Compiler.Error](
        parse(interface("@std(id = 0)"), StatefulParser.interface(_))
      ).message is "Expect ByteVec for id in annotation @std"
      intercept[Compiler.Error](
        parse(interface("@std(updateFields = 0)"), StatefulParser.interface(_))
      ).message is "Invalid keys for @std annotation: updateFields"
    }

    {
      info("Parse method selector annotation")
      def interface(annotations: String*): String =
        s"""
           |${annotations.mkString("\n")}
           |Interface Foo {
           |  pub fn foo() -> ()
           |}
           |""".stripMargin

      fastparse.parse(interface(""), StatefulParser.interface(_)).isSuccess is true
      val result0 = fastparse.parse(interface(), StatefulParser.interface(_)).get.value
      result0.stdId is None
      result0.useMethodSelector is true

      val result1 = fastparse
        .parse(interface("@using(methodSelector = false)"), StatefulParser.interface(_))
        .get
        .value
      result1.stdId is None
      result1.useMethodSelector is false

      val result2 = fastparse
        .parse(
          interface("@std(id = #0001)", "@using(methodSelector = true)"),
          StatefulParser.interface(_)
        )
        .get
        .value
      result2.stdId is Some(Val.ByteVec(Hex.unsafe("414c50480001")))
      result2.useMethodSelector is true

      intercept[Compiler.Error](
        parse(
          interface("@using(updateFields = true)"),
          StatefulParser.interface(_)
        )
      ).message is "Invalid keys for @using annotation: updateFields"

      intercept[Compiler.Error](
        parse(interface("@using(methodSelector = 0)"), StatefulParser.interface(_))
      ).message is "Expect Bool for methodSelector in annotation @using"
    }

    {
      info("Interface supports multiple inheritance")
      val code =
        s"""
           |Interface Child extends Parent0, Parent1 {
           |  fn foo() -> ()
           |}
           |""".stripMargin
      parse(code, StatefulParser.interface(_)).get.value.inheritances is
        Seq(
          InterfaceInheritance(TypeId("Parent0")),
          InterfaceInheritance(TypeId("Parent1"))
        )
    }

    {
      info("Contract inherits interface")
      val code =
        s"""
           |Contract Child() implements Parent {
           |  fn bar() -> () {}
           |}
           |""".stripMargin
      parse(code, StatefulParser.contract(_)).get.value is Contract(
        None,
        None,
        false,
        TypeId("Child"),
        Seq.empty,
        Seq.empty,
        Seq(
          FuncDef(
            Seq.empty,
            FuncId("bar", false),
            isPublic = false,
            usePreapprovedAssets = false,
            useAssetsInContract = Ast.NotUseContractAssets,
            usePayToContractOnly = false,
            useCheckExternalCaller = true,
            useUpdateFields = false,
            useMethodIndex = None,
            Seq.empty,
            Seq.empty,
            Some(Seq.empty)
          )
        ),
        Seq.empty,
        Seq.empty,
        Seq.empty,
        Seq.empty,
        Seq(InterfaceInheritance(TypeId("Parent")))
      )
    }

    {
      info("Contract supports extends multiple contracts")
      val code =
        s"""
           |Contract Child() extends Parent0(), Parent1() implements Parent2 {
           |  fn foo() -> () {
           |    return
           |  }
           |}
           |""".stripMargin
      parse(code, StatefulParser.contract(_)).get.value is Contract(
        None,
        None,
        false,
        TypeId("Child"),
        Seq.empty,
        Seq.empty,
        Seq(
          FuncDef(
            Seq.empty,
            FuncId("foo", false),
            isPublic = false,
            usePreapprovedAssets = false,
            useAssetsInContract = Ast.NotUseContractAssets,
            usePayToContractOnly = false,
            useCheckExternalCaller = true,
            useUpdateFields = false,
            useMethodIndex = None,
            Seq.empty,
            Seq.empty,
            Some(Seq(ReturnStmt(Seq.empty)))
          )
        ),
        Seq.empty,
        Seq.empty,
        Seq.empty,
        Seq.empty,
        Seq(
          ContractInheritance(TypeId("Parent0"), Seq.empty),
          ContractInheritance(TypeId("Parent1"), Seq.empty),
          InterfaceInheritance(TypeId("Parent2"))
        )
      )
    }

    {
      info("Contract supports multiple inheritance")
      val code =
        s"""
           |Contract Child() extends Parent0(), Parent1() implements Parent3, Parent4 {
           |  fn foo() -> () {
           |    return
           |  }
           |}
           |""".stripMargin
      parse(code, StatefulParser.contract(_)).get.value.inheritances is
        Seq(
          ContractInheritance(TypeId("Parent0"), Seq.empty),
          ContractInheritance(TypeId("Parent1"), Seq.empty),
          InterfaceInheritance(TypeId("Parent3")),
          InterfaceInheritance(TypeId("Parent4"))
        )
    }
  }

  it should "test contract parser" in {
    def fooFuncDef(isAbstract: Boolean, checkExternalCaller: Boolean = true) =
      FuncDef[StatefulContext](
        Seq.empty,
        FuncId("foo", false),
        isPublic = false,
        usePreapprovedAssets = false,
        useAssetsInContract = Ast.NotUseContractAssets,
        usePayToContractOnly = false,
        checkExternalCaller,
        useUpdateFields = false,
        useMethodIndex = None,
        Seq.empty,
        Seq.empty,
        if (isAbstract) None else Some(Seq(Ast.ReturnStmt(List())))
      )

    def barFuncDef(isAbstract: Boolean, checkExternalCaller: Boolean = true) =
      FuncDef[StatefulContext](
        Seq.empty,
        FuncId("bar", false),
        isPublic = false,
        usePreapprovedAssets = false,
        useAssetsInContract = Ast.NotUseContractAssets,
        usePayToContractOnly = false,
        checkExternalCaller,
        useUpdateFields = false,
        useMethodIndex = None,
        Seq.empty,
        Seq.empty,
        if (isAbstract) None else Some(Seq(Ast.ReturnStmt(List())))
      )

    {
      info("Parse contract")
      def contract(annotations: String*): String = {
        s"""
           |${annotations.mkString("\n")}
           |Contract Foo() {
           |  pub fn foo() -> () {}
           |}
           |""".stripMargin
      }

      fastparse
        .parse(contract(""), StatefulParser.contract(_))
        .get
        .value
        .stdIdEnabled is None
      fastparse
        .parse(contract("@std(enabled = true)"), StatefulParser.contract(_))
        .get
        .value
        .stdIdEnabled is Some(true)
      fastparse
        .parse(contract("@std(enabled = false)"), StatefulParser.contract(_))
        .get
        .value
        .stdIdEnabled is Some(false)
      intercept[Compiler.Error](
        parse(contract("@unknown(updateFields = true)"), StatefulParser.contract(_))
      ).message is "Invalid annotation unknown, contract only supports these annotations: std"
      intercept[Compiler.Error](
        parse(
          contract("@std(enabled = true, updateFields = true)"),
          StatefulParser.contract(_)
        )
      ).message is "Invalid keys for @std annotation: updateFields"
      intercept[Compiler.Error](
        parse(contract("@std(enabled = 0)"), StatefulParser.contract(_))
      ).message is "Expect Bool for enabled in annotation @std"
      intercept[Compiler.Error](
        parse(contract("@std(updateFields = 0)"), StatefulParser.contract(_))
      ).message is "Invalid keys for @std annotation: updateFields"
    }

    {
      info("Parse contract which has std annotation")
      def code(enabled: Boolean): String =
        s"""|
            |@std(id = #0001)
            |Interface Foo {
            |  pub fn foo() -> ()
            |}
            |
            |@std(enabled = $enabled)
            |Abstract Contract Bar() implements Foo {
            |  pub fn foo() -> () {}
            |}
            |
            |Contract Baz() extends Bar() {
            |}
            |""".stripMargin

      def test(enabled: Boolean) = {
        val extended =
          fastparse
            .parse(code(enabled), StatefulParser.multiContract(_))
            .get
            .value
            .extendedContracts()
        val bar = extended.contracts(1).asInstanceOf[Ast.Contract]
        bar.ident is TypeId("Bar")
        bar.isAbstract is true
        bar.stdIdEnabled is Some(enabled)
        bar.stdInterfaceId is Some(Val.ByteVec(Hex.unsafe("414c50480001")))
        bar.hasStdIdField is enabled

        val baz = extended.contracts(2).asInstanceOf[Ast.Contract]
        baz.ident is TypeId("Baz")
        baz.isAbstract is false
        baz.stdIdEnabled is Some(enabled)
        bar.stdInterfaceId is Some(Val.ByteVec(Hex.unsafe("414c50480001")))
        bar.hasStdIdField is enabled
      }

      test(false)
      test(true)
    }

    {
      info("Parse abstract contract")
      val code =
        s"""
           |Abstract Contract Foo() {
           |  fn foo() -> ()
           |  fn bar() -> () {
           |    return
           |  }
           |}
           |""".stripMargin
      parse(code, StatefulParser.contract(_)).get.value is Contract(
        None,
        None,
        true,
        TypeId("Foo"),
        Seq.empty,
        Seq.empty,
        Seq(fooFuncDef(true), barFuncDef(false)),
        Seq.empty,
        Seq.empty,
        Seq.empty,
        Seq.empty,
        Seq.empty
      )
    }

    {
      info("Parse abstract contract inheritance")
      val bar =
        s"""
           |Interface Bar {
           |  @using(checkExternalCaller = false)
           |  fn bar() -> ()
           |}
           |""".stripMargin

      val code =
        s"""
           |Abstract Contract Foo() implements Bar {
           |  @using(checkExternalCaller = false)
           |  fn foo() -> () {
           |    return
           |  }
           |}
           |$bar
           |""".stripMargin
      val extended =
        parse(code, StatefulParser.multiContract(_)).get.value.extendedContracts()
      val fooContract = extended.contracts(0)
      val annotations = Seq(
        Annotation(
          Ident(Parser.FunctionUsingAnnotation.id),
          Seq(
            AnnotationField(
              Ident(Parser.FunctionUsingAnnotation.useCheckExternalCallerKey),
              Const[StatefulContext](Val.False)
            )
          )
        )
      )
      fooContract is Contract(
        Some(true),
        None,
        true,
        TypeId("Foo"),
        Seq.empty,
        Seq.empty,
        Seq(
          barFuncDef(true, false).copy(annotations = annotations),
          fooFuncDef(false, false).copy(annotations = annotations)
        ),
        Seq.empty,
        Seq.empty,
        Seq.empty,
        Seq.empty,
        Seq(InterfaceInheritance(TypeId("Bar")))
      )
    }
  }

  trait ScriptFixture {
    val usePreapprovedAssets: Boolean
    val script: String

    val ident        = TypeId("Main")
    val templateVars = Seq(Argument(Ident("x"), Type.U256, false, false))
    def funcs[C <: StatelessContext] = Seq[FuncDef[C]](
      FuncDef(
        Seq.empty,
        FuncId("main", false),
        isPublic = true,
        usePreapprovedAssets,
        useAssetsInContract = Ast.NotUseContractAssets,
        usePayToContractOnly = false,
        useCheckExternalCaller = true,
        useUpdateFields = false,
        useMethodIndex = None,
        Seq.empty,
        Seq.empty,
        Some(Seq(Ast.ReturnStmt(List())))
      )
    )
  }

  it should "parse AssetScript" in {
    val script = s"""
                    |const A = 0
                    |struct Foo { x: U256 }
                    |enum Color { Red = 0 }
                    |AssetScript Main(x: U256) {
                    |  pub fn main(foo: Foo) -> Foo {
                    |    return foo
                    |  }
                    |}
                    |""".stripMargin

    val result = fastparse.parse(script, StatelessParser.assetScript(_)).get.value
    result._1 is AssetScript(
      TypeId("Main"),
      Seq(Argument(Ident("x"), Type.U256, false, false)),
      Seq(
        FuncDef(
          Seq.empty,
          FuncId("main", false),
          isPublic = true,
          usePreapprovedAssets = false,
          Ast.NotUseContractAssets,
          usePayToContractOnly = false,
          useCheckExternalCaller = true,
          useUpdateFields = false,
          useMethodIndex = None,
          Seq(Argument(Ident("foo"), Type.NamedType(TypeId("Foo")), false, false)),
          Seq(Type.NamedType(TypeId("Foo"))),
          Some(Seq(ReturnStmt(Seq(Variable(Ident("foo"))))))
        )
      )
    )
    result._2 is GlobalState(
      Seq(Struct(TypeId("Foo"), Seq(StructField(Ident("x"), false, Type.U256)))),
      Seq(ConstantVarDef[StatelessContext](Ident("A"), Const(Val.U256(U256.Zero)))),
      Seq(
        EnumDef[StatelessContext](
          TypeId("Color"),
          Seq(EnumField(Ident("Red"), Const(Val.U256(U256.Zero))))
        )
      )
    )
    result._1.funcs.forall(_.definedIn(TypeId("Main"))) is true
  }

  // scalastyle:off no.equal
  class TxScriptFixture(usePreapprovedAssetsOpt: Option[Boolean]) extends ScriptFixture {
    val usePreapprovedAssets = !usePreapprovedAssetsOpt.contains(false)
    val annotation = usePreapprovedAssetsOpt match {
      case Some(value) => s"@using(preapprovedAssets = $value)"
      case None        => ""
    }
    val script = s"""
                    |$annotation
                    |TxScript Main(x: U256) {
                    |  return
                    |}
                    |""".stripMargin

    val parsed = parse(script, StatefulParser.txScript(_)).get.value
    parsed is TxScript(ident, templateVars, funcs)
    parsed.funcs.forall(_.definedIn(parsed.ident)) is true
  }
  // scalastyle:on no.equal

  it should "parse explicit usePreapprovedAssets TxScript" in new TxScriptFixture(Some(true))
  it should "parse implicit usePreapprovedAssets TxScript" in new TxScriptFixture(None)
  it should "parse vanilla TxScript" in new TxScriptFixture(Some(false))

  it should "parse script fields" in {
    def script(fields: String) =
      s"""
         |TxScript Main$fields {
         |  return
         |}
         |""".stripMargin
    parse(script(""), StatefulParser.txScript(_)).isSuccess is true
    parse(script("()"), StatefulParser.txScript(_)).isSuccess is true
    parse(script("(x: U256)"), StatefulParser.txScript(_)).isSuccess is true
  }

  it should "it should allow explicit definition of main function in TxScript" in {
    {
      info("Explicit definition")
      val script =
        s"""
           |TxScript Main {
           |  fn foo() -> () {
           |    return
           |  }
           |  pub fn main() -> () {
           |    foo()
           |  }
           |}
           |""".stripMargin

      parse(script, StatefulParser.txScript(_)).get.value.funcs.map(_.name) is Seq("main", "foo")
    }

    {
      info("Return from main function")
      val script =
        s"""
           |TxScript Main {
           |  @using(preapprovedAssets = false)
           |  pub fn main() -> ([U256; 2], Bool) {
           |    return [0, 1], false
           |  }
           |}
           |""".stripMargin
      val parsed = parse(script, StatefulParser.txScript(_)).get.value
      parsed.funcs.length is 1
      parsed.funcs.head.name is "main"
      parsed.funcs.head.usePreapprovedAssets is false
      parsed.funcs.head.rtypes is Seq(Type.FixedSizeArray(Type.U256, Left(2)), Type.Bool)
    }

    {
      info("Implicit definition")
      val script =
        s"""
           |TxScript Main {
           |  foo()
           |  fn foo() -> () {
           |    return
           |  }
           |}
           |""".stripMargin
      parse(script, StatefulParser.txScript(_)).get.value.funcs.map(_.name) is Seq("main", "foo")
    }

    {
      info("No main function definition")
      val script =
        s"""
           |TxScript Main {
           |  pub fn foo() -> () {
           |    return
           |  }
           |}
           |""".stripMargin
      val error = intercept[Compiler.Error](parse(script, StatefulParser.txScript(_)))
      error.message is "Expected main statements for type `Main`"
    }

    {
      info("Conflict main function definitions")
      val script =
        s"""
           |TxScript Main {
           |  assert!(true, 0)
           |
           |  pub fn $$main() -> () {
           |    return
           |  }
           |}
           |""".stripMargin
      val error =
        intercept[Compiler.Error](parse(script.replace("$", ""), StatefulParser.txScript(_)))
      error.message is "The main function is already defined in script `Main`"
      error.position is script.indexOf("$")
    }

    {
      info("Main function cannot have parameters")
      val script =
        s"""
           |TxScript Main {
           |  pub fn $$main(v: U256) -> U256 {
           |    return v
           |  }
           |}
           |""".stripMargin
      val error =
        intercept[Compiler.Error](parse(script.replace("$", ""), StatefulParser.txScript(_)))
      error.message is "The main function cannot have parameters. Please declare the parameters as script parameters"
      error.position is script.indexOf("$")
    }
  }

  it should "fail to define Debug event" in {
    val code =
      s"""
         |Contract Foo() {
         |  event Debug(x: U256)
         |  pub fn foo() -> () {
         |    emit Debug(0)
         |  }
         |}
         |""".stripMargin
    intercept[Compiler.Error](parse(code, StatefulParser.contract(_))).message is
      "Debug is a built-in event name"
  }

  it should "parse method index annotation" in {
    def interface(index: String) =
      s"""
         |Interface Foo {
         |  @using(methodIndex = $index, preapprovedAssets = true)
         |  pub fn f1() -> ()
         |
         |  @using(methodIndex = 2)
         |  pub fn f2() -> ()
         |}
         |""".stripMargin

    val funcs = fastparse.parse(interface("1"), StatefulParser.interface(_)).get.value.funcs
    funcs(0).useMethodIndex is Some(1)
    funcs(0).usePreapprovedAssets is true
    funcs(1).useMethodIndex is Some(2)

    intercept[Compiler.Error](
      fastparse.parse(interface("false"), StatefulParser.interface(_))
    ).message is
      "Expect U256 for methodIndex in annotation @using"
    intercept[Compiler.Error](
      fastparse.parse(interface("-1"), StatefulParser.interface(_))
    ).message is
      "Expect U256 for methodIndex in annotation @using"
    fastparse.parse(interface("255"), StatefulParser.interface(_)).isSuccess is true
    intercept[Compiler.Error](
      fastparse.parse(interface("256"), StatefulParser.interface(_))
    ).message is
      "Invalid method index 256, expecting a value in the range [0, 0xff]"

    val contract =
      s"""
         |Contract Foo() {
         |  @using(methodIndex = 1, preapprovedAssets = true)
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin

    intercept[Compiler.Error](fastparse.parse(contract, StatefulParser.contract(_))).message is
      "The `methodIndex` annotation can only be used for interface functions"
  }

  it should "parse struct" in {
    {
      info("Simple struct")
      val code =
        s"""
           |struct Foo {
           |  amount: U256,
           |  mut address: Address
           |}
           |""".stripMargin
      parse(code, StatelessParser.struct(_)).get.value is Ast.Struct(
        TypeId("Foo"),
        List(
          StructField(Ident("amount"), false, Type.U256),
          StructField(Ident("address"), true, Type.Address)
        )
      )
    }

    {
      info("Nested struct")
      val code =
        s"""
           |struct Foo {
           |  amount: U256,
           |  address: Address
           |}
           |Contract Bar(foo: Foo, baz: Baz) {
           |  pub fn f() -> () {}
           |}
           |struct Baz {
           |  id: ByteVec,
           |  account: Foo,
           |  mut accounts: [Foo; 2]
           |}
           |""".stripMargin

      val result = parse(code, StatefulParser.multiContract(_)).get.value
      result.contracts.length is 1
      result.contracts.head.fields.map(_.tpe) is Seq(
        Type.NamedType(TypeId("Foo")),
        Type.NamedType(TypeId("Baz"))
      )
      result.structs.length is 2
      result.structs(0) is Ast.Struct(
        TypeId("Foo"),
        List(
          StructField(Ident("amount"), false, Type.U256),
          StructField(Ident("address"), false, Type.Address)
        )
      )
      result.structs(1) is Ast.Struct(
        TypeId("Baz"),
        List(
          StructField(Ident("id"), false, Type.ByteVec),
          StructField(
            Ident("account"),
            false,
            Type.NamedType(TypeId("Foo"))
          ),
          StructField(
            Ident("accounts"),
            true,
            Type.FixedSizeArray(Type.NamedType(TypeId("Foo")), Left(2))
          )
        )
      )
    }

    {
      info("Duplicate field definition")
      val code =
        s"""
           |struct Foo {
           |  a: U256,
           |  $$a: ByteVec
           |}
           |""".stripMargin
      val error =
        intercept[Compiler.Error](fastparse.parse(code.replace("$", ""), StatefulParser.struct(_)))
      error.message is "These struct fields are defined multiple times: a"
      error.position is code.indexOf("$")
    }
  }

  it should "parse map" in {
    def fail[T](code: String, parser: P[_] => P[T], errMsg: String) = {
      val error = intercept[Compiler.Error](fastparse.parse(code.replace("$", ""), parser))
      error.message is errMsg
      error.position is code.indexOf("$")
    }

    fastparse.parse("mapping[U256, U256] map", StatefulParser.mapDef(_)).get.value is Ast.MapDef(
      Ident("map"),
      Type.Map(Type.U256, Type.U256)
    )
    fastparse.parse("mapping[U256, Foo] map", StatefulParser.mapDef(_)).get.value is Ast.MapDef(
      Ident("map"),
      Type.Map(Type.U256, Type.NamedType(TypeId("Foo")))
    )
    fastparse.parse("mapping[U256, [U256; 2]] map", StatefulParser.mapDef(_)).get.value is
      Ast.MapDef(Ident("map"), Type.Map(Type.U256, Type.FixedSizeArray(Type.U256, Left(2))))
    fail(
      s"mapping[$$Foo, Foo] map",
      StatefulParser.mapDef(_),
      "The key type of map can only be primitive type"
    )
    fail(
      s"mapping[$$[U256; 2], U256] map",
      StatefulParser.mapDef(_),
      "The key type of map can only be primitive type"
    )

    parse(
      "map.insert!(address, 1, 0)",
      StatefulParser.statement(_)
    ).get.value is Ast.InsertToMap(
      Ident("map"),
      Seq[Expr[StatefulContext]](
        Variable(Ident("address")),
        Const(Val.U256(U256.One)),
        Const(Val.U256(U256.Zero))
      )
    )
    parse("map.remove!(address, 1)", StatefulParser.statement(_)).get.value is Ast.RemoveFromMap(
      Ident("map"),
      Seq[Expr[StatefulContext]](Variable(Ident("address")), Const(Val.U256(U256.One)))
    )

    parse("map.contains!(0)", StatefulParser.expr(_)).get.value is Ast.MapContains(
      Ident("map"),
      constantIndex(0)
    )
  }

  it should "parse struct destruction" in {
    checkParseStat(
      "let Foo { x, y } = foo",
      StructDestruction(
        TypeId("Foo"),
        Seq(
          StructFieldAlias(isMutable = false, Ident("x"), None),
          StructFieldAlias(isMutable = false, Ident("y"), None)
        ),
        Variable(Ident("foo"))
      )
    )
    checkParseStat(
      "let Foo { mut x, y } = foo",
      StructDestruction(
        TypeId("Foo"),
        Seq(
          StructFieldAlias(isMutable = true, Ident("x"), None),
          StructFieldAlias(isMutable = false, Ident("y"), None)
        ),
        Variable(Ident("foo"))
      )
    )
    checkParseStat(
      "let Foo { x: x1, y } = foo",
      StructDestruction(
        TypeId("Foo"),
        Seq(
          StructFieldAlias(isMutable = false, Ident("x"), Some(Ident("x1"))),
          StructFieldAlias(isMutable = false, Ident("y"), None)
        ),
        Variable(Ident("foo"))
      )
    )
    checkParseStat(
      "let Foo { mut x: x1, y } = foo",
      StructDestruction(
        TypeId("Foo"),
        Seq(
          StructFieldAlias(isMutable = true, Ident("x"), Some(Ident("x1"))),
          StructFieldAlias(isMutable = false, Ident("y"), None)
        ),
        Variable(Ident("foo"))
      )
    )

    val invalidStmt = s"$$let Foo { } = foo"
    val error = intercept[Compiler.Error](
      fastparse.parse(invalidStmt.replace("$", ""), StatefulParser.structDestruction(_))
    )
    error.message is "No variable declaration"
    error.position is invalidStmt.indexOf("$")
  }

  it should "handle correct const width lexer" in {
    info("typedNum")
    forAll(Gen.posNum, Gen.choose(0, 10)) { case (num, numSpaces) =>
      val spaces = s"${" " * numSpaces}"
      val str    = s"${num.toString}$spaces"
      val result = fastparse.parse(str, StatelessParser.const(_)).get.value
      result.sourceIndex.get.width is num.toString.length
    }
  }

  it should "parse global definitions" in {
    val code =
      s"""
         |const A = 1
         |const B = 2
         |const C = false
         |struct Bar { x: U256 }
         |Contract Foo() {
         |  pub fn foo() -> () {}
         |}
         |const D = A + B
         |enum Color { Red = 0 }
         |""".stripMargin
    val result = parse(code, StatefulParser.multiContract(_)).get.value
    result.contracts.map(_.name) is Seq("Foo")
    result.globalState.constants.size is 5
    result.globalState.getCalculatedConstants() is Seq(
      (Ident("A"), Val.U256(U256.One)),
      (Ident("B"), Val.U256(U256.Two)),
      (Ident("C"), Val.False),
      (Ident("D"), Val.U256(U256.unsafe(3)))
    )
    result.globalState.structs is Seq(
      Struct(TypeId("Bar"), Seq(StructField(Ident("x"), false, Type.U256)))
    )
    result.globalState.enums is Seq(
      EnumDef[StatefulContext](
        TypeId("Color"),
        Seq(EnumField(Ident("Red"), Const(Val.U256(U256.Zero))))
      )
    )
  }

  it should "set the origin contract id for constants and functions" in {
    val code =
      s"""
         |const G0 = 0
         |enum G1 { V = 0 }
         |Abstract Contract Foo() {
         |  const Foo0 = 0
         |  enum Foo1 { V = 1 }
         |  pub fn foo() -> () {}
         |  fn baz() -> ()
         |}
         |Contract Bar() extends Foo() {
         |  const Bar0 = 0
         |  enum Bar1 { V = 1 }
         |  pub fn bar() -> () {}
         |  fn baz() -> () {}
         |}
         |""".stripMargin

    val multiContract = parse(code, StatefulParser.multiContract(_)).get.value.extendedContracts()
    val globalState   = multiContract.globalState
    globalState.constantVars.foreach(_.origin is None)
    globalState.enums.foreach(_.fields.foreach(_.origin is None))

    val foo = multiContract.get(0).asInstanceOf[Contract]
    foo.isAbstract is true
    foo.selfDefinedConstants is Seq(Ident("Foo0"), Ident("Foo1.V"))
    foo.constantVars.length is 1
    foo.constantVars(0).origin is Some(TypeId("Foo"))
    foo.enums.length is 1
    foo.enums(0).fields.foreach(_.origin is Some(TypeId("Foo")))
    foo.funcs.forall(_.definedIn(foo.ident)) is true

    val bar = multiContract.get(1).asInstanceOf[Contract]
    bar.isAbstract is false
    bar.selfDefinedConstants is Seq(Ident("Bar0"), Ident("Bar1.V"))
    bar.constantVars.length is 2
    bar.constantVars(0).origin is Some(TypeId("Foo"))
    bar.constantVars(1).origin is Some(TypeId("Bar"))
    bar.enums.length is 2
    bar.enums(0).fields.foreach(_.origin is Some(TypeId("Foo")))
    bar.enums(1).fields.foreach(_.origin is Some(TypeId("Bar")))
    bar.getFuncUnsafe(FuncId("foo", false)).definedIn(foo.ident) is true
    bar.getFuncUnsafe(FuncId("bar", false)).definedIn(bar.ident) is true
    bar.getFuncUnsafe(FuncId("baz", false)).definedIn(bar.ident) is true
  }

  it should "parse negation on variable" in {
    parse("!x", StatelessParser.expr(_)).get.value is
      UnaryOp[StatelessContext](Not, Variable(Ident("x")))
    parse("-x", StatelessParser.expr(_)).get.value is
      UnaryOp[StatelessContext](Negate, Variable(Ident("x")))
  }
}

class ParseNoFileSpec extends ParserSpec(None)
class ParseFileSpec   extends ParserSpec(Some(new java.net.URI("file:///tmp")))
