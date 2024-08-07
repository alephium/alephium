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

import scala.collection.mutable

import org.alephium.protocol.vm.{StatelessContext, Val}
import org.alephium.ralph.Compiler.{Error, VarInfo}

trait Constants[Ctx <: StatelessContext] {
  def getConstant(ident: Ast.Ident): VarInfo.Constant[Ctx]
  def getConstantValue(ident: Ast.Ident): Val = getConstant(ident).value
  protected def addConstant(ident: Ast.Ident, value: Val, constantDef: Ast.ConstantDefinition): Unit

  def addConstants(constantVars: Seq[Ast.ConstantVarDef[Ctx]]): Seq[(Ast.Ident, Val)] = {
    Ast.UniqueDef.checkDuplicates(constantVars, "constant variables")
    constantVars.map(c => (c.ident, calcAndAddConstant(c)))
  }

  def addEnums(enums: Seq[Ast.EnumDef[Ctx]]): Unit = {
    Ast.UniqueDef.checkDuplicates(enums, "enums")
    enums.foreach(e =>
      e.fields.foreach(field =>
        addConstant(Ast.EnumDef.fieldIdent(e.id, field.ident), field.value.v, field)
      )
    )
  }

  private def calcAndAddConstant(constantVarDef: Ast.ConstantVarDef[Ctx]): Val = {
    val value = calcConstant(constantVarDef.expr)
    addConstant(constantVarDef.ident, value, constantVarDef)
    value
  }

  @scala.annotation.tailrec
  final private[ralph] def calcConstant(expr: Ast.Expr[Ctx]): Val = {
    expr match {
      case e: Ast.Const[Ctx @unchecked]             => e.v
      case Ast.Variable(ident)                      => getConstantValue(ident)
      case e: Ast.EnumFieldSelector[Ctx @unchecked] => getConstantValue(e.fieldIdent)
      case Ast.ParenExpr(expr)                      => calcConstant(expr)
      case expr: Ast.Binop[Ctx @unchecked]          => calcBinOp(expr)
      case expr: Ast.UnaryOp[Ctx @unchecked]        => calcUnaryOp(expr)
      case _                                        => Constants.invalidConstantDef(expr)
    }
  }

  private def calcBinOp(expr: Ast.Binop[Ctx]): Val = {
    val left  = calcConstant(expr.left)
    val right = calcConstant(expr.right)
    checkAndCalc(expr, expr.op, Seq(left, right))
  }

  private def calcUnaryOp(expr: Ast.UnaryOp[Ctx]): Val = {
    val value = calcConstant(expr.expr)
    checkAndCalc(expr, expr.op, Seq(value))
  }

  private def checkAndCalc(expr: Ast.Expr[Ctx], op: Operator, values: Seq[Val]): Val = {
    expr.positionedError(op.getReturnType(values.map(v => Type.fromVal(v.tpe))))
    op.calc(values) match {
      case Right(value) => value
      case Left(error)  => throw Error(error, expr.sourceIndex)
    }
  }
}

object Constants {
  private[ralph] def invalidConstantDef[Ctx <: StatelessContext](
      expr: Ast.Expr[Ctx]
  ) = {
    val label = expr match {
      case _: Ast.CreateArrayExpr[_] => "arrays"
      case _: Ast.StructCtor[_]      => "structs"
      case _: Ast.ContractConv[_]    => "contract instances"
      case _: Ast.Positioned         => "other expressions"
    }
    val primitiveTypes = Type.primitives.map(_.signature).mkString("/")
    throw Error(
      s"Expected constant value with primitive types $primitiveTypes, $label are not supported",
      expr.sourceIndex
    )
  }

  private[ralph] class CachedConstant(
      val definition: Ast.ConstantDefinition,
      var calculated: Option[Val],
      var isCalculating: Boolean
  ) {
    def setCalculated(value: Val): Unit = {
      calculated = Some(value)
      isCalculating = false
    }
  }

  private def newConstant(definition: Ast.ConstantDefinition) = {
    new CachedConstant(definition, None, false)
  }

  final private[ralph] class LazyEvaluatedConstants[Ctx <: StatelessContext]
      extends Constants[Ctx] {
    private[ralph] val constants = mutable.Map.empty[Ast.Ident, CachedConstant]

    override def getConstantValue(ident: Ast.Ident): Val = {
      constants.get(ident) match {
        case Some(constant) => constant.calculated.getOrElse(calcConstant(ident, constant))
        case _ =>
          throw Compiler.Error(s"Constant variable ${ident.name} does not exist", ident.sourceIndex)
      }
    }

    def addConstant(ident: Ast.Ident, value: Val, constantDef: Ast.ConstantDefinition): Unit = ???
    def getConstant(ident: Ast.Ident): VarInfo.Constant[Ctx]                                 = ???

    private def calcConstant(ident: Ast.Ident, constant: CachedConstant): Val = {
      if (constant.isCalculating) {
        throw Compiler.Error(
          s"Found circular reference when evaluating constant ${ident.name}",
          ident.sourceIndex
        )
      }
      constant.isCalculating = true
      val definition = constant.definition
      val value = definition match {
        case field: Ast.EnumField[Ctx @unchecked]         => field.value.v
        case constant: Ast.ConstantVarDef[Ctx @unchecked] => calcConstant(constant.expr)
      }
      constant.setCalculated(value)
      value
    }

    override def addConstants(
        constantVars: Seq[Ast.ConstantVarDef[Ctx]]
    ): Seq[(Ast.Ident, Val)] = {
      constantVars.foreach(c => constants.addOne(c.ident -> newConstant(c)))
      Seq.empty
    }

    override def addEnums(enums: Seq[Ast.EnumDef[Ctx]]): Unit = {
      enums.foreach(e =>
        e.fields.foreach { field =>
          val fieldIdent = Ast.EnumDef.fieldIdent(e.id, field.ident)
          constants.addOne(fieldIdent -> newConstant(field))
        }
      )
    }
  }

  private[ralph] def lazyEvaluated[Ctx <: StatelessContext]: LazyEvaluatedConstants[Ctx] =
    new LazyEvaluatedConstants[Ctx]
}
