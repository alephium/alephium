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

sealed trait VariablesRefOffset[Ctx <: StatelessContext] extends Product with Serializable {
  def getStoreOffset: VarOffset[Ctx]
}
final case class LocalRefOffset[Ctx <: StatelessContext](offset: VarOffset[Ctx])
    extends VariablesRefOffset[Ctx] {
  def getStoreOffset: VarOffset[Ctx] = offset
}
final case class FieldRefOffset[Ctx <: StatelessContext](
    immFieldOffset: VarOffset[Ctx],
    mutFieldOffset: VarOffset[Ctx]
) extends VariablesRefOffset[Ctx] {
  def getStoreOffset: VarOffset[Ctx] = mutFieldOffset
}

sealed trait VariablesRef[Ctx <: StatelessContext] {
  type Selector

  def ident: Ast.Ident
  def isLocal: Boolean
  def isMutable: Boolean
  def isTemplate: Boolean
  def tpe: Type

  def subRef(state: Compiler.State[Ctx], selector: Selector): VariablesRef[Ctx]
  def genLoadFieldsCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]]

  def genLoadCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  def genStoreCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]]
}

object VariablesRef {
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
      varInfoBuilder: Compiler.VarInfoBuilder
  ): VariablesRef[Ctx] = {
    val refOffset = if (isLocal) {
      LocalRefOffset[Ctx](ConstantVarOffset(state.currentScopeState.varIndex))
    } else if (isTemplate) {
      LocalRefOffset[Ctx](ConstantVarOffset(state.templateVarIndex))
    } else {
      FieldRefOffset[Ctx](
        ConstantVarOffset(state.immFieldsIndex),
        ConstantVarOffset(state.mutFieldsIndex)
      )
    }
    val varType = state.resolveType(tpe)
    val ref: VariablesRef[Ctx] = varType match {
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
            varInfoBuilder
          )
        }
        val ident = Ast.Ident(baseName)
        ArrayRef.from(ident, arrayType, isLocal, isMutable, isTemplate, refOffset)
      case structType: Type.Struct =>
        val ast = state.getStruct(structType.id)
        ast.fields.foreach { field =>
          val ident = Ast.Ident(StructRef.structVarName(baseName, field.name))
          state.addVariable(
            ident,
            state.resolveType(field.tpe),
            isMutable && field.isMutable,
            isUnused,
            isLocal,
            isGenerated = true,
            isTemplate,
            varInfoBuilder
          )
        }
        val ident = Ast.Ident(baseName)
        StructRef.from(ident, isLocal, isMutable, isTemplate, refOffset, ast)
      case _ => // dead branch
        throw Compiler.Error(s"Expected array or struct type, got $varType", None)
    }
    state.addVariablesRef(ref.ident, isMutable, isUnused, isGenerated, ref)
    ref
  }
  // scalastyle:on parameter.number
  // scalastyle:on method.length
}

sealed trait StructRef[Ctx <: StatelessContext] extends VariablesRef[Ctx] {
  type Selector = Ast.Ident
  def ast: Ast.Struct
  def tpe: Type.Struct = ast.tpe
  def refOffset: VariablesRefOffset[Ctx]

  def calcRefOffset(state: Compiler.State[Ctx], selector: Ast.Ident): VariablesRefOffset[Ctx]
  def calcFieldOffset(state: Compiler.State[Ctx], selector: Ast.Ident): VarOffset[Ctx]

