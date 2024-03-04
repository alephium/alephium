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

import org.alephium.protocol.vm._
import org.alephium.util.U256

sealed trait VarOffset[Ctx <: StatelessContext] {
  def add(offset: VarOffset[Ctx]): VarOffset[Ctx]
  def add(value: Int): VarOffset[Ctx]              = add(ConstantVarOffset[Ctx](value))
  def add(instrs: Seq[Instr[Ctx]]): VarOffset[Ctx] = add(VariableVarOffset[Ctx](instrs))
}

final case class ConstantVarOffset[Ctx <: StatelessContext](value: Int) extends VarOffset[Ctx] {
  def add(offset: VarOffset[Ctx]): VarOffset[Ctx] = offset match {
    case ConstantVarOffset(v) => ConstantVarOffset(value + v)
    case VariableVarOffset(instrs) =>
      if (value == 0) {
        offset
      } else {
        VariableVarOffset(
          instrs ++ Seq[Instr[Ctx]](ConstInstr.u256(Val.U256(U256.unsafe(value))), U256Add)
        )
      }
  }
}

final case class VariableVarOffset[Ctx <: StatelessContext](instrs: Seq[Instr[Ctx]])
    extends VarOffset[Ctx] {
  def add(offset: VarOffset[Ctx]): VarOffset[Ctx] = offset match {
    case ConstantVarOffset(v) =>
      if (v == 0) {
        this
      } else {
        VariableVarOffset(
          instrs ++ Seq[Instr[Ctx]](ConstInstr.u256(Val.U256(U256.unsafe(v))), U256Add)
        )
      }
    case VariableVarOffset(codes) => VariableVarOffset(instrs ++ codes :+ U256Add)
  }
}

sealed trait VariablesRef[Ctx <: StatelessContext] {
  type Selector

  def ident: Ast.Ident
  def isLocal: Boolean
  def isMutable: Boolean
  def isTemplate: Boolean
  def offset: VarOffset[Ctx]
  def tpe: Type

  def subRef(state: Compiler.State[Ctx], selector: Selector): VariablesRef[Ctx]

  def genLoadCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  def genLoadCode(state: Compiler.State[Ctx], selector: Selector): Seq[Instr[Ctx]]
  def genStoreCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]]
  def genStoreCode(state: Compiler.State[Ctx], selector: Selector): Seq[Seq[Instr[Ctx]]]
}

object VariablesRef {
  @inline def getOffset[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      getIndex: () => Byte,
      isTemplate: Boolean,
      isLocal: Boolean,
      isMutable: Boolean
  ): VarOffset[Ctx] = {
    if (isTemplate) {
      ConstantVarOffset[Ctx](getIndex().toInt)
    } else if (isLocal) {
      ConstantVarOffset[Ctx](state.currentScopeState.varIndex)
    } else if (isMutable) {
      ConstantVarOffset[Ctx](state.mutFieldsIndex)
    } else {
      ConstantVarOffset[Ctx](state.immFieldsIndex)
    }
  }

  // scalastyle:off parameter.number
  // scalastyle:off method.length
  def init[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      tpe: Type,
      baseName: String,
      isMutable: Boolean,
      isUnused: Boolean,
      isLocal: Boolean,
      isGenerated: Boolean,
      isTemplate: Boolean,
      varInfoBuilder: Compiler.VarInfoBuilder,
      getIndex: () => Byte,
      increaseIndex: () => Unit
  ): VariablesRef[Ctx] = {
    val offset = VariablesRef.getOffset(state, getIndex, isTemplate, isLocal, isMutable)
    val ref: VariablesRef[Ctx] = tpe match {
      case arrayType: Type.FixedSizeArray =>
        (0 until arrayType.size).foreach { idx =>
          val ident = Ast.Ident(ArrayRef.arrayVarName(baseName, idx))
          state.addVariable(
            ident,
            arrayType.baseType,
            isMutable,
            isUnused,
            isLocal,
            isGenerated = true,
            isTemplate,
            varInfoBuilder,
            getIndex,
            increaseIndex
          )
        }
        val ident = Ast.Ident(baseName)
        ArrayRef[Ctx](ident, arrayType, isLocal, isMutable, isTemplate, offset)
      case structType: Type.Struct =>
        val ast = state.getStruct(structType.id)
        ast.fields.foreach { field =>
          val ident = Ast.Ident(StructRef.structVarName(baseName, field.name))
          state.addVariable(
            ident,
            field.tpe,
            isMutable,
            isUnused,
            isLocal,
            isGenerated = true,
            isTemplate,
            varInfoBuilder,
            getIndex,
            increaseIndex
          )
        }
        val ident = Ast.Ident(baseName)
        StructRef[Ctx](ident, isLocal, isMutable, isTemplate, offset, ast)
      case _ => // dead branch
        throw Compiler.Error(s"Expected array or struct type, got $tpe", None)
    }
    state.addVariablesRef(ref.ident, isMutable, isUnused, isGenerated, ref)
    ref
  }
  // scalastyle:on parameter.number
  // scalastyle:on method.length
}

