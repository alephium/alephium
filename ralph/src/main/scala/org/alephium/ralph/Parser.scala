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

import scala.collection.mutable.ArrayBuffer

import akka.util.ByteString
import fastparse._

import org.alephium.protocol.vm.{Instr, StatefulContext, StatelessContext, Val}
import org.alephium.ralph.Ast.{Annotation, Argument, FuncId, Statement}
import org.alephium.ralph.error.CompilerError
import org.alephium.ralph.error.FastParseExtension._
import org.alephium.util.{AVector, U256}

// scalastyle:off number.of.methods file.size.limit
@SuppressWarnings(
  Array(
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
abstract class Parser[Ctx <: StatelessContext] {
  implicit object RalphWhitespace extends Whitespace {
    def apply(ctx: fastparse.ParsingRun[_]): P[Unit] = {
      Lexer.emptyChars(ctx)
    }
  }

  def fileURI: Option[java.net.URI]

  lazy val Lexer: Lexer = new Lexer(fileURI)

  /*
   * PP: Positioned Parser
   * Help adding source index to the result, it works well on easy case, but it fails for
   * unknown reason when parser ends with is a `.rep` or `.opt`, also with our complex `expr` parser,
   * the end index then adds the trailing spaces.
   */
  def PP[Unknown: P, A, B <: Ast.Positioned](a: => P[A])(f: A => B): P[B] = {
    P(Index ~~ a ~~ Index).map { case (from, v, to) =>
      val q = f(v)
      if (q.sourceIndex.isDefined) {
        q
      } else {
        q.atSourceIndex(from, to, fileURI)
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def const[Unknown: P]: P[Ast.Const[Ctx]] =
    P(Lexer.typedNum | Lexer.bool | Lexer.bytes | Lexer.address).map(_.asInstanceOf[Ast.Const[Ctx]])

  def createArray1[Unknown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    PP("[" ~ expr.rep(1, ",") ~ "]") { elems =>
      Ast.CreateArrayExpr1(elems)
    }
  def createArray2[Unknown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    PP("[" ~ (expr ~ ";" ~ expr) ~ "]") { case (element, size) =>
      Ast.CreateArrayExpr2(element, size)
    }
  def arrayExpr[Unknown: P]: P[Ast.Expr[Ctx]] = P(createArray1 | createArray2)
  def variable[Unknown: P]: P[Ast.Variable[Ctx]] =
    PP(Lexer.ident | Lexer.constantIdent) { name =>
      Ast.Variable.apply[Ctx](name)
    }

  def variableIdOnly[Unknown: P]: P[Ast.Variable[Ctx]] =
    PP(Lexer.ident) { value =>
      Ast.Variable.apply[Ctx](value)
    }
  def alphTokenId[Unknown: P]: P[Ast.Expr[Ctx]] =
    PP(Lexer.token(Keyword.ALPH_CAPS)) { _ =>
      Ast.ALPHTokenId()
    }

  def alphAmount[Unknown: P]: P[Ast.Expr[Ctx]]                       = expr
  def tokenAmount[Unknown: P]: P[(Ast.Expr[Ctx], Ast.Expr[Ctx])]     = P(expr ~ ":" ~ expr)
  def amountList[Unknown: P]: P[Seq[(Ast.Expr[Ctx], Ast.Expr[Ctx])]] = P(tokenAmount.rep(0, ","))
  def approveAssetPerAddress[Unknown: P]: P[Ast.ApproveAsset[Ctx]] =
    PP(expr ~ "->" ~~ Index ~ amountList) { case (address, index, amounts) =>
      if (amounts.isEmpty) {
        throw CompilerError.`Expected non-empty asset(s) for address`(index, fileURI)
      }
      Ast.ApproveAsset(address, amounts)
    }
  def approveAssets[Unknown: P]: P[Seq[Ast.ApproveAsset[Ctx]]] =
    P("{" ~ approveAssetPerAddress.rep(1, ";") ~ ";".? ~ "}")
  def callAbs[Unknown: P]: P[Parser.CallAbs[Ctx]] =
    PP(Lexer.funcId ~ approveAssets.? ~ "(" ~ expr.rep(0, ",") ~ ")") {
      case (funcId, approveAssets, arguments) =>
        Parser.CallAbs(funcId, approveAssets.getOrElse(Seq.empty), arguments)
    }
  def callExpr[Unknown: P]: P[Ast.Expr[Ctx]] =
    PP((Lexer.typeId ~ ".").? ~ callAbs) {
      case (contractIdOpt, Parser.CallAbs(funcId, approveAssets, args)) =>
        contractIdOpt match {
          case Some(contractId) =>
            Ast.ContractStaticCallExpr(contractId, funcId, approveAssets, args)
          case None => Ast.CallExpr(funcId, approveAssets, args)
        }
    }
  def contractConv[Unknown: P]: P[Ast.ContractConv[Ctx]] =
    PP(Lexer.typeId ~ "(" ~ expr ~ ")") { case (typeId, expr) =>
      Ast.ContractConv(typeId, expr)
    }

  def chain[Unknown: P](p: => P[Ast.Expr[Ctx]], op: => P[Operator]): P[Ast.Expr[Ctx]] = {
    P(p ~ (op ~ p).rep).map { case (lhs, rhs) =>
      rhs.foldLeft(lhs) { case (acc, (op, right)) =>
        val sourceIndex = SourceIndex(acc.sourceIndex, right.sourceIndex)
        Ast.Binop(op, acc, right).atSourceIndex(sourceIndex)
      }
    }
  }

  def indexSelector[Unknown: P]: P[Ast.DataSelector[Ctx]] =
    P(Index ~~ "[" ~ expr ~ "]" ~~ Index).map { case (from, expr, to) =>
      Ast
        .IndexSelector(expr.overwriteSourceIndex(from, to, fileURI))
        .atSourceIndex(from, to, fileURI)
    }

  // Optimize chained comparisons
  def expr[Unknown: P]: P[Ast.Expr[Ctx]]    = P(chain(andExpr, Lexer.opOr))
  def andExpr[Unknown: P]: P[Ast.Expr[Ctx]] = P(chain(relationExpr, Lexer.opAnd))
  def relationExpr[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(arithExpr6 ~ comparison.?).flatMap {
      case (lhs, Some(op)) =>
        arithExpr6.map { rhs =>
          val sourceIndex = SourceIndex(lhs.sourceIndex, rhs.sourceIndex)
          Ast.Binop(op, lhs, rhs).atSourceIndex(sourceIndex)
        }
      case (lhs, None) => Pass(lhs)
    }
  def comparison[Unknown: P]: P[TestOperator] =
    P(Lexer.opEq | Lexer.opNe | Lexer.opLe | Lexer.opLt | Lexer.opGe | Lexer.opGt)
  def arithExpr6[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr5, Lexer.opBitOr))
  def arithExpr5[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr4, Lexer.opXor))
  def arithExpr4[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr3, Lexer.opBitAnd))
  def arithExpr3[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr2, Lexer.opSHL | Lexer.opSHR))
  def arithExpr2[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(
      chain(
        arithExpr1,
        Lexer.opByteVecAdd | Lexer.opAdd | Lexer.opSub | Lexer.opModAdd | Lexer.opModSub
      )
    )
  def arithExpr1[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr0, Lexer.opMul | Lexer.opDiv | Lexer.opMod | Lexer.opModMul))
  def arithExpr0[Unknown: P]: P[Ast.Expr[Ctx]] = P(chain(unaryExpr, Lexer.opExp | Lexer.opModExp))
  def unaryExpr[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(atom | PP((Lexer.opNot | Lexer.opNegate) ~ atom) { case (op, expr) =>
      Ast.UnaryOp.apply[Ctx](op, expr)
    })

  def atom[Unknown: P]: P[Ast.Expr[Ctx]]

  def structCtor[Unknown: P]: P[Ast.StructCtor[Ctx]] =
    PP(Lexer.typeId ~ "{" ~ P(Lexer.ident ~ P(":" ~ expr).?).rep(0, ",") ~ "}") {
      case (typeId, fields) =>
        if (fields.isEmpty) {
          throw Compiler.Error(s"No field definition in struct ${typeId.name}", typeId.sourceIndex)
        }
        Ast.StructCtor(typeId, fields)
    }

  def parenExpr[Unknown: P]: P[Ast.ParenExpr[Ctx]] =
    PP("(" ~ expr ~ ")") { case (ex) =>
      Ast.ParenExpr.apply[Ctx](ex)
    }

  private def ifElseExprBody[Unknown: P]: P[(Seq[Ast.Statement[Ctx]], Ast.Expr[Ctx])] =
    P("{" ~ statement.rep(0) ~ expr ~ "}" | expr.map(expr => (Seq.empty[Ast.Statement[Ctx]], expr)))
  def ifBranchExpr[Unknown: P]: P[Ast.IfBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`if`) ~ expr ~ ifElseExprBody).map {
      case (ifIndex, condition, (statements, expr)) =>
        val sourceIndex = SourceIndex(Some(ifIndex), expr.sourceIndex)
        Ast.IfBranchExpr(condition, statements, expr).atSourceIndex(sourceIndex)
    }
  def elseIfBranchExpr[Unknown: P]: P[Ast.IfBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ ifBranchExpr).map { case (elseIndex, ifBranch) =>
      val sourceIndex = SourceIndex(Some(elseIndex), ifBranch.sourceIndex)
      Ast
        .IfBranchExpr(ifBranch.condition, ifBranch.statements, ifBranch.expr)
        .atSourceIndex(sourceIndex)
    }
  def elseBranchExpr[Unknown: P]: P[Ast.ElseBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ ifElseExprBody).map { case (elseIndex, (statements, expr)) =>
      val sourceIndex = SourceIndex(Some(elseIndex), expr.sourceIndex)
      Ast.ElseBranchExpr(statements, expr).atSourceIndex(sourceIndex)
    }

  def rawIfElseExpr[Unknown: P]: P[Ast.IfElseExpr[Ctx]] =
    P(ifBranchExpr ~ elseIfBranchExpr.rep(0) ~~ Index ~ elseBranchExpr.?).map {
      case (ifBranch, elseIfBranches, _, Some(elseBranch)) =>
        val sourceIndex = SourceIndex(ifBranch.sourceIndex, elseBranch.sourceIndex)
        Ast.IfElseExpr(ifBranch +: elseIfBranches, elseBranch).atSourceIndex(sourceIndex)
      case (_, _, index, None) =>
        throw CompilerError.`Expected else statement`(index, fileURI)
    }

  def ifElseExpr[Unknown: P]: P[Ast.IfElseExpr[Ctx]] = P(Start ~ rawIfElseExpr ~ End)

  def stringLiteral[Unknown: P]: P[Ast.Const[Ctx]] =
    PP("b" ~ Lexer.string) { s =>
      Ast.Const(Val.ByteVec(ByteString.fromString(s)))
    }

  def ret[Unknown: P]: P[Ast.ReturnStmt[Ctx]] =
    P(Index ~~ normalRet.rep(1) ~~ Index).map { case (fromIndex, returnStmts, endIndex) =>
      if (returnStmts.length > 1) {
        throw Compiler.Error(
          "Consecutive return statements are not allowed",
          Some(SourceIndex(fromIndex, endIndex - fromIndex, fileURI))
        )
      } else {
        returnStmts(0)
      }
    }

  private def withOptionalParens[Unknown: P, T](parser: => P[T]) = P(parser | P("(" ~ parser ~ ")"))
  private def returnExprs[Unknown: P] = P(
    withOptionalParens(expr.rep(1, ",")) | Pass.map(_ => Seq.empty[Ast.Expr[Ctx]])
  )

  def normalRet[Unknown: P]: P[Ast.ReturnStmt[Ctx]] =
    P(Lexer.token(Keyword.`return`) ~/ returnExprs).map { case (returnIndex, returns) =>
      val too =
        returns.lastOption.flatMap(_.sourceIndex.map(_.endIndex)).getOrElse(returnIndex.endIndex)
      Ast.ReturnStmt.apply[Ctx](returns).atSourceIndex(returnIndex.index, too, fileURI)
    }

  def stringInterpolator[Unknown: P]: P[Ast.Expr[Ctx]] =
    PP("${" ~ expr ~ "}")(identity)

  def debug[Unknown: P]: P[Ast.Debug[Ctx]] =
    P("emit" ~~ Index ~ "Debug" ~/ "(" ~ Lexer.string(() => stringInterpolator) ~ ")" ~~ Index)
      .map { case (fromIndex, (stringParts, interpolationParts), endIndex) =>
        Ast
          .Debug(
            stringParts.map(s => Val.ByteVec(ByteString.fromString(s))),
            interpolationParts
          )
          .atSourceIndex(fromIndex, endIndex, fileURI)
      }

  def anonymousVar[Unknown: P]: P[Ast.VarDeclaration] = PP("_")(_ => Ast.AnonymousVar())
  def namedVar[Unknown: P]: P[Ast.NamedVar] =
    P(Index ~ Lexer.mut ~ Lexer.ident ~~ Index).map { case (from, mutable, id, to) =>
      Ast.NamedVar(mutable, id).atSourceIndex(from, to, fileURI)
    }

  def varDeclaration[Unknown: P]: P[Ast.VarDeclaration] = P(namedVar | anonymousVar)
  def varDeclarations[Unknown: P]: P[Seq[Ast.VarDeclaration]] = P(
    varDeclaration.map(Seq(_)) | "(" ~ varDeclaration.rep(1, ",") ~ ")"
  )
  def varDef[Unknown: P]: P[Ast.VarDef[Ctx]] =
    P(Lexer.token(Keyword.let) ~ varDeclarations ~/ "=" ~ expr).map { case (from, vars, expr) =>
      val sourceIndex = SourceIndex(Some(from), expr.sourceIndex)
      Ast.VarDef(vars, expr).atSourceIndex(sourceIndex)
    }
  def structFieldAlias[Unknown: P]: P[Ast.StructFieldAlias] =
    P(Lexer.mut ~ Lexer.ident ~ P(":" ~ Lexer.ident).?).map { case (isMutable, ident, alias) =>
      Ast.StructFieldAlias(isMutable, ident, alias)
    }
  def structDestruction[Unknown: P]: P[Ast.StructDestruction[Ctx]] =
    P(
      Lexer.token(Keyword.let) ~ Lexer.typeId ~/ "{" ~ structFieldAlias.rep(
        0,
        ","
      ) ~ "}" ~ "=" ~ expr
    ).map { case (from, id, vars, expr) =>
      val sourceIndex = SourceIndex(Some(from), expr.sourceIndex)
      if (vars.isEmpty) {
        throw Compiler.Error("No variable declaration", sourceIndex)
      }
      Ast.StructDestruction(id, vars, expr).atSourceIndex(sourceIndex)
    }

  def identSelector[Unknown: P]: P[Ast.DataSelector[Ctx]] = P(
    "." ~ Index ~ Lexer.ident ~ Index
  ).map { case (from, ident, to) =>
    Ast.IdentSelector(ident).atSourceIndex(from, to, fileURI)
  }
  def dataSelector[Unknown: P]: P[Ast.DataSelector[Ctx]] = P(identSelector | indexSelector)
  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def assignmentTarget[Unknown: P]: P[Ast.AssignmentTarget[Ctx]] =
    PP(Lexer.ident ~ dataSelector.rep(0)) { case (ident, selectors) =>
      if (selectors.isEmpty) {
        Ast.AssignmentSimpleTarget(ident)
      } else {
        Ast.AssignmentSelectedTarget(ident, selectors)
      }
    }

  def assign[Unknown: P]: P[Ast.Assign[Ctx]] =
    P(assignmentTarget.rep(1, ",") ~ "=" ~ expr).map { case (targets, expr) =>
      val sourceIndex = SourceIndex(targets.headOption.flatMap(_.sourceIndex), expr.sourceIndex)
      Ast.Assign(targets, expr).atSourceIndex(sourceIndex)
    }

  def compoundAssignOperator[Unknown: P]: P[CompoundAssignmentOperator] =
    Lexer.opAddAssign | Lexer.opSubAssign | Lexer.opMulAssign | Lexer.opDivAssign
  def compoundAssign[Unknown: P]: P[Ast.CompoundAssign[Ctx]] =
    P(assignmentTarget ~ compoundAssignOperator ~ expr).map { case (target, op, expr) =>
      val sourceIndex = SourceIndex(target.sourceIndex, expr.sourceIndex)
      Ast.CompoundAssign(target, op, expr).atSourceIndex(sourceIndex)
    }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def parseType[Unknown: P](contractTypeCtor: Ast.TypeId => Type.NamedType): P[Type] = {
    P(
      // Lexer.primTpes currently can't have source index as they are case objects
      Lexer.typeId.map(id =>
        Lexer.primTpes.getOrElse(id.name, contractTypeCtor(id).atSourceIndex(id.sourceIndex))
      ) |
        arrayType(parseType(contractTypeCtor))
    )
  }

  // use by-name parameter because of https://github.com/com-lihaoyi/fastparse/pull/204
  def arrayType[Unknown: P](baseType: => P[Type]): P[Type] = {
    PP("[" ~ baseType ~ ";" ~ expr ~ "]") { case (tpe, size) =>
      size match {
        case Ast.Const(Val.U256(value)) => Type.FixedSizeArray(tpe, Left(value.toBigInt.intValue()))
        case _                          => Type.FixedSizeArray(tpe, Right(size))
      }
    }
  }
  def argument[Unknown: P](
      allowMutable: Boolean
  )(contractTypeCtor: Ast.TypeId => Type.NamedType): P[Ast.Argument] =
    P(Index ~ Lexer.unused ~ Lexer.mutMaybe(allowMutable) ~ Lexer.ident ~ ":").flatMap {
      case (fromIndex, isUnused, isMutable, ident) =>
        P(parseType(contractTypeCtor) ~~ Index).map { case (tpe, endIndex) =>
          Ast.Argument(ident, tpe, isMutable, isUnused).atSourceIndex(fromIndex, endIndex, fileURI)
        }
    }
  def funcArgument[Unknown: P]: P[Ast.Argument] =
    argument(allowMutable = true)(Type.NamedType.apply)
  def funParams[Unknown: P]: P[Seq[Ast.Argument]] = P("(" ~ funcArgument.rep(0, ",") ~ ")")
  def returnType[Unknown: P]: P[Seq[Type]]        = P(simpleReturnType | bracketReturnType)
  def simpleReturnType[Unknown: P]: P[Seq[Type]] =
    P("->" ~ parseType(Type.NamedType.apply)).map(tpe => Seq(tpe))
  def bracketReturnType[Unknown: P]: P[Seq[Type]] =
    P("->" ~ "(" ~ parseType(Type.NamedType.apply).rep(0, ",") ~ ")")
  // scalastyle:off method.length
  def funcTmp[Unknown: P]: P[FuncDefTmp[Ctx]] =
    PP(
      annotation.rep(0) ~
        Index ~ Lexer.FuncModifier.modifiers.rep(0) ~~ Index ~ Lexer
          .token(
            Keyword.fn
          ) ~/ Lexer.funcId ~ funParams ~ returnType ~ ("{" ~ statement.rep ~ "}").?
    ) {
      case (
            annotations,
            modifiersStart,
            modifiers,
            modifiersEnd,
            _,
            funcId,
            params,
            returnType,
            statements
          ) =>
        if (modifiers.toSet.size != modifiers.length) {
          throw Compiler.Error(
            s"Duplicated function modifiers: $modifiers",
            Some(SourceIndex(modifiersStart, modifiersEnd - modifiersStart, fileURI))
          )
        } else {
          val isPublic = modifiers.contains(Lexer.FuncModifier.Pub)
          val validAnnotationIds =
            AVector(Parser.FunctionUsingAnnotation.id, Parser.FunctionInlineAnnotation.id)
          Parser.checkAnnotations(annotations, validAnnotationIds, "function")
          val usingAnnotation = Parser.FunctionUsingAnnotation.extractFields(
            annotations,
            Parser.FunctionUsingAnnotationFields(
              preapprovedAssets = false,
              assetsInContract = Ast.NotUseContractAssets,
              payToContractOnly = false,
              checkExternalCaller = true,
              updateFields = false,
              methodIndex = None
            )
          )
          if (usingAnnotation.payToContractOnly && usingAnnotation.assetsInContract.assetsEnabled) {
            throw Compiler.Error(
              s"Can only enable one of the two annotations: @using(assetsInContract = true/enforced) or @using(payToContractOnly = true)",
              SourceIndex(
                annotations.headOption.flatMap(_.fields.headOption.flatMap(_.sourceIndex)),
                annotations.lastOption.flatMap(_.fields.lastOption.flatMap(_.sourceIndex))
              )
            )
          }
          val inline = Parser.FunctionInlineAnnotation.extractFields(annotations, false)
          FuncDefTmp(
            annotations,
            funcId,
            isPublic,
            usingAnnotation.preapprovedAssets,
            usingAnnotation.assetsInContract,
            usingAnnotation.payToContractOnly,
            usingAnnotation.checkExternalCaller,
            usingAnnotation.updateFields,
            usingAnnotation.methodIndex,
            inline,
            params,
            returnType,
            statements
          )
        }
    }
  def func[Unknown: P]: P[Ast.FuncDef[Ctx]] = funcTmp.map { f =>
    if (f.useMethodIndex.nonEmpty) {
      throw Compiler.Error(
        "The `methodIndex` annotation can only be used for interface functions",
        f.id.sourceIndex
      )
    }
    Ast
      .FuncDef(
        f.annotations,
        f.id,
        f.isPublic,
        f.usePreapprovedAssets,
        f.useContractAssets,
        f.usePayToContractOnly,
        f.useCheckExternalCaller,
        f.useUpdateFields,
        f.useMethodIndex,
        f.inline,
        f.args,
        f.rtypes,
        f.body
      )
      .atSourceIndex(f.sourceIndex)
  }

  def eventFields[Unknown: P]: P[Seq[Ast.EventField]] = P("(" ~ eventField.rep(0, ",") ~ ")")
  def eventDef[Unknown: P]: P[Ast.EventDef] =
    P(Lexer.token(Keyword.event) ~/ Lexer.typeId ~ eventFields ~~ Index)
      .map { case (eventIndex, typeId, fields, endIndex) =>
        if (fields.length >= Instr.allLogInstrs.length) {
          throw Compiler.Error(
            "Max 8 fields allowed for contract events",
            Some(SourceIndex(eventIndex.index, endIndex - eventIndex.index, fileURI))
          )
        }
        if (typeId.name == "Debug") {
          throw Compiler.Error("Debug is a built-in event name", typeId.sourceIndex)
        }
        Ast.EventDef(typeId, fields).atSourceIndex(eventIndex.index, endIndex, fileURI)
      }

  def funcCall[Unknown: P]: P[Ast.Statement[Ctx]] =
    (Index ~ (Lexer.typeId ~ ".").? ~ callAbs ~~ Index).map {
      case (fromIndex, contractIdOpt, Parser.CallAbs(funcId, approveAssets, exprs), endIndex) =>
        contractIdOpt match {
          case Some(contractId) =>
            Ast
              .StaticContractFuncCall(contractId, funcId, approveAssets, exprs)
              .atSourceIndex(fromIndex, endIndex, fileURI)
          case None =>
            Ast.FuncCall(funcId, approveAssets, exprs).atSourceIndex(fromIndex, endIndex, fileURI)
        }
    }

  def block[Unknown: P]: P[Seq[Ast.Statement[Ctx]]]      = P("{" ~ statement.rep(1) ~ "}")
  def emptyBlock[Unknown: P]: P[Seq[Ast.Statement[Ctx]]] = P("{" ~ "}").map(_ => Seq.empty)
  def ifBranchStmt[Unknown: P]: P[Ast.IfBranchStatement[Ctx]] =
    P(Lexer.token(Keyword.`if`) ~ expr ~ block ~~ Index).map {
      case (ifIndex, condition, body, endIndex) =>
        Ast.IfBranchStatement(condition, body).atSourceIndex(ifIndex.index, endIndex, fileURI)
    }
  def elseIfBranchStmt[Unknown: P]: P[Ast.IfBranchStatement[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ ifBranchStmt).map { case (elseIndex, ifBranch) =>
      val sourceIndex = SourceIndex(Some(elseIndex), ifBranch.sourceIndex)
      Ast.IfBranchStatement(ifBranch.condition, ifBranch.body).atSourceIndex(sourceIndex)
    }
  def elseBranchStmt[Unknown: P]: P[Ast.ElseBranchStatement[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ (block | emptyBlock) ~~ Index).map {
      case (index, statement, endIndex) =>
        Ast.ElseBranchStatement(statement).atSourceIndex(index.index, endIndex, fileURI)
    }
  def ifelseStmt[Unknown: P]: P[Ast.IfElseStatement[Ctx]] =
    P(ifBranchStmt ~ elseIfBranchStmt.rep(0) ~ elseBranchStmt.?)
      .map { case (ifBranch, elseIfBranches, elseBranchOpt) =>
        if (elseIfBranches.nonEmpty && elseBranchOpt.isEmpty) {
          throw Compiler.Error(
            "If ... else if constructs should be terminated with an else statement",
            ifBranch.sourceIndex
          )
        }
        Ast.IfElseStatement(ifBranch +: elseIfBranches, elseBranchOpt)
      }

  def whileStmt[Unknown: P]: P[Ast.While[Ctx]] =
    P(Lexer.token(Keyword.`while`) ~/ "(" ~ expr ~ ")" ~ block ~~ Index).map {
      case (whileIndex, expr, block, endIndex) =>
        Ast.While(expr, block).atSourceIndex(whileIndex.index, endIndex, fileURI)
    }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def forLoopStmt[Unknown: P]: P[Ast.ForLoop[Ctx]] =
    P(
      Lexer.token(
        Keyword.`for`
      ) ~/ "(" ~ statement.? ~ ";" ~ expr ~ ";" ~ statement.? ~ ")" ~ block ~~ Index
    )
      .map { case (forIndex, initializeOpt, condition, updateOpt, body, endIndex) =>
        if (initializeOpt.isEmpty) {
          throw Compiler.Error("No initialize statement in for loop", Some(forIndex))
        }
        if (updateOpt.isEmpty) {
          throw Compiler.Error("No update statement in for loop", Some(forIndex))
        }
        Ast
          .ForLoop(initializeOpt.get, condition, updateOpt.get, body)
          .atSourceIndex(forIndex.index, endIndex, fileURI)
      }

  def statement[Unknown: P]: P[Ast.Statement[Ctx]]

  def contractField[Unknown: P](allowMutable: Boolean): P[Ast.Argument] =
    argument(allowMutable)(Type.NamedType.apply)

  def templateParams[Unknown: P]: P[Seq[Ast.Argument]] =
    P("(" ~ contractField(allowMutable = false).rep(0, ",") ~ ")")

  def field[Unknown: P]: P[(Ast.Ident, Type)] = P(Lexer.ident ~ ":").flatMap { ident =>
    parseType(Type.NamedType.apply).map { tpe => (ident, tpe) }
  }

  def eventField[Unknown: P]: P[Ast.EventField] = P(Index ~ field ~~ Index).map {
    case (from, (ident, tpe), to) =>
      Ast.EventField(ident, tpe).atSourceIndex(from, to, fileURI)
  }

  def structField[Unknown: P]: P[Ast.StructField] = PP(
    Lexer.mut ~ Lexer.ident ~ ":" ~ parseType(Type.NamedType.apply)
  ) { case (mutable, ident, tpe) =>
    Ast.StructField(ident, mutable, tpe)
  }
  def rawStruct[Unknown: P]: P[Ast.Struct] =
    PP(Lexer.token(Keyword.struct) ~/ Lexer.typeId ~ "{" ~ structField.rep(0, ",") ~ "}" ~~ Index) {
      case (structIndex, id, fields, endIndex) =>
        val sourceIndex = SourceIndex(structIndex.index, endIndex - structIndex.index, fileURI)
        if (fields.isEmpty) {
          throw Compiler.Error(s"No field definition in struct ${id.name}", Some(sourceIndex))
        }
        Ast.UniqueDef.checkDuplicates(fields, "struct fields")
        Ast.Struct(id, fields).atSourceIndex(Some(sourceIndex))
    }
  def struct[Unknown: P]: P[Ast.Struct] = P(Start ~ rawStruct ~ End)

  def enforceUsingContractAssets[Unknown: P]: P[Ast.AnnotationField[Ctx]] =
    P(
      Index ~ Parser.FunctionUsingAnnotation.useContractAssetsKey ~ "=" ~ Index ~ "enforced" ~~ Index
    )
      .map { case (from, valueFrom, to) =>
        Ast
          .AnnotationField(
            Ast.Ident(Parser.FunctionUsingAnnotation.useContractAssetsKey),
            Ast.Const[Ctx](Val.Enforced).atSourceIndex(valueFrom, to, fileURI)
          )
          .atSourceIndex(from, to, fileURI)
      }
  def annotationField[Unknown: P]: P[Ast.AnnotationField[Ctx]] =
    P(Index ~ Lexer.ident ~ "=" ~ expr ~~ Index).map {
      case (fromIndex, ident, expr: Ast.Const[_], endIndex) =>
        Ast.AnnotationField(ident, expr).atSourceIndex(fromIndex, endIndex, fileURI)
      case (_, _, expr, _) =>
        throw Compiler.Error(
          s"Expect const value for annotation field, got ${expr}",
          expr.sourceIndex
        )
    }
  def annotationFields[Unknown: P]: P[Seq[Ast.AnnotationField[Ctx]]] =
    P("(" ~ (enforceUsingContractAssets | annotationField).rep(1, ",") ~ ")")
  def annotation[Unknown: P]: P[Ast.Annotation[Ctx]] =
    P(Index ~ "@" ~ Lexer.ident ~ annotationFields.? ~~ Index).map {
      case (fromIndex, id, fieldsOpt, endIndex) =>
        Ast
          .Annotation(id, fieldsOpt.getOrElse(Seq.empty))
          .atSourceIndex(fromIndex, endIndex, fileURI)
    }

  def constantVarDef[Unknown: P]: P[Ast.ConstantVarDef[Ctx]] =
    P(Lexer.token(Keyword.const) ~/ Lexer.constantIdent ~ "=" ~ expr)
      .map { case (from, ident, expr) =>
        val sourceIndex = SourceIndex(Some(from), expr.sourceIndex)
        Ast.ConstantVarDef(ident, expr).atSourceIndex(sourceIndex)
      }

  def enumFieldSelector[Unknown: P]: P[Ast.EnumFieldSelector[Ctx]] =
    PP(Lexer.typeId ~ "." ~ Lexer.constantIdent) { case (enumId, field) =>
      Ast.EnumFieldSelector(enumId, field)
    }

  def enumField[Unknown: P]: P[Ast.RawEnumField[Ctx]] =
    PP(Lexer.constantIdent ~ ("=" ~ (const | stringLiteral)).?) { case (ident, valueOpt) =>
      Ast.RawEnumField(ident, valueOpt)
    }

  @SuppressWarnings(
    Array("org.wartremover.warts.OptionPartial", "org.wartremover.warts.IterableOps")
  )
  def rawEnumDef[Unknown: P]: P[Ast.EnumDef[Ctx]] =
    PP(Lexer.token(Keyword.`enum`) ~/ Lexer.typeId ~ "{" ~ enumField.rep ~ "}") {
      case (enumIndex, id, rawFields) =>
        if (rawFields.isEmpty) {
          val sourceIndex = SourceIndex(Some(enumIndex), id.sourceIndex)
          throw Compiler.Error(s"No field definition in Enum ${id.name}", sourceIndex)
        }

        val firstField = rawFields.head.validateAsFirstField()
        rawFields.tail.foreach(_.validate(id.name, firstField.value.v))

        val fields = if (firstField.value.v.tpe != Val.U256) {
          rawFields.map { case rawField @ Ast.RawEnumField(ident, valueOpt) =>
            Ast.EnumField(ident, valueOpt.get).atSourceIndex(rawField.sourceIndex)
          }
        } else {
          val (_, allFields) =
            rawFields.tail.foldLeft(
              (firstField.value.v.asInstanceOf[Val.U256].v, Seq(firstField))
            ) { case ((currentValue, fields), rawField @ Ast.RawEnumField(ident, valueOpt)) =>
              val (newValue, value) = valueOpt match {
                case Some(v) => (v.v.asInstanceOf[Val.U256].v, v)
                case None =>
                  val nextValue = currentValue
                    .add(U256.One)
                    .getOrElse(
                      throw Compiler.Error(
                        s"Enum field ${ident.name} value overflows, it must not exceed ${U256.MaxValue}",
                        ident.sourceIndex
                      )
                    )
                  (
                    nextValue,
                    Ast.Const[Ctx](Val.U256(nextValue))
                  )
              }
              (newValue, fields :+ Ast.EnumField(ident, value).atSourceIndex(rawField.sourceIndex))
            }
          allFields
        }

        Ast.UniqueDef.checkDuplicates(fields, "enum fields")
        if (fields.distinctBy(_.value.v).size != fields.length) {
          throw Compiler.Error(s"Fields have the same value in Enum ${id.name}", id.sourceIndex)
        }
        Ast.EnumDef(id, fields)
    }

  def enumDef[Unknown: P]: P[Ast.EnumDef[Ctx]] = P(Start ~ rawEnumDef ~ End)
}

final case class FuncDefTmp[Ctx <: StatelessContext](
    annotations: Seq[Annotation[Ctx]],
    id: FuncId,
    isPublic: Boolean,
    usePreapprovedAssets: Boolean,
    useContractAssets: Ast.ContractAssetsAnnotation,
    usePayToContractOnly: Boolean,
    useCheckExternalCaller: Boolean,
    useUpdateFields: Boolean,
    useMethodIndex: Option[Int],
    inline: Boolean,
    args: Seq[Argument],
    rtypes: Seq[Type],
    body: Option[Seq[Statement[Ctx]]]
) extends Ast.Positioned

object Parser {

  final case class CallAbs[Ctx <: StatelessContext](
      funcId: Ast.FuncId,
      approveAssets: Seq[Ast.ApproveAsset[Ctx]],
      args: Seq[Ast.Expr[Ctx]]
  ) extends Ast.Positioned

  sealed trait RalphAnnotation[T] {
    def id: String
    def keys: AVector[String]
    def validate[Ctx <: StatelessContext](
        annotations: Seq[Ast.Annotation[Ctx]]
    ): Option[Annotation[Ctx]] = {
      annotations.filter(_.id.name == id) match {
        case Seq(result @ annotation) =>
          val duplicateKeys = keys.filter(key => annotation.fields.count(_.ident.name == key) > 1)
          if (duplicateKeys.nonEmpty) {
            throw Compiler.Error(
              s"These keys are defined multiple times: ${duplicateKeys.mkString(",")}",
              annotation.sourceIndex
            )
          }
          val invalidKeys = annotation.fields.filter(f => !keys.contains(f.ident.name))
          if (invalidKeys.nonEmpty) {
            throw Compiler.Error(
              s"Invalid keys for @$id annotation: ${invalidKeys.map(_.ident.name).mkString(",")}",
              annotation.sourceIndex
            )
          }
          Some(result)
        case Nil => None
        case list =>
          throw Compiler.Error(
            s"There are duplicate annotations: $id",
            list.headOption.flatMap(_.sourceIndex)
          )
      }
    }

    final def extractField[V <: Val, Ctx <: StatelessContext](
        annotation: Ast.Annotation[Ctx],
        key: String,
        tpe: Val.Type
    ): Option[V] = {
      annotation.fields.find(_.ident.name == key) match {
        case Some(Ast.AnnotationField(_, Ast.Const(value: V @unchecked))) if tpe == value.tpe =>
          Some(value)
        case Some(field) =>
          throw Compiler.Error(s"Expect $tpe for $key in annotation @$id", field.sourceIndex)
        case None => None
      }
    }

    final def extractField[V <: Val, Ctx <: StatelessContext](
        annotation: Ast.Annotation[Ctx],
        key: String,
        default: V
    ): V = {
      extractField[V, Ctx](annotation, key, default.tpe).getOrElse(default)
    }

    final def extractFields[Ctx <: StatelessContext](
        annotations: Seq[Ast.Annotation[Ctx]],
        default: T
    ): T = {
      validate(annotations).map(extractFields(_, default)).getOrElse(default)
    }
    def extractFields[Ctx <: StatelessContext](annotation: Ast.Annotation[Ctx], default: T): T
  }

  final case class FunctionUsingAnnotationFields(
      preapprovedAssets: Boolean,
      assetsInContract: Ast.ContractAssetsAnnotation,
      payToContractOnly: Boolean,
      checkExternalCaller: Boolean,
      updateFields: Boolean,
      methodIndex: Option[Int]
  )

  object FunctionUsingAnnotation extends RalphAnnotation[FunctionUsingAnnotationFields] {
    val id: String                = "using"
    val usePreapprovedAssetsKey   = "preapprovedAssets"
    val useContractAssetsKey      = "assetsInContract"
    val usePayToContractOnly      = "payToContractOnly"
    val useCheckExternalCallerKey = "checkExternalCaller"
    val useUpdateFieldsKey        = "updateFields"
    val useMethodIndexKey         = "methodIndex"
    val keys: AVector[String] = AVector(
      usePreapprovedAssetsKey,
      useContractAssetsKey,
      usePayToContractOnly,
      useCheckExternalCallerKey,
      useUpdateFieldsKey,
      useMethodIndexKey
    )

    private def extractUseContractAsset[Ctx <: StatelessContext](
        annotation: Annotation[Ctx]
    ): Ast.ContractAssetsAnnotation = {
      annotation.fields.find(_.ident.name == useContractAssetsKey) match {
        case Some(field @ Ast.AnnotationField(_, const)) =>
          const.v match {
            case Val.False    => Ast.NotUseContractAssets
            case Val.True     => Ast.UseContractAssets
            case Val.Enforced => Ast.EnforcedUseContractAssets
            case _ =>
              throw Compiler.Error(
                "Invalid assetsInContract annotation, expected true/false/enforced",
                field.sourceIndex
              )
          }
        case None => Ast.NotUseContractAssets
      }
    }

    def extractFields[Ctx <: StatelessContext](
        annotation: Ast.Annotation[Ctx],
        default: FunctionUsingAnnotationFields
    ): FunctionUsingAnnotationFields = {
      val methodIndex =
        extractField[Val.U256, Ctx](annotation, useMethodIndexKey, Val.U256).flatMap(_.v.toInt)
      methodIndex match {
        case Some(index) =>
          if (index < 0 || index > 0xff) {
            throw Compiler.Error(
              s"Invalid method index $index, expecting a value in the range [0, 0xff]",
              annotation.fields.find(_.ident.name == useMethodIndexKey).flatMap(_.sourceIndex)
            )
          }
        case None => ()
      }
      FunctionUsingAnnotationFields(
        extractField(annotation, usePreapprovedAssetsKey, Val.Bool(default.preapprovedAssets)).v,
        extractUseContractAsset(annotation),
        extractField(annotation, usePayToContractOnly, Val.Bool(default.payToContractOnly)).v,
        extractField(
          annotation,
          useCheckExternalCallerKey,
          Val.Bool(default.checkExternalCaller)
        ).v,
        extractField(annotation, useUpdateFieldsKey, Val.Bool(default.updateFields)).v,
        methodIndex
      )
    }
  }

  object FunctionInlineAnnotation extends RalphAnnotation[Boolean] {
    val id: String            = "inline"
    val keys: AVector[String] = AVector.empty

    def extractFields[Ctx <: StatelessContext](
        annotation: Annotation[Ctx],
        default: Boolean
    ): Boolean = {
      true
    }
  }

  final case class InterfaceStdFields(id: ByteString)

  object InterfaceStdAnnotation extends RalphAnnotation[Option[InterfaceStdFields]] {
    val id: String            = "std"
    val keys: AVector[String] = AVector("id")

    def extractFields[Ctx <: StatelessContext](
        annotation: Annotation[Ctx],
        default: Option[InterfaceStdFields]
    ): Option[InterfaceStdFields] = {
      extractField[Val.ByteVec, Ctx](annotation, keys(0), Val.ByteVec).map { stdId =>
        if (stdId.bytes.isEmpty) {
          throw Compiler.Error(
            "The field id of the @std annotation must be a non-empty ByteVec",
            annotation.sourceIndex
          )
        }
        InterfaceStdFields(Ast.StdInterfaceIdPrefix ++ stdId.bytes)
      }
    }
  }

  final case class InterfaceUsingAnnotationFields(methodSelector: Boolean)
  object InterfaceUsingAnnotation extends RalphAnnotation[InterfaceUsingAnnotationFields] {
    val id: String            = "using"
    val keys: AVector[String] = AVector("methodSelector")

    def extractFields[Ctx <: StatelessContext](
        annotation: Annotation[Ctx],
        default: InterfaceUsingAnnotationFields
    ): InterfaceUsingAnnotationFields = {
      val value =
        extractField[Val.Bool, Ctx](annotation, keys(0), Val.Bool(default.methodSelector)).v
      InterfaceUsingAnnotationFields(value)
    }
  }

  final case class ContractStdFields(enabled: Boolean)

  object ContractStdAnnotation extends RalphAnnotation[Option[ContractStdFields]] {
    val id: String            = "std"
    val keys: AVector[String] = AVector("enabled")

    def extractFields[Ctx <: StatelessContext](
        annotation: Annotation[Ctx],
        default: Option[ContractStdFields]
    ): Option[ContractStdFields] = {
      extractField[Val.Bool, Ctx](annotation, keys(0), Val.Bool).map(field =>
        ContractStdFields(field.v)
      )
    }
  }

  def checkAnnotations[Ctx <: StatelessContext](
      annotations: Seq[Annotation[Ctx]],
      ids: AVector[String],
      label: String
  ): Unit = {
    annotations.find(a => !ids.contains(a.id.name)) match {
      case Some(annotation) =>
        throw Compiler.Error(
          s"Invalid annotation ${annotation.id.name}, $label only supports these annotations: ${ids
              .mkString(",")}",
          annotation.sourceIndex
        )
      case None => ()
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
class StatelessParser(val fileURI: Option[java.net.URI]) extends Parser[StatelessContext] {
  def atom[Unknown: P]: P[Ast.Expr[StatelessContext]] =
    P(
      const | stringLiteral | alphTokenId | loadData | callExpr | contractConv |
        structCtor | variable | parenExpr | arrayExpr | rawIfElseExpr
    )

  private def loadDataBase[Unknown: P] = P(
    (callExpr | structCtor | variableIdOnly | parenExpr | arrayExpr) ~ dataSelector.rep(1)
  )
  def loadData[Unknown: P]: P[Ast.Expr[StatelessContext]] =
    loadDataBase.map { case (base, selectors) =>
      val sourceIndex = SourceIndex(base.sourceIndex, selectors.lastOption.flatMap(_.sourceIndex))
      Ast.LoadDataBySelectors(base, selectors).atSourceIndex(sourceIndex)
    }

  def statement[Unknown: P]: P[Ast.Statement[StatelessContext]] =
    P(
      varDef | structDestruction | assign | compoundAssign | debug | funcCall | ifelseStmt | whileStmt | forLoopStmt | ret
    )

  private def globalDefinitions[Unknown: P]: P[Ast.GlobalDefinition] = P(
    rawStruct | constantVarDef | rawEnumDef
  )

  def assetScript[Unknown: P]: P[(Ast.AssetScript, Ast.GlobalState[StatelessContext])] =
    P(
      Start ~ globalDefinitions.rep(0) ~ Lexer.token(Keyword.AssetScript) ~/ Lexer.typeId ~
        templateParams.? ~ "{" ~ func.rep(1) ~ "}" ~~ Index ~ endOfInput(fileURI)
    ).map { case (defs, assetIndex, typeId, templateVars, funcs, endIndex) =>
      val globalState = Ast.GlobalState.from[StatelessContext](defs)
      val assetScript = Ast
        .AssetScript(typeId, templateVars.getOrElse(Seq.empty), funcs.map(_.withOrigin(typeId)))
        .atSourceIndex(assetIndex.index, endIndex, fileURI)
      (assetScript, globalState)
    }
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
class StatefulParser(val fileURI: Option[java.net.URI]) extends Parser[StatefulContext] {
  def atom[Unknown: P]: P[Ast.Expr[StatefulContext]] =
    P(
      const | stringLiteral | alphTokenId | mapContains | contractCallOrLoadData | callExpr | contractConv |
        enumFieldSelector | structCtor | variable | parenExpr | arrayExpr | rawIfElseExpr
    )

  def mapKeyType[Unknown: P]: P[Type] = {
    P(Index ~ parseType(Type.NamedType.apply) ~ Index).map { case (from, tpe, to) =>
      if (!tpe.isPrimitive) {
        val sourceIndex = Some(SourceIndex(from, to - from, fileURI))
        throw Compiler.Error("The key type of map can only be primitive type", sourceIndex)
      }
      tpe
    }
  }

  def mapDef[Unknown: P]: P[Ast.MapDef] = {
    PP(
      Lexer.token(Keyword.`mapping`) ~ "[" ~ mapKeyType ~ ","
        ~ parseType(Type.NamedType.apply) ~ "]" ~ Lexer.ident
    ) { case (_, key, value, ident) =>
      Ast.MapDef(ident, Type.Map(key, value))
    }
  }

  private def contractCallOrLoadData(
      base: Ast.Expr[StatefulContext],
      selectorOrCallAbss: Seq[Ast.Positioned]
  ): Ast.Expr[StatefulContext] =
    selectorOrCallAbss.foldLeft(base)((acc, selectorOrCallAbs) => {
      val sourceIndex = SourceIndex(acc.sourceIndex, selectorOrCallAbs.sourceIndex)
      val expr = selectorOrCallAbs match {
        case selector: Ast.DataSelector[StatefulContext @unchecked] =>
          acc match {
            case Ast.LoadDataBySelectors(base, selectors) =>
              Ast.LoadDataBySelectors(base, selectors :+ selector)
            case _ => Ast.LoadDataBySelectors(acc, Seq(selector))
          }
        case callAbs: Parser.CallAbs[StatefulContext @unchecked] =>
          Ast.ContractCallExpr(acc, callAbs.funcId, callAbs.approveAssets, callAbs.args)
      }
      expr.atSourceIndex(sourceIndex)
    })

  private def contractCallOrLoadDataBase[Unknown: P] = P(
    (callExpr | contractConv | structCtor | variableIdOnly | parenExpr | arrayExpr)
      ~ (("." ~ callAbs) | dataSelector).rep(1)
  )

  def contractCallOrLoadData[Unknown: P]: P[Ast.Expr[StatefulContext]] =
    contractCallOrLoadDataBase.map { case (base, selectorOrCallAbss) =>
      contractCallOrLoadData(base, selectorOrCallAbss)
    }

  def mapContains[Unknown: P]: P[Ast.Expr[StatefulContext]] =
    P(Index ~ Lexer.ident ~ ".contains!" ~ "(" ~ expr ~ ")" ~~ Index).map {
      case (fromIndex, ident, index, endIndex) =>
        Ast.MapContains(ident, index).atSourceIndex(fromIndex, endIndex, fileURI)
    }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def contractCall[Unknown: P]: P[Ast.Statement[StatefulContext]] =
    contractCallOrLoadDataBase.map { case (base, selectorOrCallAbss) =>
      val obj         = contractCallOrLoadData(base, selectorOrCallAbss.init)
      val last        = selectorOrCallAbss.last
      val sourceIndex = SourceIndex(obj.sourceIndex, last.sourceIndex)
      last match {
        case callAbs: Parser.CallAbs[StatefulContext @unchecked] =>
          Ast
            .ContractCall(obj, callAbs.funcId, callAbs.approveAssets, callAbs.args)
            .atSourceIndex(sourceIndex)
        case _ =>
          throw Compiler.Error("Expected a statement", sourceIndex)
      }
    }

  def statement[Unknown: P]: P[Ast.Statement[StatefulContext]] =
    P(
      varDef | structDestruction | assign | compoundAssign | debug | mapCall | contractCall | funcCall | ifelseStmt | whileStmt | forLoopStmt | ret | emitEvent
    )

  def insertToMap[Unknown: P]: P[Ast.Statement[StatefulContext]] =
    P(
      Index ~ Lexer.ident ~ "." ~ "insert!" ~
        "(" ~ expr.rep(0, ",") ~ ")" ~~ Index
    )
      .map { case (fromIndex, ident, exprs, endIndex) =>
        val sourceIndex = Some(SourceIndex(fromIndex, endIndex - fromIndex, fileURI))
        Ast.InsertToMap(ident, exprs).atSourceIndex(sourceIndex)
      }

  def removeFromMap[Unknown: P]: P[Ast.Statement[StatefulContext]] =
    P(Index ~ Lexer.ident ~ "." ~ "remove!" ~ "(" ~ expr.rep(0, ",") ~ ")" ~~ Index).map {
      case (fromIndex, ident, exprs, endIndex) =>
        val sourceIndex = Some(SourceIndex(fromIndex, endIndex - fromIndex, fileURI))
        Ast.RemoveFromMap(ident, exprs).atSourceIndex(sourceIndex)
    }

  def mapCall[Unknown: P]: P[Ast.Statement[StatefulContext]] = P(insertToMap | removeFromMap)

  def contractFields[Unknown: P]: P[Seq[Ast.Argument]] =
    P(
      "(" ~ contractField(allowMutable = true).rep(0, ",") ~ ")"
    )

  def rawTxScript[Unknown: P]: P[Ast.TxScript] =
    P(
      annotation.rep ~ Lexer.token(Keyword.TxScript) ~/
        Lexer.typeId ~ templateParams.? ~ "{" ~~
        Index ~ statement.rep(0) ~ func.rep(0) ~
        "}" ~~ Index
    )
      .map {
        case (
              annotations,
              scriptIndex,
              typeId,
              templateVars,
              mainStmtsIndex,
              mainStmts,
              funcs,
              endIndex
            ) =>
          val mainFuncOpt = funcs.find(_.name == "main")
          if (mainStmts.isEmpty && mainFuncOpt.isEmpty) {
            throw CompilerError.`Expected main statements`(
              typeId,
              mainStmtsIndex,
              fileURI
            )
          } else if (mainStmts.nonEmpty && mainFuncOpt.nonEmpty) {
            throw Compiler.Error(
              s"The main function is already defined in script `${typeId.name}`",
              mainFuncOpt.flatMap(_.id.sourceIndex)
            )
          } else {
            val usingAnnotation = Parser.FunctionUsingAnnotation.extractFields(
              annotations,
              Parser.FunctionUsingAnnotationFields(
                preapprovedAssets = true,
                assetsInContract = Ast.NotUseContractAssets,
                payToContractOnly = false,
                checkExternalCaller = true,
                updateFields = false,
                methodIndex = None
              )
            )
            val mainFunc = mainFuncOpt.getOrElse(
              Ast.FuncDef.main(
                mainStmts,
                usingAnnotation.preapprovedAssets,
                usingAnnotation.assetsInContract,
                usingAnnotation.updateFields
              )
            )
            if (mainFunc.args.nonEmpty) {
              throw Compiler.Error(
                "The main function cannot have parameters. Please declare the parameters as script parameters",
                mainFuncOpt.flatMap(_.id.sourceIndex)
              )
            }
            val allFuncs = (mainFunc +: funcs.filter(_.name != "main")).map(_.withOrigin(typeId))
            Ast
              .TxScript(typeId, templateVars.getOrElse(Seq.empty), allFuncs)
              .atSourceIndex(scriptIndex.index, endIndex, fileURI)
          }
      }
  def txScript[Unknown: P]: P[Ast.TxScript] = P(Start ~ rawTxScript ~ End)

  def inheritanceTemplateVariable[Unknown: P]: P[Seq[Ast.Ident]] =
    P("<" ~ Lexer.ident.rep(1, ",") ~ ">").?.map(_.getOrElse(Seq.empty))
  def inheritanceFields[Unknown: P]: P[Seq[Ast.Ident]] =
    P("(" ~ Lexer.ident.rep(0, ",") ~ ")")
  def contractInheritance[Unknown: P]: P[Ast.ContractInheritance] =
    P(Index ~ Lexer.typeId ~ inheritanceFields ~~ Index).map {
      case (startIndex, typeId, fields, endIndex) =>
        Ast.ContractInheritance(typeId, fields).atSourceIndex(startIndex, endIndex, fileURI)
    }

  def interfaceImplementing[Unknown: P]: P[Seq[Ast.Inheritance]] =
    P(Lexer.token(Keyword.implements) ~ (interfaceInheritance.rep(1, ","))).map {
      case (_, inheritances) => inheritances
    }

  def contractExtending[Unknown: P]: P[Seq[Ast.Inheritance]] =
    P(
      (Lexer.token(Keyword.`extends`) | Lexer.token(Keyword.`embeds`)) ~
        contractInheritance.rep(1, ",")
    ).map { case (_, extendings) => extendings }

  def contractInheritances[Unknown: P]: P[Seq[Ast.Inheritance]] = {
    P(contractExtending.? ~ interfaceImplementing.?).map {
      case (extendingsOpt, implementingOpt) => {
        extendingsOpt.getOrElse(Seq.empty) ++ implementingOpt.getOrElse(Seq.empty)
      }
    }
  }

  // scalastyle:off method.length
  // scalastyle:off cyclomatic.complexity
  def rawContract[Unknown: P]: P[Ast.Contract] =
    P(
      annotation.rep ~ Index ~ Lexer.`abstract` ~ Lexer.token(
        Keyword.Contract
      ) ~/ Lexer.typeId ~ contractFields ~
        contractInheritances.? ~ "{" ~
        (mapDef | eventDef | constantVarDef | rawEnumDef | func).rep ~ "}"
        ~~ Index
    ).map {
      case (
            annotations,
            fromIndex,
            isAbstract,
            _,
            typeId,
            fields,
            contractInheritances,
            statements,
            endIndex
          ) =>
        Parser.checkAnnotations(annotations, AVector(Parser.ContractStdAnnotation.id), "contract")
        val contractStdAnnotation = Parser.ContractStdAnnotation.extractFields(annotations, None)
        val maps                  = ArrayBuffer.empty[Ast.MapDef]
        val funcs                 = ArrayBuffer.empty[Ast.FuncDef[StatefulContext]]
        val events                = ArrayBuffer.empty[Ast.EventDef]
        val constantVars          = ArrayBuffer.empty[Ast.ConstantVarDef[StatefulContext]]
        val enums                 = ArrayBuffer.empty[Ast.EnumDef[StatefulContext]]

        statements.foreach {
          case m: Ast.MapDef =>
            if (events.nonEmpty || constantVars.nonEmpty || funcs.nonEmpty || enums.nonEmpty) {
              throwContractStmtsOutOfOrderException(m.sourceIndex)
            }
            maps += m
          case e: Ast.EventDef =>
            if (constantVars.nonEmpty || funcs.nonEmpty || enums.nonEmpty) {
              throwContractStmtsOutOfOrderException(e.sourceIndex)
            }
            events += e
          case c: Ast.ConstantVarDef[StatefulContext @unchecked] =>
            if (funcs.nonEmpty || enums.nonEmpty) {
              throwContractStmtsOutOfOrderException(c.sourceIndex)
            }
            constantVars += c.withOrigin(typeId)
          case e: Ast.EnumDef[StatefulContext @unchecked] =>
            if (funcs.nonEmpty) {
              throwContractStmtsOutOfOrderException(e.sourceIndex)
            }
            e.fields.foreach(_.withOrigin(typeId))
            enums += e
          case f: Ast.FuncDef[StatefulContext @unchecked] => funcs += f
          case _                                          =>
        }

        Ast
          .Contract(
            contractStdAnnotation.map(_.enabled),
            None,
            isAbstract,
            typeId,
            Seq.empty,
            fields,
            funcs.map(_.withOrigin(typeId)).toSeq,
            maps.toSeq,
            events.toSeq,
            constantVars.toSeq,
            enums.toSeq,
            contractInheritances.getOrElse(Seq.empty)
          )
          .atSourceIndex(fromIndex, endIndex, fileURI)
    }
  // scalastyle:on method.length
  // scalastyle:on cyclomatic.complexity

  private def throwContractStmtsOutOfOrderException(sourceIndex: Option[SourceIndex]) = {
    throw Compiler.Error(
      "Contract statements should be in the order of `maps`, `events`, `consts`, `enums` and `methods`",
      sourceIndex
    )
  }

  def contract[Unknown: P]: P[Ast.Contract] = P(Start ~ rawContract ~ End)

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def interfaceInheritance[Unknown: P]: P[Ast.InterfaceInheritance] =
    P(Lexer.typeId).map(typeId =>
      Ast.InterfaceInheritance(typeId).atSourceIndex(typeId.sourceIndex)
    )
  def interfaceFunc[Unknown: P]: P[Ast.FuncDef[StatefulContext]] = {
    funcTmp.map { f =>
      f.body match {
        case None =>
          Ast
            .FuncDef(
              f.annotations,
              f.id,
              f.isPublic,
              f.usePreapprovedAssets,
              f.useContractAssets,
              f.usePayToContractOnly,
              f.useCheckExternalCaller,
              f.useUpdateFields,
              f.useMethodIndex,
              f.inline,
              f.args,
              f.rtypes,
              None
            )
            .atSourceIndex(f.sourceIndex)
        case _ =>
          throw Compiler.Error(
            s"Interface function ${f.id.name} should not have function body",
            f.sourceIndex
          )
      }
    }
  }
  def rawInterface[Unknown: P]: P[Ast.ContractInterface] =
    P(
      annotation.rep ~ Lexer.token(Keyword.Interface) ~/ Lexer.typeId ~
        (Lexer.token(Keyword.`extends`) ~/ interfaceInheritance.rep(1, ",")).? ~
        "{" ~ eventDef.rep ~ interfaceFunc.rep ~ "}"
        ~~ Index
    ).map { case (annotations, fromIndex, typeId, inheritances, events, funcs, endIndex) =>
      if (funcs.length < 1) {
        throw Compiler.Error(
          s"No function definition in Interface ${typeId.name}",
          typeId.sourceIndex
        )
      }
      val annotationIds =
        AVector(Parser.InterfaceStdAnnotation.id, Parser.FunctionUsingAnnotation.id)
      Parser.checkAnnotations(annotations, annotationIds, "interface")
      val stdIdOpt = Parser.InterfaceStdAnnotation.extractFields(annotations, None)
      val usingFields = Parser.InterfaceUsingAnnotation.extractFields(
        annotations,
        Parser.InterfaceUsingAnnotationFields(methodSelector = true)
      )
      Ast
        .ContractInterface(
          stdIdOpt.map(stdId => Val.ByteVec(stdId.id)),
          usingFields.methodSelector,
          typeId,
          funcs.map(_.withOrigin(typeId)),
          events,
          inheritances.map(_._2).getOrElse(Seq.empty)
        )
        .atSourceIndex(fromIndex.index, endIndex, fileURI)
    }
  def interface[Unknown: P]: P[Ast.ContractInterface] = P(Start ~ rawInterface ~ End)

  private def globalDefinition[Unknown: P]: P[Ast.GlobalDefinition] = P(
    rawTxScript | rawContract | rawInterface | rawStruct | constantVarDef | rawEnumDef
  )

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def multiContract[Unknown: P]: P[Ast.MultiContract] =
    P(Start ~~ Index ~ globalDefinition.rep(1) ~~ Index ~ End)
      .map { case (fromIndex, definitions, endIndex) =>
        val (contracts, defs) = definitions.partition(_.isInstanceOf[Ast.ContractWithState])
        val globalState       = Ast.GlobalState.from[StatefulContext](defs)
        Ast
          .MultiContract(
            contracts.asInstanceOf[Seq[Ast.ContractWithState]],
            globalState,
            None,
            None
          )
          .atSourceIndex(fromIndex, endIndex, fileURI)
      }

  def emitEvent[Unknown: P]: P[Ast.EmitEvent[StatefulContext]] =
    P(Index ~ "emit" ~ Lexer.typeId ~ "(" ~ expr.rep(0, ",") ~ ")" ~~ Index)
      .map { case (fromIndex, typeId, exprs, endIndex) =>
        Ast.EmitEvent(typeId, exprs).atSourceIndex(fromIndex, endIndex, fileURI)
      }
}