  def subRef(state: Compiler.State[Ctx], selector: Ast.Ident): VariablesRef[Ctx] = {
    val field     = ast.getField(selector)
    val refOffset = calcRefOffset(state, selector)
    state.resolveType(field.tpe) match {
      case tpe: Type.FixedSizeArray =>
        ArrayRef.from(selector, tpe, isLocal, isMutable && field.isMutable, isTemplate, refOffset)
      case tpe: Type.Struct =>
        val struct       = state.getStruct(tpe.id)
        val isRefMutable = isMutable && field.isMutable
        StructRef.from(selector, isLocal, isRefMutable, isTemplate, refOffset, struct)
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
      case _: Type.FixedSizeArray | _: Type.Struct =>
        subRef(state, selector).genStoreCode(state).reverse.flatten
      case _ =>
        val offset = calcFieldOffset(state, selector)
        state.genStoreCode(offset, isLocal)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def genStoreCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
    ast.fields.map(field => genStoreCode(state, field.ident, state.resolveType(field.tpe)))
  }

  def genStoreCode(state: Compiler.State[Ctx], selector: Ast.Ident): Seq[Seq[Instr[Ctx]]] = {
    val field = ast.getField(selector)
    Seq(genStoreCode(state, field.ident, state.resolveType(field.tpe)))
  }

  private def genLoadCode(
      state: Compiler.State[Ctx],
      selector: Ast.Ident,
      tpe: Type
  ): Seq[Instr[Ctx]] = {
    tpe match {
      case _: Type.FixedSizeArray | _: Type.Struct =>
        subRef(state, selector).genLoadCode(state)
      case _ =>
        val field  = ast.getField(selector)
        val offset = calcFieldOffset(state, selector)
        state.genLoadCode(ident, isTemplate, isLocal, isMutable && field.isMutable, tpe, offset)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def genLoadCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
    ast.fields.flatMap(field => genLoadCode(state, field.ident, state.resolveType(field.tpe)))
  }

  def genLoadFieldsCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
    ast.fields.foldLeft(Seq.empty[Seq[Instr[Ctx]]]) { case (acc, field) =>
      state.resolveType(field.tpe) match {
        case _: Type.Struct | _: Type.FixedSizeArray =>
          acc ++ subRef(state, field.ident).genLoadFieldsCode(state)
        case _ => acc :+ genLoadCode(state, field.ident)
      }
    }
  }

  def genLoadCode(state: Compiler.State[Ctx], selector: Ast.Ident): Seq[Instr[Ctx]] = {
    val field = ast.getField(selector)
    genLoadCode(state, field.ident, state.resolveType(field.tpe))
  }
}

final case class FieldStructRef[Ctx <: StatelessContext](
    ident: Ast.Ident,
    isLocal: Boolean,
    isMutable: Boolean,
    isTemplate: Boolean,
    refOffset: FieldRefOffset[Ctx],
    ast: Ast.Struct
) extends StructRef[Ctx] {
  def calcRefOffset(state: Compiler.State[Ctx], selector: Ast.Ident): FieldRefOffset[Ctx] = {
    val result = ast.calcFieldOffset(state, selector, isMutable)
    FieldRefOffset(refOffset.immFieldOffset.add(result._1), refOffset.mutFieldOffset.add(result._2))
  }

  def calcFieldOffset(state: Compiler.State[Ctx], selector: Ast.Ident): VarOffset[Ctx] = {
    val field     = ast.getField(selector)
    val newOffset = calcRefOffset(state, selector)
    if (isMutable && field.isMutable) newOffset.mutFieldOffset else newOffset.immFieldOffset
  }
}

final case class LocalStructRef[Ctx <: StatelessContext](
    ident: Ast.Ident,
    isLocal: Boolean,
    isMutable: Boolean,
    isTemplate: Boolean,
    refOffset: LocalRefOffset[Ctx],
    ast: Ast.Struct
) extends StructRef[Ctx] {
  def calcRefOffset(state: Compiler.State[Ctx], selector: Ast.Ident): LocalRefOffset[Ctx] = {
    LocalRefOffset(refOffset.offset.add(ast.calcLocalOffset(state, selector)))
  }

  def calcFieldOffset(state: Compiler.State[Ctx], selector: Ast.Ident): VarOffset[Ctx] = {
    calcRefOffset(state, selector).offset
  }
}

object StructRef {
  @inline def structVarName(baseName: String, field: String): String = s"_$baseName-$field"

