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

import akka.util.ByteString

import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.{StatefulContext, StatelessContext, Val}
import org.alephium.protocol.vm.lang.ArithOperator._
import org.alephium.protocol.vm.lang.LogicalOperator._
import org.alephium.protocol.vm.lang.TestOperator._
import org.alephium.util.{AlephiumSpec, AVector, Hex, I256, U256}

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
    fastparse.parse("foo(x)", StatelessParser.expr(_)).get.value is
      CallExpr[StatelessContext](FuncId("foo", false), List(Variable(Ident("x"))))
    fastparse.parse("Foo(x)", StatelessParser.expr(_)).get.value is
      ContractConv[StatelessContext](Ast.TypeId("Foo"), Variable(Ident("x")))
    fastparse.parse("foo!(x)", StatelessParser.expr(_)).get.value is
      CallExpr[StatelessContext](FuncId("foo", true), List(Variable(Ident("x"))))
    fastparse.parse("foo(x + y) + bar!(x + y)", StatelessParser.expr(_)).get.value is
      Binop[StatelessContext](
        Add,
        CallExpr(
          FuncId("foo", false),
          List(Binop(Add, Variable(Ident("x")), Variable(Ident("y"))))
        ),
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
    fastparse.parse("foo(?)", StatefulParser.callExpr(_)).get.value is
      CallExpr(FuncId("foo", false), List(Placeholder[StatefulContext]()))
    fastparse.parse("# ++ #00", StatefulParser.expr(_)).get.value is
      Binop[StatefulContext](
        Concat,
        Const(Val.ByteVec(ByteString.empty)),
        Const(Val.ByteVec(Hex.unsafe("00")))
      )
    fastparse.parse("let bytes = #", StatefulParser.statement(_)).get.value is
      VarDef[StatefulContext](Seq((false, Ident("bytes"))), Const(Val.ByteVec(ByteString.empty)))
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
      .parse(
        "fn add(x: U256, y: U256) -> (U256, U256) { return x + y, x - y }",
        StatelessParser.func(_)
      )
      .get
      .value
    parsed0.id is Ast.FuncId("add", false)
    parsed0.isPublic is false
    parsed0.isPayable is false
    parsed0.args.size is 2
    parsed0.rtypes is Seq(Type.U256, Type.U256)

    val parsed1 = fastparse
      .parse(
        "pub payable fn add(x: U256, y: U256) -> (U256, U256) { return x + y, x - y }",
        StatelessParser.func(_)
      )
      .get
      .value
    parsed1.id is Ast.FuncId("add", false)
    parsed1.isPublic is true
    parsed1.isPayable is true
    parsed1.args.size is 2
    parsed1.rtypes is Seq(Type.U256, Type.U256)

    info("Simple return type")
    val parsed2 = fastparse
      .parse(
        "pub payable fn add(x: U256, y: U256) -> U256 { return x + y }",
        StatelessParser.func(_)
      )
      .get
      .value
    parsed2.id is Ast.FuncId("add", false)
    parsed2.isPublic is true
    parsed2.isPayable is true
    parsed2.args.size is 2
    parsed2.rtypes is Seq(Type.U256)
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
          Argument(Ident("a"), Type.FixedSizeArray(Type.Bool, 2), isMutable = true),
          Argument(
            Ident("b"),
            Type.FixedSizeArray(Type.FixedSizeArray(Type.Address, 3), 2),
            isMutable = false
          ),
          Argument(
            Ident("c"),
            Type.FixedSizeArray(Type.Contract.local(TypeId("Foo"), Ident("c")), 4),
            isMutable = false
          ),
          Argument(Ident("d"), Type.U256, isMutable = false)
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
        Seq((false, Ast.Ident("a")), (false, Ast.Ident("b"))),
        Ast.CallExpr(Ast.FuncId("foo", false), Seq.empty)
      ),
      "let (a, mut b) = foo()" -> Ast.VarDef(
        Seq((false, Ast.Ident("a")), (true, Ast.Ident("b"))),
        Ast.CallExpr(Ast.FuncId("foo", false), Seq.empty)
      ),
      "let (mut a, mut b) = foo()" -> Ast.VarDef(
        Seq((true, Ast.Ident("a")), (true, Ast.Ident("b"))),
        Ast.CallExpr(Ast.FuncId("foo", false), Seq.empty)
      )
    )
    states.foreach { case (code, ast) =>
      checkParseStat(code, ast)
    }
  }

  it should "parse array expression" in {
    val exprs: List[(String, Ast.Expr[StatelessContext])] = List(
      "a[0][?]" -> Ast.ArrayElement(
        Ast.ArrayElement(Variable(Ast.Ident("a")), constantIndex(0)),
        Ast.Placeholder()
      ),
      "a[0][1]" -> Ast.ArrayElement(
        Ast.ArrayElement(Variable(Ast.Ident("a")), constantIndex(0)),
        constantIndex(1)
      ),
      "!a[0][1]" -> Ast.UnaryOp(
        LogicalOperator.Not,
        Ast.ArrayElement(
          Ast.ArrayElement(Variable(Ast.Ident("a")), constantIndex(0)),
          constantIndex(1)
        )
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
        Ast.ArrayElement(Ast.Variable(Ast.Ident("b")), constantIndex(0))
      ),
      "a[?][0] = ?" -> Assign(
        Seq(AssignmentArrayElementTarget(Ident("a"), Seq(Ast.Placeholder(), constantIndex(0)))),
        Ast.Placeholder()
      ),
      "a, b = foo()" -> Assign(
        Seq(AssignmentSimpleTarget(Ident("a")), AssignmentSimpleTarget(Ident("b"))),
        CallExpr(FuncId("foo", false), Seq.empty)
      ),
      "a, b[?] = foo()" -> Assign(
        Seq(
          AssignmentSimpleTarget(Ident("a")),
          AssignmentArrayElementTarget(Ident("b"), Seq(Placeholder()))
        ),
        CallExpr(FuncId("foo", false), Seq.empty)
      )
    )

    stats.foreach { case (str, ast) =>
      checkParseStat(str, ast)
    }
  }

  it should "parse loop" in {
    checkParseStat(
      "loop(0, 4, 1, x[?] = ?)",
      Ast.Loop[StatelessContext](
        0,
        4,
        1,
        Ast.Assign(
          Seq(
            Ast.AssignmentArrayElementTarget(
              Ast.Ident("x"),
              Seq(Ast.Placeholder[StatelessContext]())
            )
          ),
          Ast.Placeholder[StatelessContext]()
        )
      )
    )
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

  it should "parse contract inheritance" in {
    {
      info("Simple contract inheritance")
      val code =
        s"""
         |TxContract Child<a: U256>(x: U256, y: U256) extends Parent0<a>(x), Parent1(x) {
         |  fn foo() -> () {
         |  }
         |}
         |""".stripMargin

      fastparse.parse(code, StatefulParser.contract(_)).get.value is TxContract(
        TypeId("Child"),
        Seq(Argument(Ident("a"), Type.U256, false)),
        Seq(Argument(Ident("x"), Type.U256, false), Argument(Ident("y"), Type.U256, false)),
        Seq(FuncDef(FuncId("foo", false), false, false, Seq.empty, Seq.empty, Seq.empty)),
        Seq.empty,
        List(
          ContractInheritance(TypeId("Parent0"), Seq(Ident("a")), Seq(Ident("x"))),
          ContractInheritance(TypeId("Parent1"), Seq.empty, Seq(Ident("x")))
        )
      )
    }

    {
      info("Contract event inheritance")
      val foo: String =
        s"""
             |TxContract Foo() {
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
             |TxContract Bar() extends Foo() {
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
        Seq(FuncDef(FuncId("foo", false), false, false, Seq.empty, Seq.empty, Seq.empty)),
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
      error.message is "Interface only supports single inheritance: Parent0,Parent1"
    }

    {
      info("Contract inherits interface")
      val code =
        s"""
           |TxContract Child() extends Parent {
           |  fn foo() -> () {
           |    return
           |  }
           |}
           |""".stripMargin
      fastparse.parse(code, StatefulParser.contract(_)).get.value is TxContract(
        TypeId("Child"),
        Seq.empty,
        Seq.empty,
        Seq(
          FuncDef(
            FuncId("foo", false),
            false,
            false,
            Seq.empty,
            Seq.empty,
            Seq(ReturnStmt(Seq.empty))
          )
        ),
        Seq.empty,
        Seq(InterfaceInheritance(TypeId("Parent")))
      )
    }
  }

  trait ScriptFixture {
    def script(tpe: String) =
      s"""
         |$tpe Main<x: U256> {
         |  pub fn main() -> () {
         |  }
         |}
         |""".stripMargin

    val ident        = TypeId("Main")
    val templateVars = Seq(Argument(Ident("x"), Type.U256, false))
    def funcs[C <: StatelessContext] = Seq[FuncDef[C]](
      FuncDef(
        FuncId("main", false),
        true,
        false,
        Seq.empty,
        Seq.empty,
        Seq.empty
      )
    )
  }

  it should "parse Script" in new ScriptFixture {
    fastparse.parse(script("AssetScript"), StatelessParser.assetScript(_)).get.value is
      AssetScript(ident, templateVars, funcs)
    fastparse.parse(script("TxScript"), StatefulParser.txScript(_)).get.value is
      TxScript(ident, templateVars, funcs)
  }
}
