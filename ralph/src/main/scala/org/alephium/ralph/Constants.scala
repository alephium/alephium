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

  final private[ralph] def calcArraySize(tpe: Type.FixedSizeArray[Ctx]): Int = {
    tpe.size match {
      case Left(size) => size
      case Right(expr) =>
        val arraySize = calcArraySize(expr)
        tpe.size = Left(arraySize)
        arraySize
    }
  }

  final private[ralph] def calcArraySize(expr: Ast.Expr[Ctx]): Int = {
    calcConstant(expr) match {
      case Val.U256(value) => value.toBigInt.intValue()
      case _ =>
        throw Compiler.Error("Invalid array size, expected a constant U256 value", expr.sourceIndex)
    }
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
}
