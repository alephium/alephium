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

import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.{StatefulContext, StatelessContext, Val}
import org.alephium.ralph.ArithOperator._
import org.alephium.ralph.LogicalOperator._
import org.alephium.ralph.TestOperator._
import org.alephium.util.{AlephiumSpec, AVector, Hex, I256, U256}

// scalastyle:off file.size.limit
class ParserSpec extends AlephiumSpec {
  import Ast._

  it should "parse exprs" in {
    fastparse.parse("x + y", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](Add, Variable(Ident("x")), Variable(Ident("y")))
    fastparse.parse("x >= y", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](Ge, Variable(Ident("x")), Variable(Ident("y")))
    fastparse.parse("(x + y)", StatelessParser.expr(_)).get.value is
      ParenExpr[StatelessContext](Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))
    fastparse.parse("(x + y) + (x + y)", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y")))),
        ParenExpr(Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))
      )
    fastparse.parse("x * y ** z + u", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        Binop(Mul, Variable(Ident("x")), Binop(Exp, Variable(Ident("y")), Variable(Ident("z")))),
        Variable(Ident("u"))
      )
    fastparse.parse("x / y |**| z + u", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        Binop(Div, Variable(Ident("x")), Binop(ModExp, Variable(Ident("y")), Variable(Ident("z")))),
        Variable(Ident("u"))
      )
    fastparse.parse("x + y * z + u", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        Binop(Add, Variable(Ident("x")), Binop(Mul, Variable(Ident("y")), Variable(Ident("z")))),
        Variable(Ident("u"))
      )
    fastparse.parse("x < y <= y < z", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](Lt, Variable(Ident("x")), Variable(Ident("y")))
    fastparse.parse("x && y || z", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Or,
        Binop(And, Variable(Ident("x")), Variable(Ident("y"))),
        Variable(Ident("z"))
      )
    fastparse.parse("foo(ErrorCodes.Error)", StatefulParser.expr(_)).get.value is
      CallExpr[StatefulContext](
        FuncId("foo", false),
        Seq.empty,
        Seq(EnumFieldSelector(TypeId("ErrorCodes"), Ident("Error")))
      )
  }

  it should "parse function" in {
    info("Function")
    fastparse.parse("foo(x)", StatelessParser.expr(_)).get.value is
      CallExpr[StatelessContext](FuncId("foo", false), Seq.empty, List(Variable(Ident("x"))))
    fastparse.parse("Foo(x)", StatelessParser.expr(_)).get.value is
      ContractConv[StatelessContext](Ast.TypeId("Foo"), Variable(Ident("x")))
    fastparse.parse("foo!(x)", StatelessParser.expr(_)).get.value is
      CallExpr[StatelessContext](FuncId("foo", true), Seq.empty, List(Variable(Ident("x"))))
    fastparse.parse("foo(x + y) + bar!(x + y)", StatelessParser.expr(_)).get.value is
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

    info("Braces syntax")
    fastparse.parse("{ x -> ALPH: 1 alph }", StatelessParser.approveAssets(_)).isSuccess is true
    fastparse.parse("{ x -> tokenId: 2 }", StatelessParser.approveAssets(_)).isSuccess is true
    fastparse
      .parse("{ x -> ALPH: 1 alph, tokenId: 2; y -> ALPH: 3 }", StatelessParser.approveAssets(_))
      .isSuccess is true

    info("Contract call")
    fastparse.parse("x.bar(x)", StatefulParser.contractCallExpr(_)).get.value is
      ContractCallExpr(
        Variable(Ident("x")),
        FuncId("bar", false),
        Seq.empty,
        List(Variable(Ident("x")))
      )
    fastparse
      .parse("Foo(x).bar{ z -> ALPH: 1 }(x)", StatefulParser.contractCallExpr(_))
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
    intercept[Compiler.Error](
      fastparse.parse("Foo(x).bar{ z -> }(x)", StatefulParser.contractCallExpr(_))
    ).message is "Empty asset for address: Variable(Ident(z))"
  }

  it should "parse ByteVec" in {
    fastparse.parse("# ++ #00", StatefulParser.expr(_)).get.value is
      Binop[StatefulContext](
        Concat,
        Const(Val.ByteVec(ByteString.empty)),
        Const(Val.ByteVec(Hex.unsafe("00")))
      )
    fastparse.parse("let bytes = #", StatefulParser.statement(_)).get.value is
      VarDef[StatefulContext](
        Seq(Ast.NamedVar(false, Ident("bytes"))),
        Const(Val.ByteVec(ByteString.empty))
      )
    fastparse.parse("ALPH", StatefulParser.expr(_)).get.value is ALPHTokenId[StatefulContext]()
  }

  it should "parse return" in {
    fastparse.parse("return x, y", StatelessParser.ret(_)).isSuccess is true
    fastparse.parse("return x + y", StatelessParser.ret(_)).isSuccess is true
    fastparse.parse("return (x + y)", StatelessParser.ret(_)).isSuccess is true
    intercept[Compiler.Error](fastparse.parse("return return", StatelessParser.ret(_))).message is
      "Consecutive return statements are not allowed"
  }

  it should "parse statements" in {
    fastparse.parse("let x = 1", StatelessParser.statement(_)).isSuccess is true
    fastparse.parse("x = 1", StatelessParser.statement(_)).isSuccess is true
    fastparse.parse("x = true", StatelessParser.statement(_)).isSuccess is true
    fastparse.parse("ConstantVar = 0", StatefulParser.statement(_)).isSuccess is false
    fastparse.parse("ErrorCodes.Error = 0", StatefulParser.statement(_)).isSuccess is false
    fastparse.parse("add(x, y)", StatelessParser.statement(_)).isSuccess is true
    fastparse.parse("foo.add(x, y)", StatefulParser.statement(_)).isSuccess is true
    fastparse.parse("Foo(x).add(x, y)", StatefulParser.statement(_)).isSuccess is true
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
  }

  it should "parse debug statements" in {
    fastparse.parse(s"$${a}", StatelessParser.stringInterpolator(_)).get.value is
      Variable[StatelessContext](Ident("a"))
    fastparse.parse(s"$${ x + y }", StatelessParser.stringInterpolator(_)).get.value is
      Binop[StatelessContext](Add, Variable(Ident("x")), Variable(Ident("y")))

    fastparse.parse(s"emit Debug(``)", StatelessParser.debug(_)).get.value is
      Ast.Debug[StatelessContext](AVector(Val.ByteVec(ByteString.empty)), Seq.empty)
    fastparse.parse(s"emit Debug(`$${a}`)", StatelessParser.debug(_)).get.value is
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

    val error = intercept[Compiler.Error](fastparse.parse("if (cond0) 0", StatelessParser.expr(_)))
    error.message is "If else expressions should be terminated with an else branch"
  }

  it should "parse annotations" in {
    fastparse.parse("@using(x = true, y = false)", StatefulParser.annotation(_)).isSuccess is true
  }

  it should "parse functions" in {
    val parsed0 = fastparse
      .parse(
        "fn add(x: U256, y: U256) -> (U256, U256) { return x + y, x - y }",
        StatelessParser.func(_)
      )
      .get
      .value
    parsed0.id is Ast.FuncId("add", false)
    parsed0.isPublic is false
    parsed0.usePreapprovedAssets is false
    parsed0.useAssetsInContract is false
    parsed0.args.size is 2
    parsed0.rtypes is Seq(Type.U256, Type.U256)

    val parsed1 = fastparse
      .parse(
        """@using(preapprovedAssets = true, updateFields = false)
          |pub fn add(x: U256, y: U256) -> (U256, U256) { return x + y, x - y }
          |""".stripMargin,
        StatelessParser.func(_)
      )
      .get
      .value
    parsed1.id is Ast.FuncId("add", false)
    parsed1.isPublic is true
    parsed1.usePreapprovedAssets is true
    parsed1.useAssetsInContract is false
    parsed1.useCheckExternalCaller is true
    parsed1.useUpdateFields is false
    parsed1.args.size is 2
    parsed1.rtypes is Seq(Type.U256, Type.U256)

    info("Simple return type")
    val parsed2 = fastparse
      .parse(
        """@using(preapprovedAssets = true, assetsInContract = true)
          |pub fn add(x: U256, y: U256) -> U256 { return x + y }""".stripMargin,
        StatelessParser.func(_)
      )
      .get
      .value
    parsed2.id is Ast.FuncId("add", false)
    parsed2.isPublic is true
    parsed2.usePreapprovedAssets is true
    parsed2.useAssetsInContract is true
    parsed2.useCheckExternalCaller is true
    parsed2.useUpdateFields is false
    parsed2.args.size is 2
    parsed2.rtypes is Seq(Type.U256)

    info("More use annotation")
    val parsed3 = fastparse
      .parse(
        """@using(assetsInContract = true, updateFields = true)
          |pub fn add(x: U256, y: U256) -> U256 { return x + y }""".stripMargin,
        StatelessParser.func(_)
      )
      .get
      .value
    parsed3.usePreapprovedAssets is false
    parsed3.useAssetsInContract is true
    parsed3.useCheckExternalCaller is true
    parsed3.useUpdateFields is true
  }

  it should "parser contract initial states" in {
    val bytes   = Hash.generate
    val address = Address.p2pkh(PublicKey.generate)
    val stateRaw =
      s"[1, 2i, true, @${address.toBase58}, #${bytes.toHexString}, [[1, 2], [1, 2]], [[1, 2]; 2]]"
    val expected =
      Seq[Val](
        Val.U256(U256.One),
        Val.I256(I256.Two),
        Val.True,
        Val.Address(address.lockupScript),
        Val.ByteVec.from(bytes),
        Val.U256(U256.One),
        Val.U256(U256.Two),
        Val.U256(U256.One),
        Val.U256(U256.Two),
        Val.U256(U256.One),
        Val.U256(U256.Two),
        Val.U256(U256.One),
        Val.U256(U256.Two)
      )
    fastparse.parse(stateRaw, StatefulParser.state(_)).get.value.map(_.v) is expected
    Compiler.compileState(stateRaw).rightValue is AVector.from(expected)
  }

  it should "parse bytes and address" in {
    val hash    = Hash.random
    val address = Address.p2pkh(PublicKey.generate)
    fastparse
      .parse(
        s"foo.foo(#${hash.toHexString}, #${hash.toHexString}, @${address.toBase58})",
        StatefulParser.contractCall(_)
      )
      .get
      .value is a[ContractCall]
  }

  it should "parse array types" in {
    def check(str: String, arguments: Seq[Argument]) = {
      fastparse.parse(str, StatelessParser.funParams(_)).get.value is arguments
    }

    val funcArgs = List(
      "(mut a: [Bool; 2], b: [[Address; 3]; 2], c: [Foo; 4], d: U256)" ->
        Seq(
          Argument(
            Ident("a"),
            Type.FixedSizeArray(Type.Bool, 2),
            isMutable = true,
            isUnused = false
          ),
          Argument(
            Ident("b"),
            Type.FixedSizeArray(Type.FixedSizeArray(Type.Address, 3), 2),
            isMutable = false,
            isUnused = false
          ),
          Argument(
            Ident("c"),
            Type.FixedSizeArray(Type.Contract.local(TypeId("Foo"), Ident("c")), 4),
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
    fastparse.parse(str, StatelessParser.expr(_)).get.value is expr
  }

  def checkParseStat(str: String, stat: Ast.Statement[StatelessContext]) = {
    fastparse.parse(str, StatelessParser.statement(_)).get.value is stat
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
        .ArrayElement(Variable(Ast.Ident("a")), Seq(constantIndex(0), constantIndex(1))),
      "a[i]" -> Ast.ArrayElement(Variable(Ast.Ident("a")), Seq(Variable(Ast.Ident("i")))),
      "a[foo()]" -> Ast
        .ArrayElement(
          Variable(Ast.Ident("a")),
          Seq(CallExpr(FuncId("foo", false), Seq.empty, Seq.empty))
        ),
      "a[i + 1]" -> Ast.ArrayElement(
        Variable(Ast.Ident("a")),
        Seq(Binop(ArithOperator.Add, Variable(Ast.Ident("i")), Const(Val.U256(U256.unsafe(1)))))
      ),
      "!a[0][1]" -> Ast.UnaryOp(
        LogicalOperator.Not,
        Ast.ArrayElement(Variable(Ast.Ident("a")), Seq(constantIndex(0), constantIndex(1)))
      ),
      "[a, a]" -> Ast.CreateArrayExpr(Seq(Variable(Ast.Ident("a")), Variable(Ast.Ident("a")))),
      "[a; 2]" -> Ast.CreateArrayExpr(Seq(Variable(Ast.Ident("a")), Variable(Ast.Ident("a")))),
      "[[1, 1], [1, 1]]" -> Ast.CreateArrayExpr(
        Seq.fill(2)(Ast.CreateArrayExpr(Seq.fill(2)(Ast.Const(Val.U256(U256.unsafe(1))))))
      ),
      "[[1; 2]; 2]" -> Ast.CreateArrayExpr(
        Seq.fill(2)(Ast.CreateArrayExpr(Seq.fill(2)(Ast.Const(Val.U256(U256.unsafe(1))))))
      )
    )

    exprs.foreach { case (str, expr) =>
      checkParseExpr(str, expr)
    }
  }

  it should "parse assign statement" in {
    val stats: List[(String, Ast.Statement[StatelessContext])] = List(
      "a[0] = b" -> Assign(
        Seq(AssignmentArrayElementTarget(Ident("a"), Seq(constantIndex(0)))),
        Ast.Variable(Ast.Ident("b"))
      ),
      "a[0][1] = b[0]" -> Assign(
        Seq(AssignmentArrayElementTarget(Ident("a"), Seq(constantIndex(0), constantIndex(1)))),
        Ast.ArrayElement(Ast.Variable(Ast.Ident("b")), Seq(constantIndex(0)))
      ),
      "a, b = foo()" -> Assign(
        Seq(AssignmentSimpleTarget(Ident("a")), AssignmentSimpleTarget(Ident("b"))),
        CallExpr(FuncId("foo", false), Seq.empty, Seq.empty)
      ),
      "a[i] = b" -> Assign(
        Seq(AssignmentArrayElementTarget(Ident("a"), Seq(Variable(Ident("i"))))),
        Ast.Variable(Ast.Ident("b"))
      ),
      "a[foo()] = b" -> Assign(
        Seq(
          AssignmentArrayElementTarget(
            Ident("a"),
            Seq(CallExpr(FuncId("foo", false), Seq.empty, Seq.empty))
          )
        ),
        Ast.Variable(Ast.Ident("b"))
      ),
      "a[i + 1] = b" -> Assign(
        Seq(
          AssignmentArrayElementTarget(
            Ident("a"),
            Seq(Binop(ArithOperator.Add, Variable(Ident("i")), Const(Val.U256(U256.unsafe(1)))))
          )
        ),
        Ast.Variable(Ast.Ident("b"))
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
      fastparse.parse(eventRaw, StatefulParser.eventDef(_)).get.value is EventDef(
        TypeId("Event"),
        Seq()
      )
    }

    {
      info("fields of primitive types")

      val eventRaw = "event Transfer(from: Address, to: Address, amount: U256)"
      fastparse.parse(eventRaw, StatefulParser.eventDef(_)).get.value is EventDef(
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
      fastparse.parse(eventRaw, StatefulParser.eventDef(_)).get.value is EventDef(
        TypeId("Participants"),
        Seq(
          EventField(Ident("addresses"), Type.FixedSizeArray(Type.Address, 3))
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
    val definitions = Seq[(String, Val)](
      ("const C = true", Val.True),
      ("const C = 1", Val.U256(U256.One)),
      ("const C = 1i", Val.I256(I256.One)),
      ("const C = #11", Val.ByteVec(Hex.unsafe("11"))),
      (s"const C = @${address.toBase58}", Val.Address(address.lockupScript))
    )
    definitions.foreach { definition =>
      val constantVar = fastparse.parse(definition._1, StatefulParser.constantVarDef(_)).get.value
      constantVar.ident.name is "C"
      constantVar.value is definition._2
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
      fastparse.parse(definition, StatefulParser.enumDef(_)).isSuccess is false
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
      val error = intercept[Compiler.Error](fastparse.parse(definition, StatefulParser.enumDef(_)))
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
      val error = intercept[Compiler.Error](fastparse.parse(definition, StatefulParser.enumDef(_)))
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
      val error = intercept[Compiler.Error](fastparse.parse(definition, StatefulParser.enumDef(_)))
      error.message is "These enum fields are defined multiple times: Error"
    }

    {
      info("Enum definition")
      val definition =
        s"""
           |enum ErrorCodes {
           |  Error0 = 0
           |  Error1 = 1
           |}
           |""".stripMargin
      fastparse.parse(definition, StatefulParser.enumDef(_)).get.value is EnumDef(
        TypeId("ErrorCodes"),
        Seq(
          EnumField(Ident("Error0"), Val.U256(U256.Zero)),
          EnumField(Ident("Error1"), Val.U256(U256.One))
        )
      )
    }
  }

  it should "parse contract inheritance" in {
    {
      info("Simple contract inheritance")
      val code =
        s"""
           |Contract Child(x: U256, y: U256) extends Parent0(x), Parent1(x) {
           |  fn foo() -> () {
           |  }
           |}
           |""".stripMargin

      fastparse.parse(code, StatefulParser.contract(_)).get.value is Contract(
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
            useAssetsInContract = false,
            useCheckExternalCaller = true,
            useUpdateFields = false,
            Seq.empty,
            Seq.empty,
            Some(Seq.empty)
          )
        ),
        Seq.empty,
        Seq.empty,
        Seq.empty,
        List(
          ContractInheritance(TypeId("Parent0"), Seq(Ident("x"))),
          ContractInheritance(TypeId("Parent1"), Seq(Ident("x")))
        )
      )
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
        fastparse.parse(bar, StatefulParser.multiContract(_)).get.value.extendedContracts()
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
        fastparse.parse(bar, StatefulParser.multiContract(_)).get.value.extendedContracts()
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
        fastparse.parse(bar, StatefulParser.multiContract(_)).get.value.extendedContracts()
      val barContract = extended.contracts(0)
      val fooContract = extended.contracts(1)
      barContract.enums.length is 2
      fooContract.enums.length is 1
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
      fastparse.parse(code, StatefulParser.interface(_)).get.value is ContractInterface(
        TypeId("Child"),
        Seq(
          FuncDef(
            Seq.empty,
            FuncId("foo", false),
            isPublic = false,
            usePreapprovedAssets = false,
            useAssetsInContract = false,
            useCheckExternalCaller = true,
            useUpdateFields = false,
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
      info("Interface supports single inheritance")
      val code =
        s"""
           |Interface Child extends Parent0, Parent1 {
           |  fn foo() -> ()
           |}
           |""".stripMargin
      val error = intercept[Compiler.Error](fastparse.parse(code, StatefulParser.interface(_)))
      error.message is "Interface only supports single inheritance: Parent0, Parent1"
    }

    {
      info("Contract inherits interface")
      val code =
        s"""
           |Contract Child() implements Parent {
           |  fn bar() -> () {}
           |}
           |""".stripMargin
      fastparse.parse(code, StatefulParser.contract(_)).get.value is Contract(
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
            useAssetsInContract = false,
            useCheckExternalCaller = true,
            useUpdateFields = false,
            Seq.empty,
            Seq.empty,
            Some(Seq.empty)
          )
        ),
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
      fastparse.parse(code, StatefulParser.contract(_)).get.value is Contract(
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
            useAssetsInContract = false,
            useCheckExternalCaller = true,
            useUpdateFields = false,
            Seq.empty,
            Seq.empty,
            Some(Seq(ReturnStmt(Seq.empty)))
          )
        ),
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
      info("Contract can only implement single interface")
      val code =
        s"""
           |Contract Child() extends Parent0(), Parent1() implements Parent3, Parent4 {
           |  fn foo() -> () {
           |    return
           |  }
           |}
           |""".stripMargin
      val error = intercept[Compiler.Error](fastparse.parse(code, StatefulParser.contract(_)))
      error.message is "Contract only supports implementing single interface: Parent3, Parent4"
    }
  }

  it should "test abstract contract parser" in {
    def fooFuncDef(isAbstract: Boolean, checkExternalCaller: Boolean = true) =
      FuncDef[StatefulContext](
        Seq.empty,
        FuncId("foo", false),
        isPublic = false,
        usePreapprovedAssets = false,
        useAssetsInContract = false,
        checkExternalCaller,
        useUpdateFields = false,
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
        useAssetsInContract = false,
        checkExternalCaller,
        useUpdateFields = false,
        Seq.empty,
        Seq.empty,
        if (isAbstract) None else Some(Seq(Ast.ReturnStmt(List())))
      )

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
      fastparse.parse(code, StatefulParser.contract(_)).get.value is Contract(
        true,
        TypeId("Foo"),
        Seq.empty,
        Seq.empty,
        Seq(fooFuncDef(true), barFuncDef(false)),
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
        fastparse.parse(code, StatefulParser.multiContract(_)).get.value.extendedContracts()
      val fooContract = extended.contracts(0)
      val annotations = Seq(
        Annotation(
          Ident(Parser.usingAnnotationId),
          Seq(AnnotationField(Ident(Parser.useCheckExternalCallerKey), Val.False))
        )
      )
      fooContract is Contract(
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
        useAssetsInContract = false,
        useCheckExternalCaller = true,
        useUpdateFields = false,
        Seq.empty,
        Seq.empty,
        Some(Seq(Ast.ReturnStmt(List())))
      )
    )
  }

  it should "parse AssetScript" in new ScriptFixture {
    val usePreapprovedAssets = false
    val script = s"""
                    |AssetScript Main(x: U256) {
                    |  pub fn main() -> () {
                    |    return
                    |  }
                    |}
                    |""".stripMargin

    fastparse.parse(script, StatelessParser.assetScript(_)).get.value is
      AssetScript(ident, templateVars, funcs)
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

    fastparse.parse(script, StatefulParser.txScript(_)).get.value is TxScript(
      ident,
      templateVars,
      funcs
    )
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
    fastparse.parse(script(""), StatefulParser.txScript(_)).isSuccess is true
    fastparse.parse(script("()"), StatefulParser.txScript(_)).isSuccess is true
    fastparse.parse(script("(x: U256)"), StatefulParser.txScript(_)).isSuccess is true
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
    intercept[Compiler.Error](fastparse.parse(code, StatefulParser.contract(_))).message is
      "Debug is a built-in event name"
  }
}
