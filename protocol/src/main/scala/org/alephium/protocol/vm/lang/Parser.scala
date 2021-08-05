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

import fastparse._

import org.alephium.protocol.vm.{StatefulContext, StatelessContext}

// scalastyle:off number.of.methods
@SuppressWarnings(
  Array(
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
abstract class Parser[Ctx <: StatelessContext] {
  implicit val whitespace: P[_] => P[Unit] = { implicit ctx: P[_] => Lexer.emptyChars(ctx) }

  def const[_: P]: P[Ast.Const[Ctx]] =
    P(Lexer.typedNum | Lexer.bool | Lexer.bytes | Lexer.address).map(Ast.Const.apply[Ctx])
  def variable[_: P]: P[Ast.Variable[Ctx]] = P(Lexer.ident).map(Ast.Variable.apply[Ctx])
  def callAbs[_: P]: P[(Ast.FuncId, Seq[Ast.Expr[Ctx]])] =
    P(Lexer.funcId ~ "(" ~ expr.rep(0, ",") ~ ")")
  def callExpr[_: P]: P[Ast.CallExpr[Ctx]] =
    callAbs.map { case (funcId, expr) => Ast.CallExpr(funcId, expr) }
  def contractConv[_: P]: P[Ast.ContractConv[Ctx]] =
    P(Lexer.typeId ~ "(" ~ expr ~ ")").map { case (typeId, expr) => Ast.ContractConv(typeId, expr) }

  def chain[_: P](p: => P[Ast.Expr[Ctx]], op: => P[Operator]): P[Ast.Expr[Ctx]] =
    P(p ~ (op ~ p).rep).map { case (lhs, rhs) =>
      rhs.foldLeft(lhs) { case (acc, (op, right)) =>
        Ast.Binop(op, acc, right)
      }
    }

  // Optimize chained comparisons
  def expr[_: P]: P[Ast.Expr[Ctx]]         = P(chain(andExpr, Lexer.opOr))
  def andExpr[_: P]: P[Ast.Expr[Ctx]]      = P(chain(relationExpr, Lexer.opAnd))
  def relationExpr[_: P]: P[Ast.Expr[Ctx]] = P(comp | arithExpr5)
  def comp[_: P]: P[Ast.Expr[Ctx]] =
    P(arithExpr5 ~ comparison ~ arithExpr5).map { case (lhs, op, rhs) => Ast.Binop(op, lhs, rhs) }
  def comparison[_: P]: P[TestOperator] =
    P(Lexer.opEq | Lexer.opNe | Lexer.opLe | Lexer.opLt | Lexer.opGe | Lexer.opGt)
  def arithExpr5[_: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr4, Lexer.opBitOr))
  def arithExpr4[_: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr3, Lexer.opXor))
  def arithExpr3[_: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr2, Lexer.opBitAnd))
  def arithExpr2[_: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr1, Lexer.opSHL | Lexer.opSHR))
  def arithExpr1[_: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr0, Lexer.opAdd | Lexer.opSub | Lexer.opModAdd | Lexer.opModSub))
  def arithExpr0[_: P]: P[Ast.Expr[Ctx]] =
    P(chain(unaryExpr, Lexer.opMul | Lexer.opDiv | Lexer.opMod | Lexer.opModMul))
  def unaryExpr[_: P]: P[Ast.Expr[Ctx]] =
    P(atom | (Lexer.opNot ~ atom).map { case (op, expr) => Ast.UnaryOp.apply[Ctx](op, expr) })
  def atom[_: P]: P[Ast.Expr[Ctx]]

  def parenExpr[_: P]: P[Ast.ParenExpr[Ctx]] = P("(" ~ expr ~ ")").map(Ast.ParenExpr.apply[Ctx])

  def ret[_: P]: P[Ast.ReturnStmt[Ctx]] =
    P(Lexer.keyword("return") ~/ expr.rep(0, ",")).map(Ast.ReturnStmt.apply[Ctx])

  def varDef[_: P]: P[Ast.VarDef[Ctx]] =
    P(Lexer.keyword("let") ~/ Lexer.mut ~ Lexer.ident ~ "=" ~ expr).map {
      case (isMutable, ident, expr) => Ast.VarDef(isMutable, ident, expr)
    }
  def assign[_: P]: P[Ast.Assign[Ctx]] =
    P(Lexer.ident ~ "=" ~ expr).map { case (ident, expr) =>
      Ast.Assign(ident, expr)
    }

  def funcArgument[_: P]: P[Ast.Argument] =
    P(Lexer.mut ~ Lexer.ident ~ ":" ~ Lexer.typeId).map { case (isMutable, ident, typeId) =>
      val tpe = Lexer.primTpes.getOrElse(typeId.name, Type.Contract.local(typeId, ident))
      Ast.Argument(ident, tpe, isMutable)
    }
  def funParams[_: P]: P[Seq[Ast.Argument]] = P("(" ~ funcArgument.rep(0, ",") ~ ")")
  def returnType[_: P]: P[Seq[Type]] =
    P("->" ~ "(" ~ Lexer.typeId.rep(0, ",") ~ ")").map {
      _.map(typeId => Lexer.primTpes.getOrElse(typeId.name, Type.Contract.stack(typeId)))
    }
  def func[_: P]: P[Ast.FuncDef[Ctx]] =
    P(
      Lexer.funcModifier.rep(0) ~ Lexer
        .keyword("fn") ~/ Lexer.funcId ~ funParams ~ returnType ~ "{" ~ statement.rep ~ "}"
    ).map { case (modifiers, funcId, params, returnType, statement) =>
      if (modifiers.toSet.size != modifiers.length) {
        throw Compiler.Error(s"Duplicated function modifiers: $modifiers")
      } else {
        val isPublic  = modifiers.contains(Lexer.Pub)
        val isPayable = modifiers.contains(Lexer.Payable)
        Ast.FuncDef(funcId, isPublic, isPayable, params, returnType, statement)
      }
    }
  def funcCall[_: P]: P[Ast.FuncCall[Ctx]] =
    callAbs.map { case (funcId, exprs) => Ast.FuncCall(funcId, exprs) }

  def block[_: P]: P[Seq[Ast.Statement[Ctx]]] = P("{" ~ statement.rep(1) ~ "}")
  def elseBranch[_: P]: P[Seq[Ast.Statement[Ctx]]] =
    P((Lexer.keyword("else") ~/ block).?).map(_.fold(Seq.empty[Ast.Statement[Ctx]])(identity))
  def ifelse[_: P]: P[Ast.IfElse[Ctx]] =
    P(Lexer.keyword("if") ~/ expr ~ block ~ elseBranch)
      .map { case (expr, ifBranch, elseBranch) => Ast.IfElse(expr, ifBranch, elseBranch) }

  def whileStmt[_: P]: P[Ast.While[Ctx]] =
    P(Lexer.keyword("while") ~/ expr ~ block).map { case (expr, block) => Ast.While(expr, block) }

  def statement[_: P]: P[Ast.Statement[Ctx]]

  def contractArgument[_: P]: P[Ast.Argument] =
    P(Lexer.mut ~ Lexer.ident ~ ":" ~ Lexer.typeId).map { case (isMutable, ident, typeId) =>
      val tpe = Lexer.primTpes.getOrElse(typeId.name, Type.Contract.global(typeId, ident))
      Ast.Argument(ident, tpe, isMutable)
    }
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
object StatelessParser extends Parser[StatelessContext] {
  def atom[_: P]: P[Ast.Expr[StatelessContext]] =
    P(const | callExpr | contractConv | variable | parenExpr)