final case class StructRef[Ctx <: StatelessContext](
    ident: Ast.Ident,
    isLocal: Boolean,
    isMutable: Boolean,
    isTemplate: Boolean,
    offset: VarOffset[Ctx],
    ast: Ast.Struct
) extends VariablesRef[Ctx] {
  type Selector = Ast.Ident

  def tpe: Type.Struct = ast.tpe

  def subRef(state: Compiler.State[Ctx], selector: Ast.Ident): VariablesRef[Ctx] = {
    ast.getField(selector).tpe match {
      case tpe: Type.FixedSizeArray =>
        val newOffset = offset.add(ast.offsetOf(selector))
        ArrayRef(selector, tpe, isLocal, isMutable, isTemplate, newOffset)
      case tpe: Type.Struct =>
        val struct    = state.getStruct(tpe.id)
        val newOffset = offset.add(ast.offsetOf(selector))
        StructRef(selector, isLocal, isMutable, isTemplate, newOffset, struct)
      case tpe =>
        throw Compiler.Error(s"Expected array or struct type, got $tpe", selector.sourceIndex)
    }
  }

  private def genStoreCode(
      state: Compiler.State[Ctx],
      selector: Ast.Ident,
      tpe: Type
  ): Seq[Instr[Ctx]] = {
    tpe match {
      case _: Type.FixedSizeArray =>
        subRef(state, selector).genStoreCode(state).reverse.flatten
      case _: Type.Struct =>
        subRef(state, selector).genStoreCode(state).reverse.flatten
      case _ =>
        val newOffset = offset.add(ast.offsetOf(selector))
        state.genStoreCode(newOffset, isLocal)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def genStoreCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
    ast.fields.map(field => genStoreCode(state, field.ident, field.tpe))
  }

  def genStoreCode(state: Compiler.State[Ctx], selector: Ast.Ident): Seq[Seq[Instr[Ctx]]] = {
    val field = ast.getField(selector)
    Seq(genStoreCode(state, field.ident, field.tpe))
  }

  private def genLoadCode(
      state: Compiler.State[Ctx],
      selector: Ast.Ident,
      tpe: Type
  ): Seq[Instr[Ctx]] = {
    tpe match {
      case _: Type.FixedSizeArray =>
        subRef(state, selector).genLoadCode(state)
      case _: Type.Struct =>
        subRef(state, selector).genLoadCode(state)
      case _ =>
        val newOffset = offset.add(ast.offsetOf(selector))
        state.genLoadCode(this, tpe, newOffset)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def genLoadCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
    ast.fields.flatMap(field => genLoadCode(state, field.ident, field.tpe))
  }

  def genLoadCode(state: Compiler.State[Ctx], selector: Ast.Ident): Seq[Instr[Ctx]] = {
    val field = ast.getField(selector)
    genLoadCode(state, field.ident, field.tpe)
  }
}

object StructRef {
  @inline def structVarName(baseName: String, field: String): String = s"_$baseName-$field"
}

final case class ArrayRef[Ctx <: StatelessContext](
    ident: Ast.Ident,
    tpe: Type.FixedSizeArray,
    isLocal: Boolean,
    isMutable: Boolean,
    isTemplate: Boolean,
    offset: VarOffset[Ctx]
) extends VariablesRef[Ctx] {
  type Selector = Ast.Expr[Ctx]

  def subRef(state: Compiler.State[Ctx], index: Ast.Expr[Ctx]): VariablesRef[Ctx] = {
    tpe.baseType match {
      case baseType: Type.FixedSizeArray =>
        val flattenSize = baseType.flattenSize()
        val newOffset   = calcOffset(state, index, flattenSize)
        ArrayRef(ident, baseType, isLocal, isMutable, isTemplate, newOffset)
      case tpe: Type.Struct =>
        val struct    = state.getStruct(tpe.id)
        val newOffset = calcOffset(state, index, tpe.flattenSize)
        StructRef(ident, isLocal, isMutable, isTemplate, newOffset, struct)
      case _ =>
        throw Compiler.Error(s"Expect multi-dimension array type, have $tpe", index.sourceIndex)
    }
  }

  private def calcOffset(
      state: Compiler.State[Ctx],
      index: Ast.Expr[Ctx],
      flattenSize: Int
  ): VarOffset[Ctx] = {
    calcOffset(state, index) match {
      case ConstantVarOffset(value) =>
        offset.add(value * flattenSize)
      case VariableVarOffset(instrs) =>
        offset.add(instrs ++ Seq(ConstInstr.u256(Val.U256.unsafe(flattenSize)), U256Mul))
    }
  }

  private def calcOffset(
      state: Compiler.State[Ctx],
      index: Ast.Expr[Ctx]
  ): VarOffset[Ctx] = {
    Compiler.State.getAndCheckConstantIndex(index) match {
      case Some(idx) =>
        ArrayRef.checkArrayIndex(idx, tpe.size, index.sourceIndex)
        ConstantVarOffset(idx)
      case None =>
        val instrs = index.genCode(state) ++ Seq(
          Dup,
          ConstInstr.u256(Val.U256(U256.unsafe(tpe.size))),
          U256Lt,
          Assert
        )
        VariableVarOffset(instrs)
    }
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
      case VariableVarOffset(instrs) =>
        val (ident, codes) = storeArrayIndexVar(state, instrs)
        val loadCodes = (0 until flattenSize).flatMap { idx =>
          val calcOffsetCode = state.genLoadCode(ident) ++ Seq(
            ConstInstr.u256(Val.U256(U256.unsafe(idx))),
            U256Add
          )
          state.genLoadCode(this, tpe.elementType, VariableVarOffset(calcOffsetCode))
        }
        codes ++ loadCodes
      case ConstantVarOffset(value) =>
        (0 until flattenSize).flatMap(idx =>
          state.genLoadCode(this, tpe.elementType, ConstantVarOffset(value + idx))
        )
    }
  }

  def genStoreCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
    val flattenSize = tpe.flattenSize()
    offset match {
      case VariableVarOffset(instrs) =>
        val (ident, codes) = storeArrayIndexVar(state, instrs)
        val storeCodes = (0 until flattenSize) map { idx =>
          val calcOffsetCode = state.genLoadCode(ident) ++ Seq(
            ConstInstr.u256(Val.U256(U256.unsafe(idx))),
            U256Add
          )
          state.genStoreCode(VariableVarOffset(calcOffsetCode), isLocal)
        }
        storeCodes :+ codes
      case ConstantVarOffset(value) =>
        (0 until flattenSize) map { idx =>
          state.genStoreCode(ConstantVarOffset(value + idx), isLocal)
        }
    }
  }

  def genLoadCode(
      state: Compiler.State[Ctx],
      index: Ast.Expr[Ctx]
  ): Seq[Instr[Ctx]] = {
    tpe.baseType match {
      case _: Type.FixedSizeArray =>
        subRef(state, index).genLoadCode(state)
      case _: Type.Struct =>
        subRef(state, index).genLoadCode(state)
      case _ => state.genLoadCode(this, tpe.baseType, offset.add(calcOffset(state, index)))
    }
  }

  def genStoreCode(
      state: Compiler.State[Ctx],
      index: Ast.Expr[Ctx]
  ): Seq[Seq[Instr[Ctx]]] = {
    tpe.baseType match {
      case _: Type.FixedSizeArray => subRef(state, index).genStoreCode(state)
      case _: Type.Struct         => subRef(state, index).genStoreCode(state)
      case _ => Seq(state.genStoreCode(offset.add(calcOffset(state, index)), isLocal))
    }
  }
}

object ArrayRef {
  @inline def arrayVarName(baseName: String, idx: Int): String = s"_$baseName-$idx"

  @inline def checkArrayIndex(
      index: Int,
      arraySize: Int,
      sourceIndex: Option[SourceIndex]
  ): Unit = {
    if (index < 0 || index >= arraySize) {
      throw Compiler.Error(s"Invalid array index: $index, array size: $arraySize", sourceIndex)
    }
  }
}
