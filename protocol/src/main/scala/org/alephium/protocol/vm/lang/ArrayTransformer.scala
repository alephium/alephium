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
    val offset = ConstantArrayVarOffset[Ctx](state.varIndex)
    initArrayVars(state, tpe, baseName, isMutable, isLocal, varInfoBuild)
    val ref = ArrayRef[Ctx](isLocal, tpe, offset)
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

  sealed trait ArrayVarOffset[Ctx <: StatelessContext] {
    def add(offset: ArrayVarOffset[Ctx]): ArrayVarOffset[Ctx]
    def add(value: Int): ArrayVarOffset[Ctx]              = add(ConstantArrayVarOffset[Ctx](value))
    def add(instrs: Seq[Instr[Ctx]]): ArrayVarOffset[Ctx] = add(VariableArrayVarOffset[Ctx](instrs))
  }

  final case class ConstantArrayVarOffset[Ctx <: StatelessContext](value: Int)
      extends ArrayVarOffset[Ctx] {
    def add(offset: ArrayVarOffset[Ctx]): ArrayVarOffset[Ctx] = offset match {
      case ConstantArrayVarOffset(v) => ConstantArrayVarOffset(value + v)
      case VariableArrayVarOffset(instrs) =>
        if (value == 0) {
          offset
        } else {
          VariableArrayVarOffset(
            instrs ++ Seq[Instr[Ctx]](ConstInstr.u256(Val.U256(U256.unsafe(value))), U256Add)
          )
        }
    }
  }

  final case class VariableArrayVarOffset[Ctx <: StatelessContext](instrs: Seq[Instr[Ctx]])
      extends ArrayVarOffset[Ctx] {
    def add(offset: ArrayVarOffset[Ctx]): ArrayVarOffset[Ctx] = offset match {
      case ConstantArrayVarOffset(v) =>
        if (v == 0) {
          this
        } else {
          VariableArrayVarOffset(
            instrs ++ Seq[Instr[Ctx]](ConstInstr.u256(Val.U256(U256.unsafe(v))), U256Add)
          )
        }
      case VariableArrayVarOffset(codes) => VariableArrayVarOffset(instrs ++ codes :+ U256Add)
    }
  }

  final case class ArrayRef[Ctx <: StatelessContext](
      isLocal: Boolean,
      tpe: Type.FixedSizeArray,
      offset: ArrayVarOffset[Ctx]
  ) {
    @scala.annotation.tailrec
    def subArray(state: Compiler.State[Ctx], indexes: Seq[Ast.Expr[Ctx]]): ArrayRef[Ctx] = {
      if (indexes.isEmpty) {
        this
      } else {
        subArray(state, indexes(0)).subArray(state, indexes.drop(1))
      }
    }

    private def subArray(state: Compiler.State[Ctx], index: Ast.Expr[Ctx]): ArrayRef[Ctx] = {
      val (baseType, flattenSize) = tpe.baseType match {
        case baseType: Type.FixedSizeArray =>
          val length = baseType.flattenSize()
          (baseType, length)
        case _ =>
          throw Compiler.Error(s"Expect multi-dimension array type, have $tpe")
      }
      val newOffset = calcOffset(state, index) match {
        case ConstantArrayVarOffset(value) =>
          offset.add(value * flattenSize)
        case VariableArrayVarOffset(instrs) =>
          offset.add(instrs ++ Seq(ConstInstr.u256(Val.U256.unsafe(flattenSize)), U256Mul))
      }
      ArrayRef(isLocal, baseType, newOffset)
    }

    private def calcOffset(
        state: Compiler.State[Ctx],
        index: Ast.Expr[Ctx]
    ): ArrayVarOffset[Ctx] = {
      Compiler.State.getAndCheckConstantIndex(index) match {
        case Some(idx) =>
          checkArrayIndex(idx, tpe.size)
          ConstantArrayVarOffset(idx)
        case None =>
          val instrs = index.genCode(state) ++ Seq(
            Dup,
            ConstInstr.u256(Val.U256(U256.unsafe(tpe.size))),
            U256Lt,
            Assert
          )
          VariableArrayVarOffset(instrs)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    private def calcOffset(
        state: Compiler.State[Ctx],
        indexes: Seq[Ast.Expr[Ctx]]
    ): ArrayVarOffset[Ctx] = {
      assume(indexes.nonEmpty)
      val subArrayRef = subArray(state, indexes.dropRight(1))
      subArrayRef.offset.add(subArrayRef.calcOffset(state, indexes.last))
    }

    private def storeArrayIndexVar(
        state: Compiler.State[Ctx],
        instrs: Seq[Instr[Ctx]]
    ): (Ast.Ident, Seq[Instr[Ctx]]) = {
      val ident = state.getArrayIndexVar()
      (ident, instrs ++ state.genStoreCode(ident).flatten)
    }

    def genLoadCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val flattenSize = tpe.flattenSize()
      offset match {
        case VariableArrayVarOffset(instrs) =>
          val (ident, codes) = storeArrayIndexVar(state, instrs)
          val loadCodes = (0 until flattenSize).flatMap { idx =>
            val calcOffsetCode = state.genLoadCode(ident) ++ Seq(
              ConstInstr.u256(Val.U256(U256.unsafe(idx))),
              U256Add
            )
            state.genLoadCode(VariableArrayVarOffset(calcOffsetCode), isLocal)
          }
          codes ++ loadCodes
        case ConstantArrayVarOffset(value) =>
          (0 until flattenSize).flatMap(idx =>
            state.genLoadCode(
              ConstantArrayVarOffset(value + idx),
              isLocal
            )
          )
      }
    }

    def genStoreCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
      val flattenSize = tpe.flattenSize()
      offset match {
        case VariableArrayVarOffset(instrs) =>
          val (ident, codes) = storeArrayIndexVar(state, instrs)
          val storeCodes = (0 until flattenSize) map { idx =>
            val calcOffsetCode = state.genLoadCode(ident) ++ Seq(
              ConstInstr.u256(Val.U256(U256.unsafe(idx))),
              U256Add
            )
            state.genStoreCode(VariableArrayVarOffset(calcOffsetCode), isLocal)
          }
          storeCodes :+ codes
        case ConstantArrayVarOffset(value) =>
          (0 until flattenSize) map { idx =>
            state.genStoreCode(ConstantArrayVarOffset(value + idx), isLocal)
          }
      }
    }

    def genLoadCode(state: Compiler.State[Ctx], indexes: Seq[Ast.Expr[Ctx]]): Seq[Instr[Ctx]] = {
      state.genLoadCode(calcOffset(state, indexes), isLocal)
    }

    def genStoreCode(
        state: Compiler.State[Ctx],
        indexes: Seq[Ast.Expr[Ctx]]
    ): Seq[Seq[Instr[Ctx]]] = {
      Seq(state.genStoreCode(calcOffset(state, indexes), isLocal))
    }
  }
}
