package org.alephium.protocol.vm.lang

import fastparse._

import org.alephium.protocol.vm.Val

object Parser {
  implicit val whitespace: P[_] => P[Unit] = { implicit ctx: P[_] =>
    Lexer.emptyChars(ctx)
  }

  def const[_: P]: P[Ast.Const]                     = P(Lexer.typedNum | Lexer.bool).map(Ast.Const)
  def variable[_: P]: P[Ast.Variable]               = P(Lexer.ident).map(Ast.Variable)
  def callAbs[_: P]: P[(Ast.CallId, Seq[Ast.Expr])] = P(Lexer.callId ~ "(" ~ expr.rep(0, ",") ~ ")")
  def call[_: P]: P[Ast.Call]                       = callAbs.map(Ast.Call.tupled)

  def Chain[_: P](p: => P[Ast.Expr], op: => P[Ast.Operator]): P[Ast.Expr] =
    P(p ~ (op ~ p).rep).map {
      case (lhs, rhs) =>
        rhs.foldLeft(lhs) {
          case (acc, (op, right)) => Ast.Binop(op, acc, right)
        }
    }
  def binop[_: P]: P[Ast.Expr]  = P(Chain(binop1, Lexer.opAdd | Lexer.opSub))
  def binop1[_: P]: P[Ast.Expr] = P(Chain(binop2, Lexer.opMul | Lexer.opDiv | Lexer.opMod))
  def binop2[_: P]: P[Ast.Expr] = P(const | call | variable | parenExpr)

  def parenExpr[_: P]: P[Ast.ParenExpr] = P("(" ~ expr ~ ")").map(Ast.ParenExpr)
  def expr[_: P]: P[Ast.Expr]           = P(binop | call | parenExpr | const | variable)

  def ret[_: P]: P[Ast.Return] = P(Lexer.keyword("return") ~/ expr.rep(0, ",")).map(Ast.Return)

  def varDef[_: P]: P[Ast.VarDef] =
    P(Lexer.keyword("let") ~/ Lexer.mut ~ Lexer.ident ~ "=" ~ expr).map(Ast.VarDef.tupled)
  def assign[_: P]: P[Ast.Assign] = P(Lexer.ident ~ "=" ~ expr).map(Ast.Assign.tupled)

  def argument[_: P]: P[Ast.Argument] = P(Lexer.mut ~ Lexer.ident ~ ":" ~ Lexer.tpe).map {
    case (isMutable, ident, tpe) => Ast.Argument(ident, tpe, isMutable)
  }
  def params[_: P]: P[Seq[Ast.Argument]] = P("(" ~ argument.rep(0, ",") ~ ")")
  def returnType[_: P]: P[Seq[Val.Type]] = P("->" ~ "(" ~ Lexer.tpe.rep(0, ",") ~ ")")
  def func[_: P]: P[Ast.FuncDef] =
    P(Lexer.keyword("fn") ~/ Lexer.ident ~ params ~ returnType ~ "{" ~ statement.rep ~ ret ~ "}")
      .map(Ast.FuncDef.tupled)
  def funcCall[_: P]: P[Ast.FuncCall] = callAbs.map(Ast.FuncCall.tupled)

  def statement[_: P]: P[Ast.Statement] = P(varDef | assign | funcCall)

  def contract[_: P]: P[Ast.Contract] =
    P(Start ~ Lexer.keyword("contract") ~/ Lexer.ident ~ params ~ "{" ~ func.rep(1) ~ "}")
      .map(Ast.Contract.tupled)
}
