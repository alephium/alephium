package org.alephium.protocol.vm.lang

import fastparse._

import org.alephium.protocol.vm.Val

object Parser {
  implicit val whitespace: P[_] => P[Unit] = { implicit ctx: P[_] =>
    P(MultiLineWhitespace.whitespace(ctx) | Lexer.lineComment)
  }

  def const[_: P]: P[Ast.Const]       = P(Lexer.typedNum).map(Ast.Const)
  def variable[_: P]: P[Ast.Variable] = P(Lexer.ident).map(Ast.Variable(_, None, None))
  def call[_: P]: P[Ast.Call]         = P(Lexer.ident ~ "(" ~ expr.rep(0, ",") ~ ")").map(Ast.Call.tupled)

  def binopPrefix[_: P]: P[Ast.Expr]               = P(const | variable | parenExpr)
  def binopTerm[_: P]: P[(Ast.Operator, Ast.Expr)] = P(Lexer.operator ~ expr)
  def binop[_: P]: P[Ast.Binop] = P(binopPrefix ~ binopTerm).map {
    case (left, (op, right)) => Ast.Binop(op, left, right)
  }

  def parenExpr[_: P]: P[Ast.ParenExpr] = P("(" ~ expr ~ ")").map(Ast.ParenExpr)
  def expr[_: P]: P[Ast.Expr]           = P(binop | call | parenExpr | const | variable)

  def varDef[_: P]: P[Ast.VarDef] = P(("val" | "var").! ~/ Lexer.ident ~ "=" ~ expr).map {
    case ("val", ident, expr) =>
      Ast.VarDef(Ast.Variable(ident, None, isMutableOpt = Some(false)), expr)
    case ("var", ident, expr) =>
      Ast.VarDef(Ast.Variable(ident, None, isMutableOpt = Some(true)), expr)
    case (_, _, _) =>
      throw new RuntimeException("Dead branch")
  }
  def assign[_: P]: P[Ast.Assign] = P(variable ~ "=" ~ expr).map(Ast.Assign.tupled)
  def ret[_: P]: P[Ast.Return]    = P("return" ~/ expr.rep(0, ",")).map(Ast.Return)

  def argument[_: P]: P[Ast.Argument] = P(Lexer.ident ~ ":" ~ Lexer.tpe).map(Ast.Argument.tupled)
  def returnType[_: P]: P[Seq[Val.Type]] =
    P((":" ~ Lexer.tpe.rep).?).map(_.fold(Seq.empty[Val.Type])(identity))
  def func[_: P]: P[Ast.FuncDef] =
    P("def" ~/ Lexer.ident ~ "(" ~ argument.rep(0, ",") ~ ")" ~ returnType ~ funcBody)
      .map(Ast.FuncDef.tupled)
  def funcBody[_: P]: P[Seq[Ast.Statement]] = P("{" ~ statement.rep(1) ~ "}")

  def statement[_: P]: P[Ast.Statement] = P(varDef | assign | ret | func)

  def contract[_: P]: P[Ast.Contract] = P(Start ~ varDef.rep ~ func.rep(1)).map(Ast.Contract.tupled)
}
