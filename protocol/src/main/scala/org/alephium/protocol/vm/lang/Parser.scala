package org.alephium.protocol.vm.lang

import fastparse._

import org.alephium.protocol.vm.Val

// scalastyle:off number.of.methods
@SuppressWarnings(
  Array("org.wartremover.warts.JavaSerializable",
        "org.wartremover.warts.Product",
        "org.wartremover.warts.Serializable"))
object Parser {
  implicit val whitespace: P[_] => P[Unit] = { implicit ctx: P[_] =>
    Lexer.emptyChars(ctx)
  }

  def const[_: P]: P[Ast.Const]                     = P(Lexer.typedNum | Lexer.bool | Lexer.byte32).map(Ast.Const)
  def variable[_: P]: P[Ast.Variable]               = P(Lexer.ident).map(Ast.Variable)
  def callAbs[_: P]: P[(Ast.CallId, Seq[Ast.Expr])] = P(Lexer.callId ~ "(" ~ expr.rep(0, ",") ~ ")")
  def callExpr[_: P]: P[Ast.CallExpr]               = callAbs.map(Ast.CallExpr.tupled)
  def contractConv[_: P]: P[Ast.ContractConv] =
    P(Lexer.typeId ~ "(" ~ expr ~ ")").map(Ast.ContractConv.tupled)
  def contractCallExpr[_: P]: P[Ast.ContractCallExpr] = P(Lexer.ident ~ "." ~ callAbs).map {
    case (objId, (callId, exprs)) => Ast.ContractCallExpr(objId, callId, exprs)
  }

  def chain[_: P](p: => P[Ast.Expr], op: => P[Operator]): P[Ast.Expr] =
    P(p ~ (op ~ p).rep).map {
      case (lhs, rhs) =>
        rhs.foldLeft(lhs) {
          case (acc, (op, right)) => Ast.Binop(op, acc, right)
        }
    }
  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def chainBool[_: P](p: => P[Ast.Expr], op: => P[TestOperator]): P[Ast.Expr] =
    P(p ~ (op ~ p).rep).map {
      case (lhs, rhs) =>
        if (rhs.isEmpty) {
          lhs
        } else {
          val (op, right) = rhs(0)
          val acc         = Ast.Binop(op, lhs, right)
          rhs.tail
            .foldLeft((acc, right)) {
              case ((acc, last), (op, right)) =>
                (Ast.Binop(And, acc, Ast.Binop(op, last, right)), right)
            }
            ._1
        }
    }

  // Optimize chained comparisions
  def expr[_: P]: P[Ast.Expr]         = P(chainBool(andExpr, Lexer.opOr))
  def andExpr[_: P]: P[Ast.Expr]      = P(chainBool(relationExpr, Lexer.opAnd))
  def relationExpr[_: P]: P[Ast.Expr] = P(chainBool(arithExpr0, comparision))
  def comparision[_: P]: P[TestOperator] =
    P(Lexer.opEq | Lexer.opNe | Lexer.opLe | Lexer.opLt | Lexer.opGe | Lexer.opGt)
  def arithExpr0[_: P]: P[Ast.Expr] = P(chain(arithExpr1, Lexer.opAdd | Lexer.opSub))
  def arithExpr1[_: P]: P[Ast.Expr] = P(chain(unaryExpr, Lexer.opMul | Lexer.opDiv | Lexer.opMod))
  def unaryExpr[_: P]: P[Ast.Expr]  = P(atom | (Lexer.opNot ~ atom).map(Ast.UnaryOp.tupled))
  def atom[_: P]: P[Ast.Expr] =
    P(const | callExpr | contractCallExpr | contractConv | variable | parenExpr)

  def parenExpr[_: P]: P[Ast.ParenExpr] = P("(" ~ expr ~ ")").map(Ast.ParenExpr)

  def ret[_: P]: P[Ast.ReturnStmt] =
    P(Lexer.keyword("return") ~/ expr.rep(0, ",")).map(Ast.ReturnStmt)

  def varDef[_: P]: P[Ast.VarDef] =
    P(Lexer.keyword("let") ~/ Lexer.mut ~ Lexer.ident ~ "=" ~ expr).map(Ast.VarDef.tupled)
  def assign[_: P]: P[Ast.Assign] = P(Lexer.ident ~ "=" ~ expr).map(Ast.Assign.tupled)

  def argument[_: P]: P[Ast.Argument] = P(Lexer.mut ~ Lexer.ident ~ ":" ~ Lexer.tpe).map {
    case (isMutable, ident, tpe) => Ast.Argument(ident, tpe, isMutable)
  }
  def params[_: P]: P[Seq[Ast.Argument]] = P("(" ~ argument.rep(0, ",") ~ ")")
  def returnType[_: P]: P[Seq[Val.Type]] = P("->" ~ "(" ~ Lexer.tpe.rep(0, ",") ~ ")")
  def func[_: P]: P[Ast.FuncDef] =
    P(Lexer.keyword("fn") ~/ Lexer.ident ~ params ~ returnType ~ "{" ~ statement.rep ~ "}")
      .map(Ast.FuncDef.tupled)
  def funcCall[_: P]: P[Ast.FuncCall] = callAbs.map(Ast.FuncCall.tupled)
  def contractCall[_: P]: P[Ast.ContractCall] = P(Lexer.ident ~ "." ~ callAbs).map {
    case (objId, (callId, exprs)) => Ast.ContractCall(objId, callId, exprs)
  }

  def body[_: P]: P[Seq[Ast.Statement]] = P("{" ~ statement.rep(1) ~ "}")
  def elseBranch[_: P]: P[Seq[Ast.Statement]] =
    P((Lexer.keyword("else") ~/ body).?).map(_.fold(Seq.empty[Ast.Statement])(identity))
  def ifelse[_: P]: P[Ast.IfElse] =
    P(Lexer.keyword("if") ~/ expr ~ body ~ elseBranch).map(Ast.IfElse.tupled)

  def whileStmt[_: P]: P[Ast.While] =
    P(Lexer.keyword("while") ~/ expr ~ body).map(Ast.While.tupled)

  def statement[_: P]: P[Ast.Statement] =
    P(varDef | assign | funcCall | contractCall | ifelse | whileStmt | ret)

  def contract[_: P]: P[Ast.Contract] =
    P(Start ~ Lexer.keyword("contract") ~/ Lexer.typeId ~ params ~ "{" ~ func.rep(1) ~ "}")
      .map(Ast.Contract.tupled)
}