  def from[Ctx <: StatelessContext](
      ident: Ast.Ident,
      isLocal: Boolean,
      isMutable: Boolean,
      isTemplate: Boolean,
      refOffset: VariablesRefOffset[Ctx],
      ast: Ast.Struct
  ): StructRef[Ctx] = {
    refOffset match {
      case offset: LocalRefOffset[Ctx] =>
        LocalStructRef(ident, isLocal, isMutable, isTemplate, offset, ast)
      case offset: FieldRefOffset[Ctx] =>
        FieldStructRef(ident, isLocal, isMutable, isTemplate, offset, ast)
    }
  }
}

sealed trait ArrayRef[Ctx <: StatelessContext] extends VariablesRef[Ctx] {
  type Selector = Ast.Expr[Ctx]
  def refOffset: VariablesRefOffset[Ctx]
  def tpe: Type.FixedSizeArray

  def calcRefOffset(state: Compiler.State[Ctx], index: Ast.Expr[Ctx]): VariablesRefOffset[Ctx]

  def genLoadCode(
      state: Compiler.State[Ctx],
      index: Ast.Expr[Ctx]
  ): Seq[Instr[Ctx]] = {
    tpe.baseType match {
      case _: Type.FixedSizeArray | _: Type.Struct =>
        subRef(state, index).genLoadCode(state)
      case _ =>
        val offset = refOffset match {
          case LocalRefOffset(offset) => offset
          case FieldRefOffset(immFieldOffset, mutFieldOffset) =>
            if (isMutable) mutFieldOffset else immFieldOffset
        }
        state.genLoadCode(
          ident,
          isTemplate,
          isLocal,
          isMutable,
          tpe.baseType,
          offset.add(calcOffset(state, index))
        )
    }
  }

  def genStoreCode(
      state: Compiler.State[Ctx],
      index: Ast.Expr[Ctx]
  ): Seq[Seq[Instr[Ctx]]] = {
    tpe.baseType match {
      case _: Type.FixedSizeArray | _: Type.Struct => subRef(state, index).genStoreCode(state)
      case _ =>
        val offset = refOffset.getStoreOffset
        Seq(state.genStoreCode(offset.add(calcOffset(state, index)), isLocal))
    }
  }

  def genLoadFieldsCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
    (0 until tpe.size).foldLeft(Seq.empty[Seq[Instr[Ctx]]]) { case (acc, index) =>
      val indexExpr = Ast.Const[Ctx](Val.U256(U256.unsafe(index)))
      tpe.baseType match {
        case _: Type.Struct | _: Type.FixedSizeArray =>
          acc ++ subRef(state, indexExpr).genLoadFieldsCode(state)
        case _ => acc :+ genLoadCode(state, indexExpr)
      }
    }
  }

  def subRef(state: Compiler.State[Ctx], index: Ast.Expr[Ctx]): VariablesRef[Ctx] = {
    val newRefOffset = calcRefOffset(state, index)
    tpe.baseType match {
      case baseType: Type.FixedSizeArray =>
        ArrayRef.from(ident, baseType, isLocal, isMutable, isTemplate, newRefOffset)
      case tpe: Type.Struct =>
        val struct = state.getStruct(tpe.id)
        StructRef.from(ident, isLocal, isMutable, isTemplate, newRefOffset, struct)
      case _ =>
        throw Compiler.Error(
          s"Expect array or struct type, have ${tpe.baseType}",
          index.sourceIndex
        )
    }
  }

