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

import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Ast.Ident
import org.alephium.util.U256

object ArrayTransformer {
  @inline def arrayVarName(baseName: String, idx: Int): String = s"_$baseName-$idx"

  def init[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      tpe: Type.FixedSizeArray,
      baseName: String,
      isMutable: Boolean,
      isLocal: Boolean,
      varInfoBuild: (Type, Boolean, Byte) => Compiler.VarInfo
  ): ArrayRef[Ctx] = {
    val varIndexOffset = Ast.Const[Ctx](Val.U256(U256.unsafe(state.varIndex)))
    initArrayVars(state, tpe, baseName, isMutable, isLocal, varInfoBuild)
    val ref = ArrayRef[Ctx](isLocal, tpe, varIndexOffset)
    state.addArrayRef(Ident(baseName), isMutable, ref)
    ref
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def initArrayVars[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      tpe: Type.FixedSizeArray,
      baseName: String,
      isMutable: Boolean,
      isLocal: Boolean,
      varInfoBuild: (Type, Boolean, Byte) => Compiler.VarInfo
  ): Unit = {
    tpe.baseType match {
      case baseType: Type.FixedSizeArray =>
        (0 until tpe.size).foreach { idx =>
          val newBaseName = arrayVarName(baseName, idx)
          initArrayVars(state, baseType, newBaseName, isMutable, isLocal, varInfoBuild)
        }
      case baseType =>
        (0 until tpe.size).foreach { idx =>
          val ident = Ast.Ident(arrayVarName(baseName, idx))
          state.addVariable(ident, baseType, isMutable, isLocal, varInfoBuild)
          ident
        }
    }
  }

  def flattenTypeLength(types: Seq[Type]): Int = {
    types.foldLeft(0) { case (acc, tpe) =>
      tpe match {
        case t: Type.FixedSizeArray => acc + t.flattenSize()
        case _                      => acc + 1
      }
    }
  }

  @inline def checkArrayIndex(index: Int, arraySize: Int): Unit = {
    if (index < 0 || index >= arraySize) {
      throw Compiler.Error(s"Invalid array index: $index, array size: $arraySize")
    }
  }

  final case class ArrayRef[Ctx <: StatelessContext](
      isLocal: Boolean,
      tpe: Type.FixedSizeArray,
      varIndexOffset: Ast.Expr[Ctx]
  ) {
    private def checkVarIndex(index: Ast.Expr[Ctx], state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      state.getAndCheckConstantIndex(index) match {
        case Some(idx) =>
          checkArrayIndex(idx, tpe.size)
          Seq.empty[Instr[Ctx]]
        case _ =>
          index.genCode(state) ++ Seq(
            ConstInstr.u256(Val.U256(U256.unsafe(tpe.size))),
            U256Lt,
            Assert
          )
      }
    }

    def subArray(
        index: Ast.Expr[Ctx],
        state: Compiler.State[Ctx]
    ): (Seq[Instr[Ctx]], ArrayRef[Ctx]) = {
      tpe.baseType match {
        case baseType: Type.FixedSizeArray =>
          val length = baseType.flattenSize()
          val size   = Ast.Binop(ArithOperator.Mul, index, Ast.Const(Val.U256(U256.unsafe(length))))
          val offset = Ast.Binop(ArithOperator.Add, varIndexOffset, size)
          val checkCode = checkVarIndex(index, state)
          (checkCode, ArrayRef(isLocal, baseType, offset))
        case _ =>
          throw Compiler.Error(s"Expect multi-dimension array type, have $tpe")
      }
    }

    def genLoadCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      tpe.baseType match {
        case _: Type.FixedSizeArray =>
          (0 until tpe.size).flatMap { index =>
            val indexAst = Ast.Const[Ctx](Val.U256(U256.unsafe(index)))
            genLoadCode(indexAst, state)
          }
        case _ =>
          (0 until tpe.size).flatMap { index =>
            val indexAst = Ast.Const[Ctx](Val.U256(U256.unsafe(index)))
            genLoadCode(indexAst, state)
          }
      }
    }

    def genLoadCode(index: Ast.Expr[Ctx], state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      tpe.baseType match {
        case _: Type.FixedSizeArray =>
          val (checkCode, array) = subArray(index, state)
          checkCode ++ array.genLoadCode(state)
        case _ =>
          val checkCode = checkVarIndex(index, state)
          val varIndex  = Ast.Binop(ArithOperator.Add, varIndexOffset, index)
          checkCode ++ state.genLoadCode(varIndex, isLocal)
      }
    }

    def genStoreCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
      tpe.baseType match {
        case _: Type.FixedSizeArray =>
          (0 until tpe.size) flatMap { index =>
            val indexExpr = Ast.Const[Ctx](Val.U256(U256.unsafe(index)))
            genStoreCode(indexExpr, state)
          }
        case _ =>
          (0 until tpe.size) flatMap { index =>
            val indexExpr = Ast.Const[Ctx](Val.U256(U256.unsafe(index)))
            genStoreCode(indexExpr, state)
          }
      }
    }

    def genStoreCode(index: Ast.Expr[Ctx], state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
      tpe.baseType match {
        case _: Type.FixedSizeArray =>
          val (checkCode, array) = subArray(index, state)
          // Put the check code at the end to make sure the it executes first
          array.genStoreCode(state) :+ checkCode
        case _ =>
          val checkCode = checkVarIndex(index, state)
          val varIndex  = Ast.Binop(ArithOperator.Add, varIndexOffset, index)
          Seq(checkCode ++ state.genStoreCode(varIndex, isLocal))
      }
    }
  }
}
