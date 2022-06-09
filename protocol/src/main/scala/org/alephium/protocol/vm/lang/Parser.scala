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
import org.alephium.protocol.vm.lang.Ast.{Annotation, Argument, ElseBranch, FuncId, Statement}

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

  def const[Unknown: P]: P[Ast.Const[Ctx]] =
    P(Lexer.typedNum | Lexer.bool | Lexer.bytes | Lexer.address).map(Ast.Const.apply[Ctx])
  def createArray1[Unknown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    P("[" ~ expr.rep(1, ",") ~ "]").map(Ast.CreateArrayExpr.apply)
  def createArray2[Unknown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    P("[" ~ expr ~ ";" ~ nonNegativeNum("array size") ~ "]").map { case (expr, size) =>
      Ast.CreateArrayExpr(Seq.fill(size)(expr))
    }
  def arrayExpr[Unknown: P]: P[Ast.Expr[Ctx]]    = P(createArray1 | createArray2)
  def variable[Unknown: P]: P[Ast.Variable[Ctx]] = P(Lexer.ident).map(Ast.Variable.apply[Ctx])
  def callAbs[Unknown: P]: P[(Ast.FuncId, Seq[Ast.Expr[Ctx]])] =
    P(Lexer.funcId ~ "(" ~ expr.rep(0, ",") ~ ")")
  def callExpr[Unknown: P]: P[Ast.CallExpr[Ctx]] =
    callAbs.map { case (funcId, expr) => Ast.CallExpr(funcId, expr) }
  def contractConv[Unknown: P]: P[Ast.ContractConv[Ctx]] =
    P(Lexer.typeId ~ "(" ~ expr ~ ")").map { case (typeId, expr) => Ast.ContractConv(typeId, expr) }

  def chain[Unknown: P](p: => P[Ast.Expr[Ctx]], op: => P[Operator]): P[Ast.Expr[Ctx]] =
    P(p ~ (op ~ p).rep).map { case (lhs, rhs) =>
      rhs.foldLeft(lhs) { case (acc, (op, right)) =>
        Ast.Binop(op, acc, right)
      }
    }

  def nonNegativeNum[Unknown: P](errorMsg: String): P[Int] = Lexer.num.map { value =>
    val idx = value.intValue()
    if (idx < 0) {
      throw Compiler.Error(s"Invalid $errorMsg: $idx")
    }
    idx
  }

  def arrayIndex[Unknown: P]: P[Ast.Expr[Ctx]] = P("[" ~ expr ~ "]")

  // Optimize chained comparisons
  def expr[Unknown: P]: P[Ast.Expr[Ctx]]         = P(chain(andExpr, Lexer.opOr))
  def andExpr[Unknown: P]: P[Ast.Expr[Ctx]]      = P(chain(relationExpr, Lexer.opAnd))
  def relationExpr[Unknown: P]: P[Ast.Expr[Ctx]] = P(comp | arithExpr5)
  def comp[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(arithExpr5 ~ comparison ~ arithExpr5).map { case (lhs, op, rhs) => Ast.Binop(op, lhs, rhs) }
  def comparison[Unknown: P]: P[TestOperator] =
    P(Lexer.opEq | Lexer.opNe | Lexer.opLe | Lexer.opLt | Lexer.opGe | Lexer.opGt)
  def arithExpr5[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr4, Lexer.opBitOr))
  def arithExpr4[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr3, Lexer.opXor))
  def arithExpr3[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr2, Lexer.opBitAnd))
  def arithExpr2[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr1, Lexer.opSHL | Lexer.opSHR))
  def arithExpr1[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(
      chain(
        arithExpr0,
        Lexer.opByteVecAdd | Lexer.opAdd | Lexer.opSub | Lexer.opModAdd | Lexer.opModSub
      )
    )
  def arithExpr0[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(unaryExpr, Lexer.opMul | Lexer.opDiv | Lexer.opMod | Lexer.opModMul))
  def unaryExpr[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(arrayElementOrAtom | (Lexer.opNot ~ arrayElementOrAtom).map { case (op, expr) =>
      Ast.UnaryOp.apply[Ctx](op, expr)
    })
  def arrayElementOrAtom[Unknown: P]: P[Ast.Expr[Ctx]] = P(atom ~ arrayIndex.rep(0)).map {
    case (expr, indexes) => if (indexes.nonEmpty) Ast.ArrayElement(expr, indexes) else expr
  }
  def atom[Unknown: P]: P[Ast.Expr[Ctx]]

  def parenExpr[Unknown: P]: P[Ast.ParenExpr[Ctx]] =
    P("(" ~ expr ~ ")").map(Ast.ParenExpr.apply[Ctx])

  def ret[Unknown: P]: P[Ast.ReturnStmt[Ctx]] =
    P(normalRet.rep(1)).map { returnStmts =>
      if (returnStmts.length > 1) {
        throw Compiler.Error("Consecutive return statements are not allowed")
      } else {
        returnStmts(0)
      }
    }

  def normalRet[Unknown: P]: P[Ast.ReturnStmt[Ctx]] =
    P(Lexer.keyword("return") ~/ expr.rep(0, ",")).map(Ast.ReturnStmt.apply[Ctx])

  def ident[Unknown: P]: P[(Boolean, Ast.Ident)] = P(Lexer.mut ~ Lexer.ident)
  def idents[Unknown: P]: P[Seq[(Boolean, Ast.Ident)]] = P(
    ident.map(Seq(_)) | "(" ~ ident.rep(1, ",") ~ ")"
  )
  def varDef[Unknown: P]: P[Ast.VarDef[Ctx]] =
    P(Lexer.keyword("let") ~/ idents ~ "=" ~ expr).map { case (idents, expr) =>
      Ast.VarDef(idents, expr)
    }
  def assignmentSimpleTarget[Unknown: P]: P[Ast.AssignmentTarget[Ctx]] = P(
    Lexer.ident.map(Ast.AssignmentSimpleTarget.apply[Ctx])
  )
  def assignmentArrayElementTarget[Unknown: P]: P[Ast.AssignmentArrayElementTarget[Ctx]] = P(
    Lexer.ident ~ arrayIndex.rep(1)
  ).map { case (ident, indexes) =>
    Ast.AssignmentArrayElementTarget[Ctx](ident, indexes)
  }
  def assignmentTarget[Unknown: P]: P[Ast.AssignmentTarget[Ctx]] = P(
    assignmentArrayElementTarget | assignmentSimpleTarget
  )
  def assign[Unknown: P]: P[Ast.Statement[Ctx]] =
    P(assignmentTarget.rep(1, ",") ~ "=" ~ expr).map { case (targets, expr) =>
      Ast.Assign(targets, expr)
    }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def parseType[Unknown: P](contractTypeCtor: Ast.TypeId => Type): P[Type] = {
    P(
      Lexer.typeId.map(id => Lexer.primTpes.getOrElse(id.name, contractTypeCtor(id))) |
        arrayType(parseType(contractTypeCtor))
    )
  }

  // use by-name parameter because of https://github.com/com-lihaoyi/fastparse/pull/204
  def arrayType[Unknown: P](baseType: => P[Type]): P[Type] = {
    P("[" ~ baseType ~ ";" ~ nonNegativeNum("array size") ~ "]").map { case (tpe, size) =>
      Type.FixedSizeArray(tpe, size)
    }
  }
  def funcArgument[Unknown: P]: P[Ast.Argument] =
    P(Lexer.mut ~ Lexer.ident ~ ":").flatMap { case (isMutable, ident) =>
      parseType(typeId => Type.Contract.local(typeId, ident)).map { tpe =>
        Ast.Argument(ident, tpe, isMutable)
      }
    }
  def funParams[Unknown: P]: P[Seq[Ast.Argument]] = P("(" ~ funcArgument.rep(0, ",") ~ ")")
  def returnType[Unknown: P]: P[Seq[Type]]        = P(simpleReturnType | bracketReturnType)
  def simpleReturnType[Unknown: P]: P[Seq[Type]] =
    P("->" ~ parseType(Type.Contract.stack)).map(tpe => Seq(tpe))
  def bracketReturnType[Unknown: P]: P[Seq[Type]] =
    P("->" ~ "(" ~ parseType(Type.Contract.stack).rep(0, ",") ~ ")")
  def funcTmp[Unknown: P]: P[FuncDefTmp[Ctx]] =
    P(
      annotation.rep(0) ~
        Lexer.FuncModifier.modifiers.rep(0) ~ Lexer
          .keyword("fn") ~/ Lexer.funcId ~ funParams ~ returnType ~ ("{" ~ statement.rep ~ "}").?
    ).map { case (annotations, modifiers, funcId, params, returnType, statements) =>
      if (modifiers.toSet.size != modifiers.length) {
        throw Compiler.Error(s"Duplicated function modifiers: $modifiers")
      } else {
        val isPublic = modifiers.contains(Lexer.FuncModifier.Pub)
        val (usePreapprovedAssets, useContractAssets) =
          Parser.extractAssetModifier(annotations, false, false)
        FuncDefTmp(
          Seq.empty,
          funcId,
          isPublic,
          usePreapprovedAssets,
          useContractAssets,
          params,
          returnType,
          statements
        )
      }
    }
  def func[Unknown: P]: P[Ast.FuncDef[Ctx]] = funcTmp.map { f =>
    f.body match {
      case Some(statements) =>
        Ast.FuncDef(
          f.annotations,
          f.id,
          f.isPublic,
          f.usePreapprovedAssets,
          f.useContractAssets,
          f.args,
          f.rtypes,
          statements
        )
      case None =>
        throw Compiler.Error(s"Function ${f.id.name} does not have function body")
    }
  }

  def eventFields[Unknown: P]: P[Seq[Ast.EventField]] = P("(" ~ eventField.rep(0, ",") ~ ")")
  def eventDef[Unknown: P]: P[Ast.EventDef] =
    P(Lexer.keyword("event") ~/ Lexer.typeId ~ eventFields)
      .map { case (typeId, fields) =>
        if (fields.length >= Instr.allLogInstrs.length) {
          throw Compiler.Error("Max 8 fields allowed for contract events")
        }
        Ast.EventDef(typeId, fields)
      }

  def funcCall[Unknown: P]: P[Ast.FuncCall[Ctx]] =
    callAbs.map { case (funcId, exprs) => Ast.FuncCall(funcId, exprs) }

  def block[Unknown: P]: P[Seq[Ast.Statement[Ctx]]]      = P("{" ~ statement.rep(1) ~ "}")
  def emptyBlock[Unknown: P]: P[Seq[Ast.Statement[Ctx]]] = P("{" ~ "}").map(_ => Seq.empty)
  def ifBranch[Unknown: P]: P[Ast.IfBranch[Ctx]] =
    P(Lexer.keyword("if") ~/ expr ~ block).map { case (condition, body) =>
      Ast.IfBranch(condition, body)
    }
  def elseIfBranch[Unknown: P]: P[Ast.IfBranch[Ctx]] =
    P(Lexer.keyword("else") ~ ifBranch)
  def elseBranch[Unknown: P]: P[ElseBranch[Ctx]] =
    P(Lexer.keyword("else") ~ (block | emptyBlock)).map(Ast.ElseBranch(_))
  def ifelse[Unknown: P]: P[Ast.IfElse[Ctx]] =
    P(ifBranch ~ elseIfBranch.rep(0) ~ elseBranch.?)
      .map { case (ifBranch, elseIfBranches, elseBranchOpt) =>
        if (elseIfBranches.nonEmpty && elseBranchOpt.isEmpty) {
          throw Compiler.Error(
            "If ... else if constructs should be terminated with an else statement"
          )
        }
        Ast.IfElse(ifBranch +: elseIfBranches, elseBranchOpt.getOrElse(ElseBranch(Seq.empty)))
      }

  def whileStmt[Unknown: P]: P[Ast.While[Ctx]] =
    P(Lexer.keyword("while") ~/ expr ~ block).map { case (expr, block) => Ast.While(expr, block) }

  def statement[Unknown: P]: P[Ast.Statement[Ctx]]

  def contractArgument[Unknown: P]: P[Ast.Argument] =
    P(Lexer.mut ~ Lexer.ident ~ ":").flatMap { case (isMutable, ident) =>
      parseType(typeId => Type.Contract.global(typeId, ident)).map { tpe =>
        Ast.Argument(ident, tpe, isMutable)
      }
    }

  def templateParams[Unknown: P]: P[Seq[Ast.Argument]] =
    P("(" ~ contractArgument.rep(1, ",") ~ ")").map { params =>
      val mutables = params.filter(_.isMutable)
      if (mutables.nonEmpty) {
        throw Compiler.Error(
          s"Template variables should be immutable: ${mutables.map(_.ident.name).mkString}"
        )
      }
      params
    }

  def eventField[Unknown: P]: P[Ast.EventField] =
    P(Lexer.ident ~ ":").flatMap { case (ident) =>
      parseType(typeId => Type.Contract.global(typeId, ident)).map { tpe =>
        Ast.EventField(ident, tpe)
      }
    }

  def annotationField[Unknown: P]: P[Ast.AnnotationField] =
    P(Lexer.ident ~ "=" ~ expr).map {
      case (ident, expr: Ast.Const[_]) =>
        Ast.AnnotationField(ident, expr.v)
      case _ =>
        throw Compiler.Error(s"Expect const value for annotation field, got ${expr}")
    }
  def annotationFields[Unknown: P]: P[Seq[Ast.AnnotationField]] =
    P("(" ~ annotationField.rep(1, ",") ~ ")")
  def annotation[Unknown: P]: P[Ast.Annotation] =
    P("@" ~ Lexer.ident ~ annotationFields.?).map { case (id, fieldsOpt) =>
      Ast.Annotation(id, fieldsOpt.getOrElse(Seq.empty))
    }
}

final case class FuncDefTmp[Ctx <: StatelessContext](
    annotations: Seq[Annotation],
    id: FuncId,
    isPublic: Boolean,
    usePreapprovedAssets: Boolean,
    useContractAssets: Boolean,
    args: Seq[Argument],
    rtypes: Seq[Type],
    body: Option[Seq[Statement[Ctx]]]
)

object Parser {
  def extractAssetModifier(
      annotations: Seq[Annotation],
      usePreapprovedAssetsDefault: Boolean,
      useContractAssetsDefault: Boolean
  ): (Boolean, Boolean) = {
    if (annotations.exists(_.id.name != "using")) {
      throw Compiler.Error(s"Generic annotation is not supported yet")
    } else {
      val usePreapprovedAssetsKey = "preapprovedAssets"
      val useContractAssetsKey    = "assetsInContract"
      annotations.headOption match {
        case Some(useAnnotation) =>
          val invalidKeys = useAnnotation.fields
            .filter(f =>
              f.ident.name != usePreapprovedAssetsKey && f.ident.name != useContractAssetsKey
            )
          if (invalidKeys.nonEmpty) {
            throw Compiler.Error(
              s"Invalid keys for use annotation: ${invalidKeys.map(_.ident.name).mkString(",")}"
            )
          }

          val usePreapprovedAssets =
            extractAnnotationBoolean(useAnnotation, usePreapprovedAssetsKey)
          val useContractAssets = extractAnnotationBoolean(useAnnotation, useContractAssetsKey)
          (
            usePreapprovedAssets.getOrElse(usePreapprovedAssetsDefault),
            useContractAssets.getOrElse(useContractAssetsDefault)
          )
        case None =>
          (usePreapprovedAssetsDefault, useContractAssetsDefault)
      }
    }
  }

  def extractAnnotationBoolean(annotation: Annotation, name: String): Option[Boolean] = {
    annotation.fields.find(_.ident.name == name).map(_.value) match {
      case Some(value: Val.Bool) => Some(value.v)
      case Some(_) =>
        throw Compiler.Error(s"Expect boolean for ${name} in annotation ${annotation.id.name}")
      case None => None
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
  def atom[Unknown: P]: P[Ast.Expr[StatelessContext]] =
    P(const | callExpr | contractConv | variable | parenExpr | arrayExpr)

  def statement[Unknown: P]: P[Ast.Statement[StatelessContext]] =
    P(varDef | assign | funcCall | ifelse | whileStmt | ret)

  def assetScript[Unknown: P]: P[Ast.AssetScript] =
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
  def atom[Unknown: P]: P[Ast.Expr[StatefulContext]] =
    P(
      const | callExpr | contractCallExpr | contractConv | variable | parenExpr | arrayExpr
    )

  def contractCallExpr[Unknown: P]: P[Ast.ContractCallExpr] =
    P((contractConv | variable) ~ "." ~ callAbs).map { case (obj, (callId, exprs)) =>
      Ast.ContractCallExpr(obj, callId, exprs)
    }

  def contractCall[Unknown: P]: P[Ast.ContractCall] =
    P((contractConv | variable) ~ "." ~ callAbs)
      .map { case (obj, (callId, exprs)) => Ast.ContractCall(obj, callId, exprs) }

  def statement[Unknown: P]: P[Ast.Statement[StatefulContext]] =
    P(varDef | assign | funcCall | contractCall | ifelse | whileStmt | ret | emitEvent)

  def contractParams[Unknown: P]: P[Seq[Ast.Argument]] = P("(" ~ contractArgument.rep(0, ",") ~ ")")

  def rawTxScript[Unknown: P]: P[Ast.TxScript] =
    P(
      annotation.rep ~
        Lexer.keyword(
          "TxScript"
        ) ~/ Lexer.typeId ~ templateParams.? ~ "{" ~ statement
          .rep(0) ~ func
          .rep(0) ~ "}"
    )
      .map { case (annotations, typeId, templateVars, mainStmts, funcs) =>
        if (mainStmts.isEmpty) {
          throw Compiler.Error(s"No main statements defined in TxScript ${typeId.name}")
        } else {
          val (usePreapprovedAssets, useContractAssets) =
            Parser.extractAssetModifier(annotations, true, false)
          Ast.TxScript(
            typeId,
            templateVars.getOrElse(Seq.empty),
            Ast.FuncDef.main(mainStmts, usePreapprovedAssets, useContractAssets) +: funcs
          )
        }
      }
  def txScript[Unknown: P]: P[Ast.TxScript] = P(Start ~ rawTxScript ~ End)

  def inheritanceTemplateVariable[Unknown: P]: P[Seq[Ast.Ident]] =
    P("<" ~ Lexer.ident.rep(1, ",") ~ ">").?.map(_.getOrElse(Seq.empty))
  def inheritanceFields[Unknown: P]: P[Seq[Ast.Ident]] =
    P("(" ~ Lexer.ident.rep(0, ",") ~ ")")
  def contractInheritance[Unknown: P]: P[Ast.ContractInheritance] =
    P(Lexer.typeId ~ inheritanceFields).map { case (typeId, fields) =>
      Ast.ContractInheritance(typeId, fields)
    }

  def interfaceImplementing[Unknown: P]: P[Seq[Ast.Inheritance]] =
    P(Lexer.keyword("implements") ~ (interfaceInheritance.rep(1, ",")))

  def contractExtending[Unknown: P]: P[Seq[Ast.Inheritance]] =
    P(Lexer.keyword("extends") ~ (contractInheritance.rep(1, ",")))

  def contractInheritances[Unknown: P]: P[Seq[Ast.Inheritance]] = {
    P(contractExtending.? ~ interfaceImplementing.?).map { case (extendingsOpt, implementingOpt) =>
      extendingsOpt.getOrElse(Seq.empty) ++ implementingOpt.getOrElse(Seq.empty)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def rawTxContract[Unknown: P]: P[Ast.TxContract] =
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
  def contract[Unknown: P]: P[Ast.TxContract] = P(Start ~ rawTxContract ~ End)

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def interfaceInheritance[Unknown: P]: P[Ast.InterfaceInheritance] =
    P(Lexer.typeId).map(Ast.InterfaceInheritance)
  def interfaceFunc[Unknown: P]: P[Ast.FuncDef[StatefulContext]] = {
    funcTmp.map { f =>
      f.body match {
        case None =>
          Ast.FuncDef(
            f.annotations,
            f.id,
            f.isPublic,
            f.usePreapprovedAssets,
            f.useContractAssets,
            f.args,
            f.rtypes,
            Seq.empty
          )
        case _ =>
          throw Compiler.Error(s"Interface function ${f.id.name} should not have function body")
      }
    }
  }
  def rawInterface[Unknown: P]: P[Ast.ContractInterface] =
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
        throw Compiler.Error(s"No function definition in Interface ${typeId.name}")
      } else {
        Ast.ContractInterface(
          typeId,
          funcs,
          events,
          inheritances.getOrElse(Seq.empty)
        )
      }
    }
  def interface[Unknown: P]: P[Ast.ContractInterface] = P(Start ~ rawInterface ~ End)

  def multiContract[Unknown: P]: P[Ast.MultiTxContract] =
    P(Start ~ (rawTxScript | rawTxContract | rawInterface).rep(1) ~ End)
      .map(Ast.MultiTxContract.apply)

  def state[Unknown: P]: P[Seq[Ast.Const[StatefulContext]]] =
    P("[" ~ constOrArray.rep(0, ",") ~ "]").map(_.flatten)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def constOrArray[Unknown: P]: P[Seq[Ast.Const[StatefulContext]]] = P(
    const.map(Seq(_)) |
      P("[" ~ constOrArray.rep(0, ",").map(_.flatten) ~ "]") |
      P("[" ~ constOrArray ~ ";" ~ nonNegativeNum("array size") ~ "]").map { case (consts, size) =>
        (0 until size).flatMap(_ => consts)
      }
  )

  def emitEvent[Unknown: P]: P[Ast.EmitEvent[StatefulContext]] =
    P("emit" ~ Lexer.typeId ~ "(" ~ expr.rep(0, ",") ~ ")")
      .map { case (typeId, exprs) => Ast.EmitEvent(typeId, exprs) }
}
