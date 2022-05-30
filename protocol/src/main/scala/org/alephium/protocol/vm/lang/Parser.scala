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

import org.alephium.protocol.vm.{Instr, StatefulContext, StatelessContext, Val}
import org.alephium.util.U256

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

  def placeholder[Unkown: P]: P[Ast.Placeholder[Ctx]] = P("?").map(_ => Ast.Placeholder[Ctx]())
  def const[Unkown: P]: P[Ast.Const[Ctx]] =
    P(Lexer.typedNum | Lexer.bool | Lexer.bytes | Lexer.address).map(Ast.Const.apply[Ctx])
  def createArray1[Unkown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    P("[" ~ expr.rep(1, ",") ~ "]").map(Ast.CreateArrayExpr.apply)
  def createArray2[Unkown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    P("[" ~ expr ~ ";" ~ nonNegativeNum("array size") ~ "]").map { case (expr, size) =>
      Ast.CreateArrayExpr(Seq.fill(size)(expr))
    }
  def arrayExpr[Unkown: P]: P[Ast.Expr[Ctx]]    = P(createArray1 | createArray2)
  def variable[Unkown: P]: P[Ast.Variable[Ctx]] = P(Lexer.ident).map(Ast.Variable.apply[Ctx])
  def callAbs[Unkown: P]: P[(Ast.FuncId, Seq[Ast.Expr[Ctx]])] =
    P(Lexer.funcId ~ "(" ~ expr.rep(0, ",") ~ ")")
  def callExpr[Unkown: P]: P[Ast.CallExpr[Ctx]] =
    callAbs.map { case (funcId, expr) => Ast.CallExpr(funcId, expr) }
  def contractConv[Unkown: P]: P[Ast.ContractConv[Ctx]] =
    P(Lexer.typeId ~ "(" ~ expr ~ ")").map { case (typeId, expr) => Ast.ContractConv(typeId, expr) }

  def chain[Unkown: P](p: => P[Ast.Expr[Ctx]], op: => P[Operator]): P[Ast.Expr[Ctx]] =
    P(p ~ (op ~ p).rep).map { case (lhs, rhs) =>
      rhs.foldLeft(lhs) { case (acc, (op, right)) =>
        Ast.Binop(op, acc, right)
      }
    }

  def nonNegativeNum[Unkown: P](errorMsg: String): P[Int] = Lexer.num.map { value =>
    val idx = value.intValue()
    if (idx < 0) {
      throw Compiler.Error(s"Invalid $errorMsg: $idx")
    }
    idx
  }

  def arrayIndexConst[Unkown: P]: P[Ast.Expr[Ctx]] = {
    nonNegativeNum("arrayIndex").map { v =>
      if (v > 0xff) {
        throw Compiler.Error(s"Array index too big: ${v}")
      }
      Ast.Const[Ctx](Val.U256(U256.unsafe(v)))
    }
  }
  def arrayIndex[Unkown: P]: P[Ast.Expr[Ctx]] = {
    P(
      "[" ~ (arrayIndexConst | placeholder) ~ "]"
    )
  }

  // Optimize chained comparisons
  def expr[Unkown: P]: P[Ast.Expr[Ctx]]         = P(chain(andExpr, Lexer.opOr))
  def andExpr[Unkown: P]: P[Ast.Expr[Ctx]]      = P(chain(relationExpr, Lexer.opAnd))
  def relationExpr[Unkown: P]: P[Ast.Expr[Ctx]] = P(comp | arithExpr5)
  def comp[Unkown: P]: P[Ast.Expr[Ctx]] =
    P(arithExpr5 ~ comparison ~ arithExpr5).map { case (lhs, op, rhs) => Ast.Binop(op, lhs, rhs) }
  def comparison[Unkown: P]: P[TestOperator] =
    P(Lexer.opEq | Lexer.opNe | Lexer.opLe | Lexer.opLt | Lexer.opGe | Lexer.opGt)
  def arithExpr5[Unkown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr4, Lexer.opBitOr))
  def arithExpr4[Unkown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr3, Lexer.opXor))
  def arithExpr3[Unkown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr2, Lexer.opBitAnd))
  def arithExpr2[Unkown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr1, Lexer.opSHL | Lexer.opSHR))
  def arithExpr1[Unkown: P]: P[Ast.Expr[Ctx]] =
    P(
      chain(
        arithExpr0,
        Lexer.opByteVecAdd | Lexer.opAdd | Lexer.opSub | Lexer.opModAdd | Lexer.opModSub
      )
    )
  def arithExpr0[Unkown: P]: P[Ast.Expr[Ctx]] =
    P(chain(unaryExpr, Lexer.opMul | Lexer.opDiv | Lexer.opMod | Lexer.opModMul))
  def unaryExpr[Unkown: P]: P[Ast.Expr[Ctx]] =
    P(arrayElement | (Lexer.opNot ~ arrayElement).map { case (op, expr) =>
      Ast.UnaryOp.apply[Ctx](op, expr)
    })
  def arrayElement[Unkown: P]: P[Ast.Expr[Ctx]] = P(atom ~ arrayIndex.rep(0)).map {
    case (expr, indexes) =>
      indexes.foldLeft(expr) { case (acc, index) =>
        Ast.ArrayElement(acc, index)
      }
  }
  def atom[Unkown: P]: P[Ast.Expr[Ctx]]

  def parenExpr[Unkown: P]: P[Ast.ParenExpr[Ctx]] =
    P("(" ~ expr ~ ")").map(Ast.ParenExpr.apply[Ctx])

  def ret[Unkown: P]: P[Ast.ReturnStmt[Ctx]] =
    P(Lexer.keyword("return") ~/ expr.rep(0, ",")).map(Ast.ReturnStmt.apply[Ctx])

  def ident[Unkown: P]: P[(Boolean, Ast.Ident)] = P(Lexer.mut ~ Lexer.ident)
  def idents[Unkown: P]: P[Seq[(Boolean, Ast.Ident)]] = P(
    ident.map(Seq(_)) | "(" ~ ident.rep(1, ",") ~ ")"
  )
  def varDef[Unkown: P]: P[Ast.VarDef[Ctx]] =
    P(Lexer.keyword("let") ~/ idents ~ "=" ~ expr).map { case (idents, expr) =>
      Ast.VarDef(idents, expr)
    }
  def assignmentSimpleTarget[Unkown: P]: P[Ast.AssignmentTarget[Ctx]] = P(
    Lexer.ident.map(Ast.AssignmentSimpleTarget.apply[Ctx])
  )
  def assignmentArrayElementTarget[Unkown: P]: P[Ast.AssignmentArrayElementTarget[Ctx]] = P(
    Lexer.ident ~ arrayIndex.rep(1)
  ).map { case (ident, indexes) =>
    Ast.AssignmentArrayElementTarget[Ctx](ident, indexes)
  }
  def assignmentTarget[Unkown: P]: P[Ast.AssignmentTarget[Ctx]] = P(
    assignmentArrayElementTarget | assignmentSimpleTarget
  )
  def assign[Unkown: P]: P[Ast.Statement[Ctx]] =
    P(assignmentTarget.rep(1, ",") ~ "=" ~ expr).map { case (targets, expr) =>
      Ast.Assign(targets, expr)
    }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def parseType[Unkown: P](contractTypeCtor: Ast.TypeId => Type): P[Type] = {
    P(
      Lexer.typeId.map(id => Lexer.primTpes.getOrElse(id.name, contractTypeCtor(id))) |
        arrayType(parseType(contractTypeCtor))
    )
  }

  // use by-name parameter because of https://github.com/com-lihaoyi/fastparse/pull/204
  def arrayType[Unkown: P](baseType: => P[Type]): P[Type] = {
    P("[" ~ baseType ~ ";" ~ nonNegativeNum("array size") ~ "]").map { case (tpe, size) =>
      Type.FixedSizeArray(tpe, size)
    }
  }

  def funcArgument[Unkown: P]: P[Ast.Argument] =
    P(Lexer.mut ~ Lexer.ident ~ ":").flatMap { case (isMutable, ident) =>
      parseType(typeId => Type.Contract.local(typeId, ident)).map { tpe =>
        Ast.Argument(ident, tpe, isMutable)
      }
    }
  def funParams[Unkown: P]: P[Seq[Ast.Argument]] = P("(" ~ funcArgument.rep(0, ",") ~ ")")
  def returnType[Unkown: P]: P[Seq[Type]]        = P(simpleReturnType | bracketReturnType)
  def simpleReturnType[Unkown: P]: P[Seq[Type]] =
    P("->" ~ parseType(Type.Contract.stack)).map(tpe => Seq(tpe))
  def bracketReturnType[Unkown: P]: P[Seq[Type]] =
    P("->" ~ "(" ~ parseType(Type.Contract.stack).rep(0, ",") ~ ")")
  def func[Unkown: P]: P[Ast.FuncDef[Ctx]] =
    P(
      Lexer.FuncModifier.modifiers.rep(0) ~ Lexer
        .keyword("fn") ~/ Lexer.funcId ~ funParams ~ returnType ~ "{" ~ statement.rep ~ "}"
    ).map { case (modifiers, funcId, params, returnType, statements) =>
      if (modifiers.toSet.size != modifiers.length) {
        throw Compiler.Error(s"Duplicated function modifiers: $modifiers")
      } else {
        val isPublic  = modifiers.contains(Lexer.FuncModifier.Pub)
        val isPayable = modifiers.contains(Lexer.FuncModifier.Payable)
        Ast.FuncDef(funcId, isPublic, isPayable, params, returnType, statements)
      }
    }
  def eventFields[Unkown: P]: P[Seq[Ast.EventField]] = P("(" ~ eventField.rep(0, ",") ~ ")")
  def eventDef[Unkown: P]: P[Ast.EventDef] = P(Lexer.keyword("event") ~/ Lexer.typeId ~ eventFields)
    .map { case (typeId, fields) =>
      if (fields.length >= Instr.allLogInstrs.length) {
        throw Compiler.Error("Max 8 fields allowed for contract events")
      }
      Ast.EventDef(typeId, fields)
    }

  def funcCall[Unknown: P]: P[Ast.FuncCall[Ctx]] =
    callAbs.map { case (funcId, exprs) => Ast.FuncCall(funcId, exprs) }

  def block[Unknown: P]: P[Seq[Ast.Statement[Ctx]]] = P("{" ~ statement.rep(1) ~ "}")
  def elseBranch[Unkown: P]: P[Seq[Ast.Statement[Ctx]]] =
    P((Lexer.keyword("else") ~/ block).?).map(_.fold(Seq.empty[Ast.Statement[Ctx]])(identity))
  def ifelse[Unkown: P]: P[Ast.IfElse[Ctx]] =
    P(Lexer.keyword("if") ~/ expr ~ block ~ elseBranch)
      .map { case (expr, ifBranch, elseBranch) => Ast.IfElse(expr, ifBranch, elseBranch) }

  def whileStmt[Unkown: P]: P[Ast.While[Ctx]] =
    P(Lexer.keyword("while") ~/ expr ~ block).map { case (expr, block) => Ast.While(expr, block) }

  def loopStmt[Unkown: P]: P[Ast.Loop[Ctx]] =
    P(
      Lexer.keyword("loop") ~/ "(" ~
        nonNegativeNum("loop start") ~ "," ~
        nonNegativeNum("loop end") ~ "," ~
        Lexer.num.map(_.intValue()) ~ "," ~
        statement ~ ")"
    ).map { case (start, end, step, statement) =>
      Ast.Loop[Ctx](start, end, step, statement)
    }

  def statement[Unkown: P]: P[Ast.Statement[Ctx]]

  def contractArgument[Unkown: P]: P[Ast.Argument] =
    P(Lexer.mut ~ Lexer.ident ~ ":").flatMap { case (isMutable, ident) =>
      parseType(typeId => Type.Contract.global(typeId, ident)).map { tpe =>
        Ast.Argument(ident, tpe, isMutable)
      }
    }

  def templateParams[Unkown: P]: P[Seq[Ast.Argument]] =
    P("(" ~ contractArgument.rep(1, ",") ~ ")").map { params =>
      val mutables = params.filter(_.isMutable)
      if (mutables.nonEmpty) {
        throw Compiler.Error(
          s"Template variables should be immutable: ${mutables.map(_.ident.name).mkString}"
        )
      }
      params
    }

  def eventField[Unkown: P]: P[Ast.EventField] =
    P(Lexer.ident ~ ":").flatMap { case (ident) =>
      parseType(typeId => Type.Contract.global(typeId, ident)).map { tpe =>
        Ast.EventField(ident, tpe)
      }
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
  def atom[Unkown: P]: P[Ast.Expr[StatelessContext]] =
    P(placeholder | const | callExpr | contractConv | variable | parenExpr | arrayExpr)

  def statement[Unkown: P]: P[Ast.Statement[StatelessContext]] =
    P(varDef | assign | funcCall | ifelse | whileStmt | ret | loopStmt)

  def assetScript[Unkown: P]: P[Ast.AssetScript] =
    P(
      Start ~ Lexer.keyword("AssetScript") ~/ Lexer.typeId ~ templateParams.? ~
        "{" ~ func.rep(1) ~ "}"
    ).map { case (typeId, templateVars, funcs) =>
      Ast.AssetScript(typeId, templateVars.getOrElse(Seq.empty), funcs)
    }
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
object StatefulParser extends Parser[StatefulContext] {
  def atom[Unkown: P]: P[Ast.Expr[StatefulContext]] =
    P(
      placeholder | const | callExpr | contractCallExpr | contractConv | variable | parenExpr | arrayExpr
    )

  def contractCallExpr[Unkown: P]: P[Ast.ContractCallExpr] =
    P((contractConv | variable) ~ "." ~ callAbs).map { case (obj, (callId, exprs)) =>
      Ast.ContractCallExpr(obj, callId, exprs)
    }

  def contractCall[Unkown: P]: P[Ast.ContractCall] =
    P((contractConv | variable) ~ "." ~ callAbs)
      .map { case (obj, (callId, exprs)) => Ast.ContractCall(obj, callId, exprs) }

  def statement[Unkown: P]: P[Ast.Statement[StatefulContext]] =
    P(varDef | assign | funcCall | contractCall | ifelse | whileStmt | ret | emitEvent | loopStmt)

  def contractParams[Unkown: P]: P[Seq[Ast.Argument]] = P("(" ~ contractArgument.rep(0, ",") ~ ")")

  def rawTxScript[Unkown: P]: P[Ast.TxScript] =
    P(
      Lexer.keyword(
        "TxScript"
      ) ~/ Lexer.typeId ~ templateParams.? ~ Lexer.TxScriptModifier.modifiers.? ~ "{" ~ statement
        .rep(0) ~ func
        .rep(0) ~ "}"
    )
      .map { case (typeId, templateVars, payable, mainStmts, funcs) =>
        val isPayable = !payable.contains(Lexer.TxScriptModifier.NonPayable)
        if (mainStmts.isEmpty) {
          throw Compiler.Error(s"No main statements defined in TxScript ${typeId.name}")
        } else {
          Ast.TxScript(
            typeId,
            templateVars.getOrElse(Seq.empty),
            Ast.FuncDef.main(mainStmts, isPayable) +: funcs
          )
        }
      }
  def txScript[Unkown: P]: P[Ast.TxScript] = P(Start ~ rawTxScript ~ End)

  def inheritanceTemplateVariable[Unkown: P]: P[Seq[Ast.Ident]] =
    P("<" ~ Lexer.ident.rep(1, ",") ~ ">").?.map(_.getOrElse(Seq.empty))
  def inheritanceFields[Unkown: P]: P[Seq[Ast.Ident]] =
    P("(" ~ Lexer.ident.rep(0, ",") ~ ")")
  def contractInheritance[Unkown: P]: P[Ast.ContractInheritance] =
    P(Lexer.typeId ~ inheritanceFields).map { case (typeId, fields) =>
      Ast.ContractInheritance(typeId, fields)
    }
  def contractInheritances[Unkown: P]: P[Seq[Ast.Inheritance]] =
    P(Lexer.keyword("extends") ~/ (contractInheritance | interfaceInheritance).rep(1, ","))
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def rawTxContract[Unkown: P]: P[Ast.TxContract] =
    P(
      Lexer.keyword("TxContract") ~/ Lexer.typeId ~ contractParams ~
        contractInheritances.? ~ "{" ~ eventDef.rep ~ func.rep ~ "}"
    ).map { case (typeId, fields, contractInheritances, events, funcs) =>
      if (funcs.length < 1) {
        throw Compiler.Error(s"No function definition in TxContract ${typeId.name}")
      } else {
        Ast.TxContract(
          typeId,
          Seq.empty,
          fields,
          funcs,
          events,
          contractInheritances.getOrElse(Seq.empty)
        )
      }
    }
  def contract[Unkown: P]: P[Ast.TxContract] = P(Start ~ rawTxContract ~ End)

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def interfaceInheritance[Unkown: P]: P[Ast.InterfaceInheritance] =
    P(Lexer.typeId).map(Ast.InterfaceInheritance)
  def interfaceFunc[Unkown: P]: P[Ast.FuncDef[StatefulContext]] =
    P(
      Lexer.FuncModifier.modifiers.rep(0) ~ Lexer.keyword(
        "fn"
      ) ~/ Lexer.funcId ~ funParams ~ returnType
    )
      .map { case (modifiers, funcId, params, returnType) =>
        if (modifiers.toSet.size != modifiers.length) {
          throw Compiler.Error(s"Duplicated function modifiers: $modifiers")
        } else {
          val isPublic  = modifiers.contains(Lexer.FuncModifier.Pub)
          val isPayable = modifiers.contains(Lexer.FuncModifier.Payable)
          Ast.FuncDef(funcId, isPublic, isPayable, params, returnType, Seq.empty)
        }
      }
  def rawInterface[Unkown: P]: P[Ast.ContractInterface] =
    P(
      Lexer.keyword("Interface") ~/ Lexer.typeId ~
        (Lexer.keyword("extends") ~/ interfaceInheritance.rep(1, ",")).? ~
        "{" ~ eventDef.rep ~ interfaceFunc.rep ~ "}"
    ).map { case (typeId, inheritances, events, funcs) =>
      inheritances match {
        case Some(parents) if parents.length > 1 =>
          throw Compiler.Error(
            s"Interface only supports single inheritance: ${parents.map(_.parentId.name).mkString(",")}"
          )
        case _ => ()
      }
      if (funcs.length < 1) {
        throw Compiler.Error(s"No function definition in TxContract ${typeId.name}")
      } else {
        Ast.ContractInterface(
          typeId,
          funcs,
          events,
          inheritances.getOrElse(Seq.empty)
        )
      }
    }
  def interface[Unkown: P]: P[Ast.ContractInterface] = P(Start ~ rawInterface ~ End)

  def multiContract[Unkown: P]: P[Ast.MultiTxContract] =
    P(Start ~ (rawTxScript | rawTxContract | rawInterface).rep(1) ~ End)
      .map(Ast.MultiTxContract.apply)

  def state[Unkown: P]: P[Seq[Ast.Const[StatefulContext]]] =
    P("[" ~ constOrArray.rep(0, ",") ~ "]").map(_.flatten)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def constOrArray[Unkown: P]: P[Seq[Ast.Const[StatefulContext]]] = P(
    const.map(Seq(_)) |
      P("[" ~ constOrArray.rep(0, ",").map(_.flatten) ~ "]") |
      P("[" ~ constOrArray ~ ";" ~ nonNegativeNum("array size") ~ "]").map { case (consts, size) =>
        (0 until size).flatMap(_ => consts)
      }
  )

  def emitEvent[Unkown: P]: P[Ast.EmitEvent[StatefulContext]] =
    P("emit" ~ Lexer.typeId ~ "(" ~ expr.rep(0, ",") ~ ")")
      .map { case (typeId, exprs) => Ast.EmitEvent(typeId, exprs) }
}
