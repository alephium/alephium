package org.alephium.protocol.vm.lang

import fastparse._

import org.alephium.protocol.vm.{StatefulContext, StatelessContext}

// scalastyle:off number.of.methods
@SuppressWarnings(
  Array("org.wartremover.warts.JavaSerializable",
        "org.wartremover.warts.Product",
        "org.wartremover.warts.Serializable"))
class Parser[Ctx <: StatelessContext] {
  implicit val whitespace: P[_] => P[Unit] = { implicit ctx: P[_] =>
    Lexer.emptyChars(ctx)
  }

  def const[_: P]: P[Ast.Const[Ctx]] =
    P(Lexer.typedNum | Lexer.bool | Lexer.byte32).map(Ast.Const.apply[Ctx])
  def variable[_: P]: P[Ast.Variable[Ctx]] = P(Lexer.ident).map(Ast.Variable.apply[Ctx])
  def callAbs[_: P]: P[(Ast.FuncId, Seq[Ast.Expr[Ctx]])] =
    P(Lexer.funcId ~ "(" ~ expr.rep(0, ",") ~ ")")
  def callExpr[_: P]: P[Ast.CallExpr[Ctx]] =
    callAbs.map { case (funcId, expr) => Ast.CallExpr(funcId, expr) }
  def contractConv[_: P]: P[Ast.ContractConv[Ctx]] =
    P(Lexer.typeId ~ "(" ~ expr ~ ")").map { case (typeId, expr) => Ast.ContractConv(typeId, expr) }
  def contractCallExpr[_: P]: P[Ast.ContractCallExpr[Ctx]] = P(Lexer.ident ~ "." ~ callAbs).map {
    case (objId, (callId, exprs)) => Ast.ContractCallExpr(objId, callId, exprs)
  }

  def chain[_: P](p: => P[Ast.Expr[Ctx]], op: => P[Operator]): P[Ast.Expr[Ctx]] =
    P(p ~ (op ~ p).rep).map {
      case (lhs, rhs) =>
        rhs.foldLeft(lhs) {
          case (acc, (op, right)) => Ast.Binop(op, acc, right)
        }
    }
  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def chainBool[_: P](p: => P[Ast.Expr[Ctx]], op: => P[TestOperator]): P[Ast.Expr[Ctx]] =
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
  def expr[_: P]: P[Ast.Expr[Ctx]]         = P(chainBool(andExpr, Lexer.opOr))
  def andExpr[_: P]: P[Ast.Expr[Ctx]]      = P(chainBool(relationExpr, Lexer.opAnd))
  def relationExpr[_: P]: P[Ast.Expr[Ctx]] = P(chainBool(arithExpr0, comparision))
  def comparision[_: P]: P[TestOperator] =
    P(Lexer.opEq | Lexer.opNe | Lexer.opLe | Lexer.opLt | Lexer.opGe | Lexer.opGt)
  def arithExpr0[_: P]: P[Ast.Expr[Ctx]] = P(chain(arithExpr1, Lexer.opAdd | Lexer.opSub))
  def arithExpr1[_: P]: P[Ast.Expr[Ctx]] =
    P(chain(unaryExpr, Lexer.opMul | Lexer.opDiv | Lexer.opMod))
  def unaryExpr[_: P]: P[Ast.Expr[Ctx]] =
    P(atom | (Lexer.opNot ~ atom).map { case (op, expr) => Ast.UnaryOp.apply[Ctx](op, expr) })
  def atom[_: P]: P[Ast.Expr[Ctx]] =
    P(const | callExpr | contractCallExpr | contractConv | variable | parenExpr)

  def parenExpr[_: P]: P[Ast.ParenExpr[Ctx]] = P("(" ~ expr ~ ")").map(Ast.ParenExpr.apply[Ctx])

  def ret[_: P]: P[Ast.ReturnStmt[Ctx]] =
    P(Lexer.keyword("return") ~/ expr.rep(0, ",")).map(Ast.ReturnStmt.apply[Ctx])

  def varDef[_: P]: P[Ast.VarDef[Ctx]] =
    P(Lexer.keyword("let") ~/ Lexer.mut ~ Lexer.ident ~ "=" ~ expr).map {
      case (isMutable, ident, expr) => Ast.VarDef(isMutable, ident, expr)
    }
  def assign[_: P]: P[Ast.Assign[Ctx]] = P(Lexer.ident ~ "=" ~ expr).map {
    case (ident, expr) => Ast.Assign(ident, expr)
  }