  def statement[_: P]: P[Ast.Statement[StatelessContext]] =
    P(varDef | assign | funcCall | ifelse | whileStmt | ret)

  def assetScript[_: P]: P[Ast.AssetScript] =
    P(Start ~ Lexer.keyword("AssetScript") ~/ Lexer.typeId ~ "{" ~ func.rep(1) ~ "}")
      .map { case (typeId, funcs) => Ast.AssetScript(typeId, funcs) }
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
object StatefulParser extends Parser[StatefulContext] {
  def atom[_: P]: P[Ast.Expr[StatefulContext]] =
    P(const | callExpr | contractCallExpr | contractConv | variable | parenExpr)

  def contractCallExpr[_: P]: P[Ast.ContractCallExpr] =
    P((contractConv | variable) ~ "." ~ callAbs).map { case (obj, (callId, exprs)) =>
      Ast.ContractCallExpr(obj, callId, exprs)
    }

  def contractCall[_: P]: P[Ast.ContractCall] =
    P((contractConv | variable) ~ "." ~ callAbs)
      .map { case (obj, (callId, exprs)) => Ast.ContractCall(obj, callId, exprs) }

  def statement[_: P]: P[Ast.Statement[StatefulContext]] =
    P(varDef | assign | funcCall | contractCall | ifelse | whileStmt | ret)

  def rawTxScript[_: P]: P[Ast.TxScript] =
    P(Lexer.keyword("TxScript") ~/ Lexer.typeId ~ "{" ~ func.rep(1) ~ "}")
      .map { case (typeId, funcs) => Ast.TxScript(typeId, funcs) }
  def txScript[_: P]: P[Ast.TxScript] = P(Start ~ rawTxScript ~ End)

  def contractParams[_: P]: P[Seq[Ast.Argument]] = P("(" ~ contractArgument.rep(0, ",") ~ ")")
  def rawTxContract[_: P]: P[Ast.TxContract] =
    P(Lexer.keyword("TxContract") ~/ Lexer.typeId ~ contractParams ~ "{" ~ func.rep(1) ~ "}")
      .map { case (typeId, params, funcs) => Ast.TxContract(typeId, params, funcs) }
  def contract[_: P]: P[Ast.TxContract] = P(Start ~ rawTxContract ~ End)

  def multiContract[_: P]: P[Ast.MultiTxContract] =
    P(Start ~ (rawTxScript | rawTxContract).rep(1) ~ End).map(Ast.MultiTxContract.apply)

  def state[_: P]: P[Seq[Ast.Const[StatefulContext]]] = P("[" ~ const.rep(0, ",") ~ "]")
}
