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

import akka.util.ByteString
import fastparse._

import org.alephium.protocol.vm.{Instr, StatefulContext, StatelessContext, Val}
import org.alephium.ralph.Ast.{Annotation, Argument, FuncId, Statement}
import org.alephium.ralph.error.CompilerError
import org.alephium.ralph.error.FastParseExtension._
import org.alephium.util.AVector

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

  def value[Unknown: P]: P[Val] = P(Lexer.typedNum | Lexer.bool | Lexer.bytes | Lexer.address)
  def const[Unknown: P]: P[Ast.Const[Ctx]] = value.map(Ast.Const.apply[Ctx])
  def createArray1[Unknown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    P("[" ~ expr.rep(1, ",") ~ "]").map(Ast.CreateArrayExpr.apply)
  def createArray2[Unknown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    P("[" ~ expr ~ ";" ~ nonNegativeNum("array size") ~ "]").map { case (expr, size) =>
      Ast.CreateArrayExpr(Seq.fill(size)(expr))
    }
  def arrayExpr[Unknown: P]: P[Ast.Expr[Ctx]] = P(createArray1 | createArray2)
  def variable[Unknown: P]: P[Ast.Variable[Ctx]] =
    P(Lexer.ident | Lexer.constantIdent).map(Ast.Variable.apply[Ctx])
  def variableIdOnly[Unknown: P]: P[Ast.Variable[Ctx]] =
    P(Lexer.ident).map(Ast.Variable.apply[Ctx])
  def alphTokenId[Unknown: P]: P[Ast.Expr[Ctx]] =
    Lexer.token(Keyword.ALPH_CAPS).map(_ => Ast.ALPHTokenId())

  def alphAmount[Unknown: P]: P[Ast.Expr[Ctx]]                       = expr
  def tokenAmount[Unknown: P]: P[(Ast.Expr[Ctx], Ast.Expr[Ctx])]     = P(expr ~ ":" ~ expr)
  def amountList[Unknown: P]: P[Seq[(Ast.Expr[Ctx], Ast.Expr[Ctx])]] = P(tokenAmount.rep(0, ","))
  def approveAssetPerAddress[Unknown: P]: P[Ast.ApproveAsset[Ctx]] =
    P(expr ~ LastIndex("->") ~ amountList).map { case (address, index, amounts) =>
      if (amounts.isEmpty) {
        throw CompilerError.`Expected non-empty asset(s) for address`(index)
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
    P((Lexer.typeId ~ ".").? ~ callAbs).map { case (contractIdOpt, (funcId, approveAssets, expr)) =>
      contractIdOpt match {
        case Some(contractId) => Ast.ContractStaticCallExpr(contractId, funcId, approveAssets, expr)
        case None             => Ast.CallExpr(funcId, approveAssets, expr)
      }
    }
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
  def expr[Unknown: P]: P[Ast.Expr[Ctx]]    = P(chain(andExpr, Lexer.opOr))
  def andExpr[Unknown: P]: P[Ast.Expr[Ctx]] = P(chain(relationExpr, Lexer.opAnd))
  def relationExpr[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(arithExpr6 ~ comparison.?).flatMap {
      case (lhs, Some(op)) => arithExpr6.map(rhs => Ast.Binop(op, lhs, rhs))
      case (lhs, None)     => Pass(lhs)
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
    P(arrayElementOrAtom | (Lexer.opNot ~ arrayElementOrAtom).map { case (op, expr) =>
      Ast.UnaryOp.apply[Ctx](op, expr)
    })
  def arrayElementOrAtom[Unknown: P]: P[Ast.Expr[Ctx]] = P(atom ~ arrayIndex.rep(0)).map {
    case (expr, indexes) => if (indexes.nonEmpty) Ast.ArrayElement(expr, indexes) else expr
  }
  def atom[Unknown: P]: P[Ast.Expr[Ctx]]

  def parenExpr[Unknown: P]: P[Ast.ParenExpr[Ctx]] =
    P("(" ~ expr ~ ")").map(Ast.ParenExpr.apply[Ctx])

  def ifBranchExpr[Unknown: P]: P[Ast.IfBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`if`) ~/ "(" ~ expr ~ ")" ~ expr).map { case (condition, expr) =>
      Ast.IfBranchExpr(condition, expr)
    }
  def elseIfBranchExpr[Unknown: P]: P[Ast.IfBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ ifBranchExpr)
  def elseBranchExpr[Unknown: P]: P[Ast.ElseBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ expr).map(Ast.ElseBranchExpr(_))
  def ifelseExpr[Unknown: P]: P[Ast.IfElseExpr[Ctx]] =
    P(ifBranchExpr ~ elseIfBranchExpr.rep(0) ~ Index ~ elseBranchExpr.?).map {
      case (ifBranch, elseIfBranches, _, Some(elseBranch)) =>
        Ast.IfElseExpr(ifBranch +: elseIfBranches, elseBranch)
      case (_, _, index, None) =>
        throw CompilerError.`Expected else statement`(index)
    }

  def ret[Unknown: P]: P[Ast.ReturnStmt[Ctx]] =
    P(normalRet.rep(1)).map { returnStmts =>
      if (returnStmts.length > 1) {
        throw Compiler.Error("Consecutive return statements are not allowed")
      } else {
        returnStmts(0)
      }
    }

  def normalRet[Unknown: P]: P[Ast.ReturnStmt[Ctx]] =
    P(Lexer.token(Keyword.`return`) ~/ expr.rep(0, ",")).map(Ast.ReturnStmt.apply[Ctx])

  def stringInterpolator[Unknown: P]: P[Ast.Expr[Ctx]] =
    P("${" ~ expr ~ "}")

  def debug[Unknown: P]: P[Ast.Debug[Ctx]] =
    P("emit" ~ "Debug" ~/ "(" ~ Lexer.string(() => stringInterpolator) ~ ")").map {
      case (stringParts, interpolationParts) =>
        Ast.Debug(
          stringParts.map(s => Val.ByteVec(ByteString.fromString(s))),
          interpolationParts
        )
    }

  def anonymousVar[Unknown: P]: P[Ast.VarDeclaration] = P("_").map(_ => Ast.AnonymousVar)
  def namedVar[Unknown: P]: P[Ast.VarDeclaration] =
    P(Lexer.mut ~ Lexer.ident).map(Ast.NamedVar.tupled)

  def varDeclaration[Unknown: P]: P[Ast.VarDeclaration] = P(namedVar | anonymousVar)
  def varDeclarations[Unknown: P]: P[Seq[Ast.VarDeclaration]] = P(
    varDeclaration.map(Seq(_)) | "(" ~ varDeclaration.rep(1, ",") ~ ")"
  )
  def varDef[Unknown: P]: P[Ast.VarDef[Ctx]] =
    P(Lexer.token(Keyword.let) ~/ varDeclarations ~ "=" ~ expr).map { case (vars, expr) =>
      Ast.VarDef(vars, expr)
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
  def argument[Unknown: P](
      allowMutable: Boolean
  )(contractTypeCtor: (Ast.TypeId, Ast.Ident) => Type): P[Ast.Argument] =
    P(Lexer.unused ~ Lexer.mutMaybe(allowMutable) ~ Lexer.ident ~ ":").flatMap {
      case (isUnused, isMutable, ident) =>
        parseType(contractTypeCtor(_, ident)).map { tpe =>
          Ast.Argument(ident, tpe, isMutable, isUnused)
        }
    }
  def funcArgument[Unknown: P]: P[Ast.Argument] = argument(allowMutable = true)(Type.Contract.local)
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
          .token(
            Keyword.fn
          ) ~/ Lexer.funcId ~ funParams ~ returnType ~ ("{" ~ statement.rep ~ "}").?
    ).map { case (annotations, modifiers, funcId, params, returnType, statements) =>
      if (modifiers.toSet.size != modifiers.length) {
        throw Compiler.Error(s"Duplicated function modifiers: $modifiers")
      } else {
        val isPublic = modifiers.contains(Lexer.FuncModifier.Pub)
        val usingAnnotation = Parser.UsingAnnotation.extractFields(
          annotations,
          Parser.UsingAnnotationFields(
            preapprovedAssets = false,
            assetsInContract = false,
            checkExternalCaller = true,
            updateFields = false
          )
        )
        FuncDefTmp(
          annotations,
          funcId,
          isPublic,
          usingAnnotation.preapprovedAssets,
          usingAnnotation.assetsInContract,
          usingAnnotation.checkExternalCaller,
          usingAnnotation.updateFields,
          params,
          returnType,
          statements
        )
      }
    }
  def func[Unknown: P]: P[Ast.FuncDef[Ctx]] = funcTmp.map { f =>
    Ast.FuncDef(
      f.annotations,
      f.id,
      f.isPublic,
      f.usePreapprovedAssets,
      f.useContractAssets,
      f.useCheckExternalCaller,
      f.useUpdateFields,
      f.args,
      f.rtypes,
      f.body
    )
  }

  def eventFields[Unknown: P]: P[Seq[Ast.EventField]] = P("(" ~ eventField.rep(0, ",") ~ ")")
  def eventDef[Unknown: P]: P[Ast.EventDef] =
    P(Lexer.token(Keyword.event) ~/ Lexer.typeId ~ eventFields)
      .map { case (typeId, fields) =>
        if (fields.length >= Instr.allLogInstrs.length) {
          throw Compiler.Error("Max 8 fields allowed for contract events")
        }
        if (typeId.name == "Debug") {
          throw Compiler.Error("Debug is a built-in event name")
        }
        Ast.EventDef(typeId, fields)
      }

  def funcCall[Unknown: P]: P[Ast.Statement[Ctx]] =
    ((Lexer.typeId ~ ".").? ~ callAbs).map { case (contractIdOpt, (funcId, approveAssets, exprs)) =>
      contractIdOpt match {
        case Some(contractId) =>
          Ast.StaticContractFuncCall(contractId, funcId, approveAssets, exprs)
        case None => Ast.FuncCall(funcId, approveAssets, exprs)
      }
    }

  def block[Unknown: P]: P[Seq[Ast.Statement[Ctx]]]      = P("{" ~ statement.rep(1) ~ "}")
  def emptyBlock[Unknown: P]: P[Seq[Ast.Statement[Ctx]]] = P("{" ~ "}").map(_ => Seq.empty)
  def ifBranchStmt[Unknown: P]: P[Ast.IfBranchStatement[Ctx]] =
    P(Lexer.token(Keyword.`if`) ~/ "(" ~ expr ~ ")" ~ block).map { case (condition, body) =>
      Ast.IfBranchStatement(condition, body)
    }
  def elseIfBranchStmt[Unknown: P]: P[Ast.IfBranchStatement[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ ifBranchStmt)
  def elseBranchStmt[Unknown: P]: P[Ast.ElseBranchStatement[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ (block | emptyBlock)).map(Ast.ElseBranchStatement(_))
  def ifelseStmt[Unknown: P]: P[Ast.IfElseStatement[Ctx]] =
    P(ifBranchStmt ~ elseIfBranchStmt.rep(0) ~ elseBranchStmt.?)
      .map { case (ifBranch, elseIfBranches, elseBranchOpt) =>
        if (elseIfBranches.nonEmpty && elseBranchOpt.isEmpty) {
          throw Compiler.Error(
            "If ... else if constructs should be terminated with an else statement"
          )
        }
        Ast.IfElseStatement(ifBranch +: elseIfBranches, elseBranchOpt)
      }

  def whileStmt[Unknown: P]: P[Ast.While[Ctx]] =
    P(Lexer.token(Keyword.`while`) ~/ "(" ~ expr ~ ")" ~ block).map { case (expr, block) =>
      Ast.While(expr, block)
    }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def forLoopStmt[Unknown: P]: P[Ast.ForLoop[Ctx]] =
    P(
      Lexer.token(Keyword.`for`) ~/ "(" ~ statement.? ~ ";" ~ expr ~ ";" ~ statement.? ~ ")" ~ block
    )
      .map { case (initializeOpt, condition, updateOpt, body) =>
        if (initializeOpt.isEmpty) {
          throw Compiler.Error("No initialize statement in for loop")
        }
        if (updateOpt.isEmpty) {
          throw Compiler.Error("No update statement in for loop")
        }
        Ast.ForLoop(initializeOpt.get, condition, updateOpt.get, body)
      }

  def statement[Unknown: P]: P[Ast.Statement[Ctx]]

  def contractField[Unknown: P](allowMutable: Boolean): P[Ast.Argument] =
    argument(allowMutable)(Type.Contract.global)

  def templateParams[Unknown: P]: P[Seq[Ast.Argument]] =
    P("(" ~ contractField(allowMutable = false).rep(0, ",") ~ ")")

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
    useCheckExternalCaller: Boolean,
    useUpdateFields: Boolean,
    args: Seq[Argument],
    rtypes: Seq[Type],
    body: Option[Seq[Statement[Ctx]]]
)

object Parser {
  sealed trait RalphAnnotation[T] {
    def id: String
    def keys: AVector[String]
    def validate(annotations: Seq[Ast.Annotation]): Unit = {
      if (annotations.exists(_.id.name != id)) {
        throw Compiler.Error(s"Invalid annotation, expect @$id annotation")
      }
    }

    final def extractField[V <: Val](
        annotation: Ast.Annotation,
        key: String,
        tpe: Val.Type
    ): Option[V] = {
      annotation.fields.find(_.ident.name == key) match {
        case Some(Ast.AnnotationField(_, value: V @unchecked)) if tpe == value.tpe => Some(value)
        case Some(_) => throw Compiler.Error(s"Expect $tpe for $key in annotation @$id")
        case None    => None
      }
    }

    final def extractField[V <: Val](annotation: Ast.Annotation, key: String, default: V): V = {
      extractField[V](annotation, key, default.tpe).getOrElse(default)
    }

    final def extractFields(annotations: Seq[Ast.Annotation], default: T): T = {
      validate(annotations)
      annotations.headOption match {
        case Some(annotation) =>
          val invalidKeys = annotation.fields.filter(f => !keys.contains(f.ident.name))
          if (invalidKeys.nonEmpty) {
            throw Compiler.Error(
              s"Invalid keys for @$id annotation: ${invalidKeys.map(_.ident.name).mkString(",")}"
            )
          }
          extractFields(annotation, default)
        case None => default
      }
    }
    def extractFields(annotation: Ast.Annotation, default: T): T
  }

  final case class UsingAnnotationFields(
      preapprovedAssets: Boolean,
      assetsInContract: Boolean,
      checkExternalCaller: Boolean,
      updateFields: Boolean
  )

  object UsingAnnotation extends RalphAnnotation[UsingAnnotationFields] {
    val id: String                = "using"
    val usePreapprovedAssetsKey   = "preapprovedAssets"
    val useContractAssetsKey      = "assetsInContract"
    val useCheckExternalCallerKey = "checkExternalCaller"
    val useUpdateFieldsKey        = "updateFields"
    val keys: AVector[String] = AVector(
      usePreapprovedAssetsKey,
      useContractAssetsKey,
      useCheckExternalCallerKey,
      useUpdateFieldsKey
    )

    def extractFields(
        annotation: Ast.Annotation,
        default: UsingAnnotationFields
    ): UsingAnnotationFields = {
      UsingAnnotationFields(
        extractField(annotation, usePreapprovedAssetsKey, Val.Bool(default.preapprovedAssets)).v,
        extractField(annotation, useContractAssetsKey, Val.Bool(default.assetsInContract)).v,
        extractField(
          annotation,
          useCheckExternalCallerKey,
          Val.Bool(default.checkExternalCaller)
        ).v,
        extractField(annotation, useUpdateFieldsKey, Val.Bool(default.updateFields)).v
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
          throw Compiler.Error("The field id of the @std annotation must be a non-empty ByteVec")
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
object StatelessParser extends Parser[StatelessContext] {
  def atom[Unknown: P]: P[Ast.Expr[StatelessContext]] =
    P(const | alphTokenId | callExpr | contractConv | variable | parenExpr | arrayExpr | ifelseExpr)

  def statement[Unknown: P]: P[Ast.Statement[StatelessContext]] =
    P(varDef | assign | debug | funcCall | ifelseStmt | whileStmt | forLoopStmt | ret)

  def assetScript[Unknown: P]: P[Ast.AssetScript] =
    P(
      Start ~ Lexer.token(Keyword.AssetScript) ~/ Lexer.typeId ~ templateParams.? ~
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
      const | alphTokenId | callExpr | contractCallExpr | contractConv | enumFieldSelector | variable | parenExpr | arrayExpr | ifelseExpr
    )

  def contractCallExpr[Unknown: P]: P[Ast.ContractCallExpr] =
    P((contractConv | variable) ~ "." ~ callAbs).map { case (obj, (callId, approveAssets, exprs)) =>
      Ast.ContractCallExpr(obj, callId, approveAssets, exprs)
    }

  def contractCall[Unknown: P]: P[Ast.ContractCall] =
    P((contractConv | variableIdOnly) ~ "." ~ callAbs)
      .map { case (obj, (callId, approveAssets, exprs)) =>
        Ast.ContractCall(obj, callId, approveAssets, exprs)
      }

  def statement[Unknown: P]: P[Ast.Statement[StatefulContext]] =
    P(
      varDef | assign | debug | funcCall | contractCall | ifelseStmt | whileStmt | forLoopStmt | ret | emitEvent
    )

  def contractFields[Unknown: P]: P[Seq[Ast.Argument]] =
    P(
      "(" ~ contractField(allowMutable = true).rep(0, ",") ~ ")"
    )

  def rawTxScript[Unknown: P]: P[Ast.TxScript] =
    P(
      annotation.rep ~
        Lexer.token(
          Keyword.TxScript
        ) ~/ Lexer.typeId ~ templateParams.? ~ "{" ~ Index ~ statement
          .rep(0) ~ func
          .rep(0) ~ "}"
    )
      .map { case (annotations, typeId, templateVars, mainStmtsIndex, mainStmts, funcs) =>
        if (mainStmts.isEmpty) {
          throw CompilerError.`Expected main statements`(typeId, mainStmtsIndex)
        } else {
          val usingAnnotation = Parser.UsingAnnotation.extractFields(
            annotations,
            Parser.UsingAnnotationFields(
              preapprovedAssets = true,
              assetsInContract = false,
              checkExternalCaller = true,
              updateFields = false
            )
          )
          val mainFunc = Ast.FuncDef.main(
            mainStmts,
            usingAnnotation.preapprovedAssets,
            usingAnnotation.assetsInContract,
            usingAnnotation.updateFields
          )
          Ast.TxScript(typeId, templateVars.getOrElse(Seq.empty), mainFunc +: funcs)
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
    P(Lexer.token(Keyword.implements) ~ (interfaceInheritance.rep(1, ",")))

  def contractExtending[Unknown: P]: P[Seq[Ast.Inheritance]] =
    P(Lexer.token(Keyword.`extends`) ~ (contractInheritance.rep(1, ",")))

  def contractInheritances[Unknown: P]: P[Seq[Ast.Inheritance]] = {
    P(contractExtending.? ~ interfaceImplementing.?).map {
      case (extendingsOpt, implementingOpt) => {
        val implementedInterfaces = implementingOpt.getOrElse(Seq.empty)
        if (implementedInterfaces.length > 1) {
          val interfaceNames = implementedInterfaces.map(_.parentId.name).mkString(", ")
          throw Compiler.Error(
            s"Contract only supports implementing single interface: $interfaceNames"
          )
        }

        extendingsOpt.getOrElse(Seq.empty) ++ implementedInterfaces
      }
    }
  }

  def constantVarDef[Unknown: P]: P[Ast.ConstantVarDef] =
    P(Lexer.token(Keyword.const) ~/ Lexer.constantIdent ~ "=" ~ value).map { case (ident, v) =>
      Ast.ConstantVarDef(ident, v)
    }

  def enumFieldSelector[Unknown: P]: P[Ast.EnumFieldSelector[StatefulContext]] =
    P(Lexer.typeId ~ "." ~ Lexer.constantIdent).map { case (enumId, field) =>
      Ast.EnumFieldSelector(enumId, field)
    }
  def enumField[Unknown: P]: P[Ast.EnumField] =
    P(Lexer.constantIdent ~ "=" ~ value).map(Ast.EnumField.tupled)
  def rawEnumDef[Unknown: P]: P[Ast.EnumDef] =
    P(Lexer.token(Keyword.`enum`) ~/ Lexer.typeId ~ "{" ~ enumField.rep ~ "}").map {
      case (id, fields) =>
        if (fields.length == 0) {
          throw Compiler.Error(s"No field definition in Enum ${id.name}")
        }
        Ast.UniqueDef.checkDuplicates(fields, "enum fields")
        if (fields.distinctBy(_.value.tpe).size != 1) {
          throw Compiler.Error(s"Fields have different types in Enum ${id.name}")
        }
        if (fields.distinctBy(_.value).size != fields.length) {
          throw Compiler.Error(s"Fields have the same value in Enum ${id.name}")
        }
        Ast.EnumDef(id, fields)
    }
  def enumDef[Unknown: P]: P[Ast.EnumDef] = P(Start ~ rawEnumDef ~ End)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def rawContract[Unknown: P]: P[Ast.Contract] =
    P(
      annotation.rep ~ Lexer.`abstract` ~ Lexer.token(
        Keyword.Contract
      ) ~/ Lexer.typeId ~ contractFields ~
        contractInheritances.? ~ "{" ~ eventDef.rep ~ constantVarDef.rep ~ rawEnumDef.rep ~ func.rep ~ "}"
    ).map {
      case (
            annotations,
            isAbstract,
            typeId,
            fields,
            contractInheritances,
            events,
            constantVars,
            enums,
            funcs
          ) =>
        val contractStdAnnotation = Parser.ContractStdAnnotation.extractFields(annotations, None)
        Ast.Contract(
          contractStdAnnotation.map(_.enabled),
          None,
          isAbstract,
          typeId,
          Seq.empty,
          fields,
          funcs,
          events,
          constantVars,
          enums,
          contractInheritances.getOrElse(Seq.empty)
        )
    }
  def contract[Unknown: P]: P[Ast.Contract] = P(Start ~ rawContract ~ End)

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
            f.useCheckExternalCaller,
            f.useUpdateFields,
            f.args,
            f.rtypes,
            None
          )
        case _ =>
          throw Compiler.Error(s"Interface function ${f.id.name} should not have function body")
      }
    }
  }
  def rawInterface[Unknown: P]: P[Ast.ContractInterface] =
    P(
      annotation.rep ~ Lexer.token(Keyword.Interface) ~/ Lexer.typeId ~
        (Lexer.token(Keyword.`extends`) ~/ interfaceInheritance.rep(1, ",")).? ~
        "{" ~ eventDef.rep ~ interfaceFunc.rep ~ "}"
    ).map { case (annotations, typeId, inheritances, events, funcs) =>
      inheritances match {
        case Some(parents) if parents.length > 1 =>
          throw Compiler.Error(
            s"Interface only supports single inheritance: ${parents.map(_.parentId.name).mkString(", ")}"
          )
        case _ => ()
      }
      if (funcs.length < 1) {
        throw Compiler.Error(s"No function definition in Interface ${typeId.name}")
      } else {
        val stdIdOpt = Parser.InterfaceStdAnnotation.extractFields(annotations, None)
        Ast.ContractInterface(
          stdIdOpt.map(stdId => Val.ByteVec(stdId.id)),
          typeId,
          funcs,
          events,
          inheritances.getOrElse(Seq.empty)
        )
      }
    }
  def interface[Unknown: P]: P[Ast.ContractInterface] = P(Start ~ rawInterface ~ End)

  def multiContract[Unknown: P]: P[Ast.MultiContract] =
    P(Start ~ (rawTxScript | rawContract | rawInterface).rep(1) ~ End)
      .map(defs => Ast.MultiContract(defs, None))

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