  protected def calcOffset(
      state: Compiler.State[Ctx],
      offset: VarOffset[Ctx],
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

  protected def calcOffset(
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
}

final case class FieldArrayRef[Ctx <: StatelessContext](
    ident: Ast.Ident,
    tpe: Type.FixedSizeArray,
    isLocal: Boolean,
    isMutable: Boolean,
    isTemplate: Boolean,
    refOffset: FieldRefOffset[Ctx]
) extends ArrayRef[Ctx] {
  def calcRefOffset(state: Compiler.State[Ctx], index: Ast.Expr[Ctx]): FieldRefOffset[Ctx] = {
    val result       = state.flattenTypeMutability(tpe.baseType, isMutable)
    val mutFieldSize = result.count(identity)
    val immFieldSize = result.length - mutFieldSize
    FieldRefOffset(
      calcOffset(state, refOffset.immFieldOffset, index, immFieldSize),
      calcOffset(state, refOffset.mutFieldOffset, index, mutFieldSize)
    )
  }

  private def storeImmFieldArrayIndexVar(
      state: Compiler.State[Ctx],
      instrs: Seq[Instr[Ctx]]
  ): (Ast.Ident, Seq[Instr[Ctx]]) = {
    val ident = state.getImmFieldArrayVarIndex()
    (ident, instrs ++ state.genStoreCode(ident).flatten)
  }

  private def storeMutFieldArrayIndexVar(
      state: Compiler.State[Ctx],
      instrs: Seq[Instr[Ctx]]
  ): (Ast.Ident, Seq[Instr[Ctx]]) = {
    val ident = state.getMutFieldArrayVarIndex()
    (ident, instrs ++ state.genStoreCode(ident).flatten)
  }

  // scalastyle:off method.length
  def genLoadCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
    refOffset match {
      case FieldRefOffset(VariableVarOffset(immInstrs), VariableVarOffset(mutInstrs)) =>
        val (ident0, codes0) = storeImmFieldArrayIndexVar(state, immInstrs)
        val (ident1, codes1) = storeMutFieldArrayIndexVar(state, mutInstrs)
        val immVarIndex      = state.genLoadCode(ident0)
        val mutVarIndex      = state.genLoadCode(ident1)
        state
          .flattenTypeMutability(tpe, isMutable)
          .foldLeft((codes0 ++ codes1, 0, 0)) { case ((codes, immIndex, mutIndex), isMutable) =>
            if (isMutable) {
              val offsetCodes =
                mutVarIndex ++ Seq(ConstInstr.u256(Val.U256(U256.unsafe(mutIndex))), U256Add)
              val loadCodes = state.genLoadCode(
                ident,
                isTemplate,
                isLocal,
                isMutable,
                tpe.elementType,
                VariableVarOffset(offsetCodes)
              )
              (codes ++ loadCodes, immIndex, mutIndex + 1)
            } else {
              val offsetCodes =
                immVarIndex ++ Seq(ConstInstr.u256(Val.U256(U256.unsafe(immIndex))), U256Add)
              val loadCodes = state.genLoadCode(
                ident,
                isTemplate,
                isLocal,
                isMutable,
                tpe.elementType,
                VariableVarOffset(offsetCodes)
              )
              (codes ++ loadCodes, immIndex + 1, mutIndex)
            }
          }
          ._1
      case FieldRefOffset(ConstantVarOffset(immIndex), ConstantVarOffset(mutIndex)) =>
        state
          .flattenTypeMutability(tpe, isMutable)
          .foldLeft((Seq.empty[Instr[Ctx]], immIndex, mutIndex)) {
            case ((codes, immIndex, mutIndex), isMutable) =>
              if (isMutable) {
                val loadCodes = state.genLoadCode(
                  ident,
                  isTemplate,
                  isLocal,
                  isMutable,
                  tpe.elementType,
                  ConstantVarOffset(mutIndex)
                )
                (codes ++ loadCodes, immIndex, mutIndex + 1)
              } else {
                val loadCodes = state.genLoadCode(
                  ident,
                  isTemplate,
                  isLocal,
                  isMutable,
                  tpe.elementType,
                  ConstantVarOffset(immIndex)
                )
                (codes ++ loadCodes, immIndex + 1, mutIndex)
              }
          }
          ._1
      case _ => throw Compiler.Error("Invalid variable offset", ident.sourceIndex) // dead branch
    }
  }
  // scalastyle:on method.length

  def genStoreCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
    val flattenSize = state.flattenTypeLength(Seq(tpe))
    refOffset match {
      case FieldRefOffset(_, VariableVarOffset(mutInstrs)) =>
        val (ident, codes) = storeMutFieldArrayIndexVar(state, mutInstrs)
        val mutVarIndex    = state.genLoadCode(ident)
        val storeCodes = (0 until flattenSize) map { idx =>
          val calcOffsetCode = mutVarIndex ++ Seq(
            ConstInstr.u256(Val.U256(U256.unsafe(idx))),
            U256Add
          )
          state.genStoreCode(VariableVarOffset(calcOffsetCode), isLocal)
        }
        storeCodes :+ codes
      case FieldRefOffset(_, ConstantVarOffset(value)) =>
        (0 until flattenSize) map { idx =>
          state.genStoreCode(ConstantVarOffset(value + idx), isLocal)
        }
      case _ => throw Compiler.Error("Invalid variable offset", ident.sourceIndex) // dead branch
    }
  }
}

