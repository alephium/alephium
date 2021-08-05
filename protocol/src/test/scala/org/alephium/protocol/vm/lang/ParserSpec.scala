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

import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.protocol.vm.{StatefulContext, StatelessContext, Val}
import org.alephium.protocol.vm.lang.ArithOperator._
import org.alephium.protocol.vm.lang.LogicalOperator._
import org.alephium.protocol.vm.lang.TestOperator._
import org.alephium.util.{AlephiumSpec, AVector, I256, U256}

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
  }

  it should "parser contract initial states" in {
    val bytes    = Hash.generate
    val address  = Address.p2pkh(NetworkType.Mainnet, PublicKey.generate)
    val stateRaw = s"[1, 2i, true, @${address.toBase58}, #${bytes.toHexString}]"
    val expected =
      Seq[Val](
        Val.U256(U256.One),
        Val.I256(I256.Two),
        Val.True,
        Val.Address(address.lockupScript),
        Val.ByteVec.from(bytes)
      )
    fastparse.parse(stateRaw, StatefulParser.state(_)).get.value.map(_.v) is expected
    Compiler.compileState(stateRaw).rightValue is AVector.from(expected)
  }
}
