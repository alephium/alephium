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
import org.alephium.util.AVector

// scalastyle:off number.of.methods file.size.limit
@SuppressWarnings(
  Array(
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
abstract class Parser[Ctx <: StatelessContext] {
  implicit val whitespace: P[_] => P[Unit] = { implicit ctx: P[_] => Lexer.emptyChars(ctx) }

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

  def value[Unknown: P]: P[Val] = P(Lexer.typedNum | Lexer.bool | Lexer.bytes | Lexer.address)
  def const[Unknown: P]: P[Ast.Const[Ctx]] = PP(value) { const =>
    Ast.Const.apply[Ctx](const)
  }

  def createArray1[Unknown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    PP("[" ~ (expr.rep(1, ",")) ~ "]") { elems =>
      Ast.CreateArrayExpr.apply(elems)
    }
  def createArray2[Unknown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    PP("[" ~ (expr ~ ";" ~ nonNegativeNum("array size")) ~ "]") { case ((expr, size)) =>
      Ast.CreateArrayExpr(Seq.fill(size)(expr))
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
  def callAbs[Unknown: P]: P[(Ast.FuncId, Seq[Ast.ApproveAsset[Ctx]], Seq[Ast.Expr[Ctx]])] =
    P(Lexer.funcId ~ approveAssets.? ~ "(" ~ expr.rep(0, ",") ~ ")").map {
      case (funcId, approveAssets, arguments) =>
        (funcId, approveAssets.getOrElse(Seq.empty), arguments)
    }
  def callExpr[Unknown: P]: P[Ast.Expr[Ctx]] =
    PP((Lexer.typeId ~ ".").? ~ callAbs) { case (contractIdOpt, (funcId, approveAssets, expr)) =>
      contractIdOpt match {
        case Some(contractId) =>
          Ast
            .ContractStaticCallExpr(contractId, funcId, approveAssets, expr)
        case None => Ast.CallExpr(funcId, approveAssets, expr)
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

  def nonNegativeNum[Unknown: P](errorMsg: String): P[Int] = P(Index ~ Lexer.num ~~ Index).map {
    case (fromIndex, value, endIndex) =>
      val idx = value.intValue()
      if (idx < 0) {
        throw Compiler.Error(
          s"Invalid $errorMsg: $idx",
          Some(SourceIndex(fromIndex, endIndex - fromIndex, fileURI))
        )
      }
      idx
  }

  def indexSelector[Unknown: P]: P[Ast.DataSelector] = P(Index ~~ "[" ~ expr ~ "]" ~~ Index).map {
    case (from, expr, to) =>
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
    P(loadFieldBySelectors | PP(Lexer.opNot ~ loadFieldBySelectors) { case (op, expr) =>
      Ast.UnaryOp.apply[Ctx](op, expr)
    })

  def loadFieldBySelectors[Unknown: P]: P[Ast.Expr[Ctx]] =
    PP(atom ~ dataSelector.rep(0)) { case (expr, selectors) =>
      if (selectors.isEmpty) expr else Ast.LoadDataBySelectors(expr, selectors)
    }
  def atom[Unknown: P]: P[Ast.Expr[Ctx]]

  def structCtor[Unknown: P]: P[Ast.StructCtor[Ctx]] =
    PP(Lexer.typeId ~ "{" ~ P(Lexer.ident ~ ":" ~ expr).rep(0, ",") ~ "}") {
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

  def ifBranchExpr[Unknown: P]: P[Ast.IfBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`if`) ~/ "(" ~ expr ~ ")" ~ expr).map { case (ifIndex, condition, expr) =>
      val sourceIndex = SourceIndex(Some(ifIndex), expr.sourceIndex)
      Ast.IfBranchExpr(condition, expr).atSourceIndex(sourceIndex)
    }
  def elseIfBranchExpr[Unknown: P]: P[Ast.IfBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ ifBranchExpr).map { case (elseIndex, ifBranch) =>
      val sourceIndex = SourceIndex(Some(elseIndex), ifBranch.sourceIndex)
      Ast.IfBranchExpr(ifBranch.condition, ifBranch.expr).atSourceIndex(sourceIndex)
    }
  def elseBranchExpr[Unknown: P]: P[Ast.ElseBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ expr).map { case (elseIndex, expr) =>
      val sourceIndex = SourceIndex(Some(elseIndex), expr.sourceIndex)
      Ast.ElseBranchExpr(expr).atSourceIndex(sourceIndex)
    }

  def ifelseExpr[Unknown: P]: P[Ast.IfElseExpr[Ctx]] =
    P(ifBranchExpr ~ elseIfBranchExpr.rep(0) ~~ Index ~ elseBranchExpr.?).map {
      case (ifBranch, elseIfBranches, _, Some(elseBranch)) =>
        val sourceIndex = SourceIndex(ifBranch.sourceIndex, elseBranch.sourceIndex)
        Ast.IfElseExpr(ifBranch +: elseIfBranches, elseBranch).atSourceIndex(sourceIndex)
      case (_, _, index, None) =>
        throw CompilerError.`Expected else statement`(index, fileURI)
    }

  def stringLiteral[Unknown: P]: P[Ast.StringLiteral[Ctx]] =
    PP("b" ~ Lexer.string) { s =>
      Ast.StringLiteral(Val.ByteVec(ByteString.fromString(s)))
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

  def normalRet[Unknown: P]: P[Ast.ReturnStmt[Ctx]] =
    P(Lexer.token(Keyword.`return`) ~/ expr.rep(0, ",")).map { case (returnIndex, returns) =>
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

  def anonymousVar[Unknown: P]: P[Ast.VarDeclaration] = PP("_")(_ => Ast.AnonymousVar)
  def namedVar[Unknown: P]: P[Ast.VarDeclaration] =
    P(Index ~ Lexer.mut ~ Lexer.ident ~~ Index).map { case (from, mutable, id, to) =>
      Ast.NamedVar(mutable, id).atSourceIndex(from, to, fileURI)
    }

  def varDeclaration[Unknown: P]: P[Ast.VarDeclaration] = P(namedVar | anonymousVar)
  def varDeclarations[Unknown: P]: P[Seq[Ast.VarDeclaration]] = P(
    varDeclaration.map(Seq(_)) | "(" ~ varDeclaration.rep(1, ",") ~ ")"
  )
  def varDef[Unknown: P]: P[Ast.VarDef[Ctx]] =
    P(Lexer.token(Keyword.let) ~/ varDeclarations ~ "=" ~ expr).map { case (from, vars, expr) =>
      val sourceIndex = SourceIndex(Some(from), expr.sourceIndex)
      Ast.VarDef(vars, expr).atSourceIndex(sourceIndex)
    }

  def identSelector[Unknown: P]: P[Ast.DataSelector] = P(
    "." ~ Index ~ Lexer.ident ~ Index
  ).map { case (from, ident, to) =>
    Ast.IdentSelector(ident).atSourceIndex(from, to, fileURI)
  }
  def dataSelector[Unknown: P]: P[Ast.DataSelector] = P(identSelector | indexSelector)
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
    P("[" ~ baseType ~ ";" ~ nonNegativeNum("array size") ~ "]").map { case (tpe, size) =>
      Type.FixedSizeArray(tpe, size)
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
  def funcArgument[Unknown: P]: P[Ast.Argument]   = argument(allowMutable = true)(Type.NamedType)
  def funParams[Unknown: P]: P[Seq[Ast.Argument]] = P("(" ~ funcArgument.rep(0, ",") ~ ")")
  def returnType[Unknown: P]: P[Seq[Type]]        = P(simpleReturnType | bracketReturnType)
  def simpleReturnType[Unknown: P]: P[Seq[Type]] =
    P("->" ~ parseType(Type.NamedType)).map(tpe => Seq(tpe))
  def bracketReturnType[Unknown: P]: P[Seq[Type]] =
    P("->" ~ "(" ~ parseType(Type.NamedType).rep(0, ",") ~ ")")
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
          val usingAnnotation = Parser.UsingAnnotation.extractFields(
            annotations,
            Parser.UsingAnnotationFields(
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
              s"Can only enable one of the two annotations: @using(assetsInContract = true) or @using(payToContractOnly = true)",
              SourceIndex(
                annotations.headOption.flatMap(_.fields.headOption.flatMap(_.sourceIndex)),
                annotations.lastOption.flatMap(_.fields.lastOption.flatMap(_.sourceIndex))
              )
            )
          }
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
      case (fromIndex, contractIdOpt, (funcId, approveAssets, exprs), endIndex) =>
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
    P(Lexer.token(Keyword.`if`) ~ "(" ~ expr ~ ")" ~ block ~~ Index).map {
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
    argument(allowMutable)(Type.NamedType)

  def templateParams[Unknown: P]: P[Seq[Ast.Argument]] =
    P("(" ~ contractField(allowMutable = false).rep(0, ",") ~ ")")

  def field[Unknown: P]: P[(Ast.Ident, Type)] = P(Lexer.ident ~ ":").flatMap { ident =>
    parseType(Type.NamedType).map { tpe => (ident, tpe) }
  }

  def eventField[Unknown: P]: P[Ast.EventField] = P(Index ~ field ~~ Index).map {
    case (from, (ident, tpe), to) =>
      Ast.EventField(ident, tpe).atSourceIndex(from, to, fileURI)
  }

  def structField[Unknown: P]: P[Ast.StructField] = PP(
    Lexer.mut ~ Lexer.ident ~ ":" ~ parseType(Type.NamedType)
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

  def enforceUsingContractAssets[Unknown: P]: P[Ast.AnnotationField] =
    P(Index ~ Parser.UsingAnnotation.useContractAssetsKey ~ "=" ~ "enforced" ~~ Index).map {
      case (from, to) =>
        Ast
          .AnnotationField(Ast.Ident(Parser.UsingAnnotation.useContractAssetsKey), Val.Enforced)
          .atSourceIndex(from, to, fileURI)
    }
  def annotationField[Unknown: P]: P[Ast.AnnotationField] =
    P(Index ~ Lexer.ident ~ "=" ~ expr ~~ Index).map {
      case (fromIndex, ident, expr: Ast.Const[_], endIndex) =>
        Ast.AnnotationField(ident, expr.v).atSourceIndex(fromIndex, endIndex, fileURI)
      case (_, _, expr, _) =>
        throw Compiler.Error(
          s"Expect const value for annotation field, got ${expr}",
          expr.sourceIndex
        )
    }
  def annotationFields[Unknown: P]: P[Seq[Ast.AnnotationField]] =
    P("(" ~ (enforceUsingContractAssets | annotationField).rep(1, ",") ~ ")")
  def annotation[Unknown: P]: P[Ast.Annotation] =
    P(Index ~ "@" ~ Lexer.ident ~ annotationFields.? ~~ Index).map {
      case (fromIndex, id, fieldsOpt, endIndex) =>
        Ast
          .Annotation(id, fieldsOpt.getOrElse(Seq.empty))
          .atSourceIndex(fromIndex, endIndex, fileURI)
    }
}

final case class FuncDefTmp[Ctx <: StatelessContext](
    annotations: Seq[Annotation],
    id: FuncId,
    isPublic: Boolean,
    usePreapprovedAssets: Boolean,
    useContractAssets: Ast.ContractAssetsAnnotation,
    usePayToContractOnly: Boolean,
    useCheckExternalCaller: Boolean,
    useUpdateFields: Boolean,
    useMethodIndex: Option[Int],
    args: Seq[Argument],
    rtypes: Seq[Type],
    body: Option[Seq[Statement[Ctx]]]
) extends Ast.Positioned

object Parser {

  sealed trait RalphAnnotation[T] {
    def id: String
    def keys: AVector[String]
    def validate(annotations: Seq[Ast.Annotation]): Option[Annotation] = {
      annotations.find(_.id.name != id) match {
        case Some(annotation) =>
          throw Compiler.Error(
            s"Invalid annotation, expect @$id annotation",
            annotation.sourceIndex
          )
        case None => ()
      }
      annotations.headOption match {
        case result @ Some(annotation) =>
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
          result
        case None => None
      }
    }

    final def extractField[V <: Val](
        annotation: Ast.Annotation,
        key: String,
        tpe: Val.Type
    ): Option[V] = {
      annotation.fields.find(_.ident.name == key) match {
        case Some(Ast.AnnotationField(_, value: V @unchecked)) if tpe == value.tpe => Some(value)
        case Some(field) =>
          throw Compiler.Error(s"Expect $tpe for $key in annotation @$id", field.sourceIndex)
        case None => None
      }
    }

    final def extractField[V <: Val](annotation: Ast.Annotation, key: String, default: V): V = {
      extractField[V](annotation, key, default.tpe).getOrElse(default)
    }

    final def extractFields(annotations: Seq[Ast.Annotation], default: T): T = {
      validate(annotations).map(extractFields(_, default)).getOrElse(default)
    }
    def extractFields(annotation: Ast.Annotation, default: T): T
  }

  final case class UsingAnnotationFields(
      preapprovedAssets: Boolean,
      assetsInContract: Ast.ContractAssetsAnnotation,
      payToContractOnly: Boolean,
      checkExternalCaller: Boolean,
      updateFields: Boolean,
      methodIndex: Option[Int]
  )

  object UsingAnnotation extends RalphAnnotation[UsingAnnotationFields] {
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

    private def extractUseContractAsset(annotation: Annotation): Ast.ContractAssetsAnnotation = {
      annotation.fields.find(_.ident.name == useContractAssetsKey) match {
        case Some(field @ Ast.AnnotationField(_, value)) =>
          value match {
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

    def extractFields(
        annotation: Ast.Annotation,
        default: UsingAnnotationFields
    ): UsingAnnotationFields = {
      val methodIndex =
        extractField[Val.U256](annotation, useMethodIndexKey, Val.U256).flatMap(_.v.toInt)
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
      UsingAnnotationFields(
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

  final case class InterfaceStdFields(id: ByteString)

  object InterfaceStdAnnotation extends RalphAnnotation[Option[InterfaceStdFields]] {
    val id: String            = "std"
    val keys: AVector[String] = AVector("id")

    def extractFields(
        annotation: Annotation,
        default: Option[InterfaceStdFields]
    ): Option[InterfaceStdFields] = {
      extractField[Val.ByteVec](annotation, keys(0), Val.ByteVec).map { stdId =>
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

  final case class ContractStdFields(enabled: Boolean)

  object ContractStdAnnotation extends RalphAnnotation[Option[ContractStdFields]] {
    val id: String            = "std"
    val keys: AVector[String] = AVector("enabled")

    def extractFields(
        annotation: Annotation,
        default: Option[ContractStdFields]
    ): Option[ContractStdFields] = {
      extractField[Val.Bool](annotation, keys(0), Val.Bool).map(field => ContractStdFields(field.v))
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
      const | stringLiteral | alphTokenId | callExpr | contractConv |
        structCtor | variable | parenExpr | arrayExpr | ifelseExpr
    )

  def statement[Unknown: P]: P[Ast.Statement[StatelessContext]] =
    P(varDef | assign | debug | funcCall | ifelseStmt | whileStmt | forLoopStmt | ret)

  def assetScript[Unknown: P]: P[Ast.AssetScript] =
    P(
      Start ~ rawStruct.rep(0) ~ Lexer.token(Keyword.AssetScript) ~/ Lexer.typeId ~
        templateParams.? ~ "{" ~ func.rep(1) ~ "}" ~~ Index ~ rawStruct.rep(0) ~ endOfInput(fileURI)
    ).map { case (defs0, assetIndex, typeId, templateVars, funcs, endIndex, defs1) =>
      Ast
        .AssetScript(typeId, templateVars.getOrElse(Seq.empty), funcs, defs0 ++ defs1)
        .atSourceIndex(assetIndex.index, endIndex, fileURI)
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
      const | stringLiteral | alphTokenId | callExpr | mapContains | contractCallExpr | contractConv |
        enumFieldSelector | structCtor | variable | parenExpr | arrayExpr | ifelseExpr
    )

  def mapKeyType[Unknown: P]: P[Type] = {
    P(Index ~ parseType(Type.NamedType) ~ Index).map { case (from, tpe, to) =>
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
        ~ parseType(Type.NamedType) ~ "]" ~ Lexer.ident
    ) { case (_, key, value, ident) =>
      Ast.MapDef(ident, Type.Map(key, value))
    }
  }

  def contractCallExpr[Unknown: P]: P[Ast.Expr[StatefulContext]] =
    P(Index ~ (callExpr | contractConv | variableIdOnly) ~ ("." ~ callAbs).rep(1) ~~ Index).map {
      case (fromIndex, obj, callAbss, endIndex) =>
        callAbss.foldLeft(obj)((acc, callAbs) => {
          val (funcId, approveAssets, arguments) = callAbs
          Ast
            .ContractCallExpr(acc, funcId, approveAssets, arguments)
            .atSourceIndex(fromIndex, endIndex, fileURI)
        })
    }

  def mapContains[Unknown: P]: P[Ast.Expr[StatefulContext]] =
    P(Index ~ Lexer.ident ~ ".contains!" ~ "(" ~ expr ~ ")" ~~ Index).map {
      case (fromIndex, ident, index, endIndex) =>
        Ast.MapContains(ident, index).atSourceIndex(fromIndex, endIndex, fileURI)
    }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def contractCall[Unknown: P]: P[Ast.Statement[StatefulContext]] =
    P(Index ~ (callExpr | contractConv | variableIdOnly) ~ ("." ~ callAbs).rep(1) ~~ Index).map {
      case (fromIndex, obj, callAbss, endIndex) =>
        val base = callAbss.init.foldLeft(obj)((acc, callAbs) => {
          val (funcId, approveAssets, arguments) = callAbs
          Ast
            .ContractCallExpr(acc, funcId, approveAssets, arguments)
            .atSourceIndex(fromIndex, endIndex, fileURI)
        })
        val (funcId, approveAssets, arguments) = callAbss.last
        Ast
          .ContractCall(base, funcId, approveAssets, arguments)
          .atSourceIndex(fromIndex, endIndex, fileURI)
    }

  def statement[Unknown: P]: P[Ast.Statement[StatefulContext]] =
    P(
      varDef | assign | debug | funcCall | mapCall | contractCall | ifelseStmt | whileStmt | forLoopStmt | ret | emitEvent
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
      annotation.rep ~
        Lexer.token(
          Keyword.TxScript
        ) ~/ Lexer.typeId ~ templateParams.? ~ "{" ~~ Index ~ statement
          .rep(0) ~ func
          .rep(0) ~ "}"
        ~~ Index
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
          if (mainStmts.isEmpty) {
            throw CompilerError.`Expected main statements`(
              typeId,
              mainStmtsIndex,
              fileURI
            )
          } else {
            val usingAnnotation = Parser.UsingAnnotation.extractFields(
              annotations,
              Parser.UsingAnnotationFields(
                preapprovedAssets = true,
                assetsInContract = Ast.NotUseContractAssets,
                payToContractOnly = false,
                checkExternalCaller = true,
                updateFields = false,
                methodIndex = None
              )
            )
            val mainFunc = Ast.FuncDef.main(
              mainStmts,
              usingAnnotation.preapprovedAssets,
              usingAnnotation.assetsInContract,
              usingAnnotation.updateFields
            )
            Ast
              .TxScript(typeId, templateVars.getOrElse(Seq.empty), mainFunc +: funcs)
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
    P(Index ~ contractExtending.? ~ interfaceImplementing.? ~~ Index).map {
      case (fromIndex, extendingsOpt, implementingOpt, endIndex) => {
        val implementedInterfaces = implementingOpt.getOrElse(Seq.empty)
        if (implementedInterfaces.length > 1) {
          val interfaceNames = implementedInterfaces.map(_.parentId.name).mkString(", ")
          throw Compiler.Error(
            s"Contract only supports implementing single interface: $interfaceNames",
            Some(SourceIndex(fromIndex, endIndex - fromIndex, fileURI))
          )
        }

        extendingsOpt.getOrElse(Seq.empty) ++ implementedInterfaces
      }
    }
  }

  def constantVarDef[Unknown: P]: P[Ast.ConstantVarDef] =
    PP(Lexer.token(Keyword.const) ~/ Lexer.constantIdent ~ "=" ~ atom) { case (_, ident, value) =>
      value match {
        case Ast.Const(v) =>
          Ast.ConstantVarDef(ident, v)
        case Ast.StringLiteral(v) =>
          Ast.ConstantVarDef(ident, v)
        case v: Ast.CreateArrayExpr[_] =>
          throwConstantVarDefException("arrays", v.sourceIndex)
        case v: Ast.StructCtor[_] =>
          throwConstantVarDefException("structs", v.sourceIndex)
        case v: Ast.ContractConv[_] =>
          throwConstantVarDefException("contract instances", v.sourceIndex)
        case v: Ast.Positioned =>
          throwConstantVarDefException("other expressions", v.sourceIndex)
      }
    }

  private val primitiveTypes = Type.primitives.map(_.signature).mkString("/")
  private def throwConstantVarDefException(label: String, sourceIndex: Option[SourceIndex]) = {
    throw Compiler.Error(
      s"Expected constant value with primitive types ${primitiveTypes}, $label are not supported",
      sourceIndex
    )
  }

  def enumFieldSelector[Unknown: P]: P[Ast.EnumFieldSelector[StatefulContext]] =
    PP(Lexer.typeId ~ "." ~ Lexer.constantIdent) { case (enumId, field) =>
      Ast.EnumFieldSelector(enumId, field)
    }

  def enumField[Unknown: P]: P[Ast.EnumField] =
    PP(Lexer.constantIdent ~ "=" ~ (value | stringLiteral.map(_.string))) { case (ident, value) =>
      Ast.EnumField(ident, value)
    }
  def rawEnumDef[Unknown: P]: P[Ast.EnumDef] =
    PP(Lexer.token(Keyword.`enum`) ~/ Lexer.typeId ~ "{" ~ enumField.rep ~ "}") {
      case (enumIndex, id, fields) =>
        if (fields.length == 0) {
          val sourceIndex = SourceIndex(Some(enumIndex), id.sourceIndex)
          throw Compiler.Error(s"No field definition in Enum ${id.name}", sourceIndex)
        }
        Ast.UniqueDef.checkDuplicates(fields, "enum fields")
        if (fields.distinctBy(_.value.tpe).size != 1) {
          throw Compiler.Error(s"Fields have different types in Enum ${id.name}", id.sourceIndex)
        }
        if (fields.distinctBy(_.value).size != fields.length) {
          throw Compiler.Error(s"Fields have the same value in Enum ${id.name}", id.sourceIndex)
        }
        Ast.EnumDef(id, fields)
    }
  def enumDef[Unknown: P]: P[Ast.EnumDef] = P(Start ~ rawEnumDef ~ End)

  // scalastyle:off method.length
  // scalastyle:off cyclomatic.complexity
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def rawContract[Unknown: P]: P[Ast.Contract] =
    P(
      annotation.rep ~ Index ~ Lexer.`abstract` ~ Lexer.token(
        Keyword.Contract
      ) ~/ Lexer.typeId ~ contractFields ~
        contractInheritances.? ~ "{" ~ (mapDef | eventDef | constantVarDef | rawEnumDef | func).rep ~ "}"
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
        val contractStdAnnotation = Parser.ContractStdAnnotation.extractFields(annotations, None)
        val maps                  = ArrayBuffer.empty[Ast.MapDef]
        val funcs                 = ArrayBuffer.empty[Ast.FuncDef[StatefulContext]]
        val events                = ArrayBuffer.empty[Ast.EventDef]
        val constantVars          = ArrayBuffer.empty[Ast.ConstantVarDef]
        val enums                 = ArrayBuffer.empty[Ast.EnumDef]

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
          case c: Ast.ConstantVarDef =>
            if (funcs.nonEmpty || enums.nonEmpty) {
              throwContractStmtsOutOfOrderException(c.sourceIndex)
            }
            constantVars += c
          case e: Ast.EnumDef =>
            if (funcs.nonEmpty) {
              throwContractStmtsOutOfOrderException(e.sourceIndex)
            }
            enums += e
          case f: Ast.FuncDef[_] =>
            funcs += f.asInstanceOf[Ast.FuncDef[StatefulContext]]
          case _ =>
        }

        Ast
          .Contract(
            contractStdAnnotation.map(_.enabled),
            None,
            isAbstract,
            typeId,
            Seq.empty,
            fields,
            funcs.toSeq,
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
      inheritances match {
        case Some((index, parents)) if parents.length > 1 =>
          throw Compiler.Error(
            s"Interface only supports single inheritance: ${parents.map(_.parentId.name).mkString(", ")}",
            Some(index)
          )
        case _ => ()
      }
      if (funcs.length < 1) {
        throw Compiler.Error(
          s"No function definition in Interface ${typeId.name}",
          typeId.sourceIndex
        )
      }
      val stdIdOpt = Parser.InterfaceStdAnnotation.extractFields(annotations, None)
      Ast
        .ContractInterface(
          stdIdOpt.map(stdId => Val.ByteVec(stdId.id)),
          typeId,
          funcs,
          events,
          inheritances.map { case (_, inher) => inher }.getOrElse(Seq.empty)
        )
        .atSourceIndex(fromIndex.index, endIndex, fileURI)
    }
  def interface[Unknown: P]: P[Ast.ContractInterface] = P(Start ~ rawInterface ~ End)

  private def entities[Unknown: P]: P[Ast.Entity] = P(
    rawTxScript | rawContract | rawInterface | rawStruct
  )

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def multiContract[Unknown: P]: P[Ast.MultiContract] =
    P(Start ~~ Index ~ entities.rep(1) ~~ Index ~ End)
      .map { case (fromIndex, defs, endIndex) =>
        val contracts = defs
          .filter(_.isInstanceOf[Ast.ContractWithState])
          .asInstanceOf[Seq[Ast.ContractWithState]]
        val structs = defs.filter(_.isInstanceOf[Ast.Struct]).asInstanceOf[Seq[Ast.Struct]]
        Ast.MultiContract(contracts, structs, None).atSourceIndex(fromIndex, endIndex, fileURI)
      }

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
    P(Index ~ "emit" ~ Lexer.typeId ~ "(" ~ expr.rep(0, ",") ~ ")" ~~ Index)
      .map { case (fromIndex, typeId, exprs, endIndex) =>
        Ast.EmitEvent(typeId, exprs).atSourceIndex(fromIndex, endIndex, fileURI)
      }
}