final case class LocalArrayRef[Ctx <: StatelessContext](
    ident: Ast.Ident,
    tpe: Type.FixedSizeArray,
    isLocal: Boolean,
    isMutable: Boolean,
    isTemplate: Boolean,
    refOffset: LocalRefOffset[Ctx]
) extends ArrayRef[Ctx] {
  def calcRefOffset(state: Compiler.State[Ctx], index: Ast.Expr[Ctx]): LocalRefOffset[Ctx] = {
    LocalRefOffset(
      calcOffset(state, refOffset.offset, index, state.flattenTypeLength(Seq(tpe.baseType)))
    )
  }

  private def storeLocalArrayIndexVar(
      state: Compiler.State[Ctx],
      instrs: Seq[Instr[Ctx]]
  ): (Ast.Ident, Seq[Instr[Ctx]]) = {
    val ident = state.getLocalArrayVarIndex()
    (ident, instrs ++ state.genStoreCode(ident).flatten)
  }

  def genLoadCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
    refOffset match {
      case LocalRefOffset(VariableVarOffset(instrs)) =>
        val flattenSize    = state.flattenTypeLength(Seq(tpe))
        val (ident, codes) = storeLocalArrayIndexVar(state, instrs)
        val loadCodes = (0 until flattenSize).flatMap { idx =>
          val calcOffsetCode = state.genLoadCode(ident) ++ Seq(
            ConstInstr.u256(Val.U256(U256.unsafe(idx))),
            U256Add
          )
          state.genLoadCode(
            ident,
            isTemplate,
            isLocal,
            isMutable,
            tpe.elementType,
            VariableVarOffset(calcOffsetCode)
          )
        }
        codes ++ loadCodes
      case LocalRefOffset(ConstantVarOffset(value)) =>
        val flattenSize = state.flattenTypeLength(Seq(tpe))
        (0 until flattenSize).flatMap(idx =>
          state.genLoadCode(
            ident,
            isTemplate,
            isLocal,
            isMutable,
            tpe.elementType,
            ConstantVarOffset(value + idx)
          )
        )
    }
  }

  def genStoreCode(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
    val flattenSize = state.flattenTypeLength(Seq(tpe))
    refOffset match {
      case LocalRefOffset(VariableVarOffset(instrs)) =>
        val (ident, codes) = storeLocalArrayIndexVar(state, instrs)
        val mutVarIndex    = state.genLoadCode(ident)
        val storeCodes = (0 until flattenSize) map { idx =>
          val calcOffsetCode = mutVarIndex ++ Seq(
            ConstInstr.u256(Val.U256(U256.unsafe(idx))),
            U256Add
          )
          state.genStoreCode(VariableVarOffset(calcOffsetCode), isLocal)
        }
        storeCodes :+ codes
      case LocalRefOffset(ConstantVarOffset(value)) =>
        (0 until flattenSize) map { idx =>
          state.genStoreCode(ConstantVarOffset(value + idx), isLocal)
        }
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

  def from[Ctx <: StatelessContext](
      ident: Ast.Ident,
      tpe: Type.FixedSizeArray,
      isLocal: Boolean,
      isMutable: Boolean,
      isTemplate: Boolean,
      refOffset: VariablesRefOffset[Ctx]
  ): ArrayRef[Ctx] = {
    refOffset match {
      case offset: LocalRefOffset[Ctx] =>
        LocalArrayRef(ident, tpe, isLocal, isMutable, isTemplate, offset)
      case offset: FieldRefOffset[Ctx] =>
        FieldArrayRef(ident, tpe, isLocal, isMutable, isTemplate, offset)
    }
  }
}