  def funcArgument[_: P]: P[Ast.Argument] =
    P(Lexer.mut ~ Lexer.ident ~ ":" ~ Lexer.typeId).map {
      case (isMutable, ident, typeId) =>
        val tpe = Lexer.primTpes.getOrElse(typeId.name, Type.Contract.local(typeId, ident))
        Ast.Argument(ident, tpe, isMutable)
    }
  def funParams[_: P]: P[Seq[Ast.Argument]] = P("(" ~ funcArgument.rep(0, ",") ~ ")")
  def returnType[_: P]: P[Seq[Type]] = P("->" ~ "(" ~ Lexer.typeId.rep(0, ",") ~ ")").map {
    _.map(typeId => Lexer.primTpes.getOrElse(typeId.name, Type.Contract.stack(typeId)))
  }
  def func[_: P]: P[Ast.FuncDef[Ctx]] =
    P(Lexer.keyword("fn") ~/ Lexer.funcId ~ funParams ~ returnType ~ "{" ~ statement.rep ~ "}")
      .map {
        case (funcId, params, returnType, statement) =>
          Ast.FuncDef(funcId, params, returnType, statement)
      }
  def funcCall[_: P]: P[Ast.FuncCall[Ctx]] =
    callAbs.map { case (funcId, exprs) => Ast.FuncCall(funcId, exprs) }
  def contractCall[_: P]: P[Ast.ContractCall[Ctx]] =
    P(Lexer.ident ~ "." ~ callAbs)
      .map { case (objId, (callId, exprs)) => Ast.ContractCall(objId, callId, exprs) }

  def block[_: P]: P[Seq[Ast.Statement[Ctx]]] = P("{" ~ statement.rep(1) ~ "}")
  def elseBranch[_: P]: P[Seq[Ast.Statement[Ctx]]] =
    P((Lexer.keyword("else") ~/ block).?).map(_.fold(Seq.empty[Ast.Statement[Ctx]])(identity))
  def ifelse[_: P]: P[Ast.IfElse[Ctx]] =
    P(Lexer.keyword("if") ~/ expr ~ block ~ elseBranch)
      .map { case (expr, ifBranch, elseBranch) => Ast.IfElse(expr, ifBranch, elseBranch) }

  def whileStmt[_: P]: P[Ast.While[Ctx]] =
    P(Lexer.keyword("while") ~/ expr ~ block).map { case (expr, block) => Ast.While(expr, block) }

  def statement[_: P]: P[Ast.Statement[Ctx]] =
    P(varDef | assign | funcCall | contractCall | ifelse | whileStmt | ret)

  def contractArgument[_: P]: P[Ast.Argument] =
    P(Lexer.mut ~ Lexer.ident ~ ":" ~ Lexer.typeId).map {
      case (isMutable, ident, typeId) =>
        val tpe = Lexer.primTpes.getOrElse(typeId.name, Type.Contract.global(typeId, ident))
        Ast.Argument(ident, tpe, isMutable)
    }
}

object StatelessParser extends Parser[StatelessContext] {
  def assetScript[_: P]: P[Ast.AssetScript] =
    P(Start ~ Lexer.keyword("AssetScript") ~/ Lexer.typeId ~ "{" ~ func.rep(1) ~ "}")
      .map { case (typeId, funcs) => Ast.AssetScript(typeId, funcs) }
}

object StatefulParser extends Parser[StatefulContext] {
  def txScript[_: P]: P[Ast.TxScript] =
    P(Start ~ Lexer.keyword("TxScript") ~/ Lexer.typeId ~ "{" ~ func.rep(1) ~ "}")
      .map { case (typeId, funcs) => Ast.TxScript(typeId, funcs) }

  def contractParams[_: P]: P[Seq[Ast.Argument]] = P("(" ~ contractArgument.rep(0, ",") ~ ")")
  def rawTxContract[_: P]: P[Ast.TxContract] =
    P(Lexer.keyword("Contract") ~/ Lexer.typeId ~ contractParams ~ "{" ~ func.rep(1) ~ "}")
      .map { case (typeId, params, funcs) => Ast.TxContract(typeId, params, funcs) }

  def contract[_: P]: P[Ast.TxContract] =
    P(Start ~ rawTxContract ~ End)

  def multiContract[_: P]: P[Ast.MultiTxContract] =
    P(Start ~ contract.rep(1) ~ End).map(Ast.MultiTxContract.apply)
}
