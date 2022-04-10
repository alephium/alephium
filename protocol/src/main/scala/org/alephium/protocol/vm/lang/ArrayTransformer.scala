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

import org.alephium.protocol.vm.StatelessContext
import org.alephium.protocol.vm.lang.Ast.Ident

object ArrayTransformer {
  @inline def arrayVarName(baseName: String, idx: Int): String = s"_$baseName-$idx"

  def init[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      tpe: Type.FixedSizeArray,
      baseName: String,
      isMutable: Boolean,
      varInfoBuild: (Type, Boolean, Byte) => Compiler.VarInfo
  ): ArrayRef = {
    val vars = initArrayVars(state, tpe, baseName, isMutable, varInfoBuild)
    val ref  = ArrayRef(tpe, vars)
    state.addArrayRef(Ident(baseName), isMutable, ref)
    ref
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def initArrayVars[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      tpe: Type.FixedSizeArray,
      baseName: String,
      isMutable: Boolean,
      varInfoBuild: (Type, Boolean, Byte) => Compiler.VarInfo
  ): Seq[Ast.Ident] = {
    tpe.baseType match {
      case baseType: Type.FixedSizeArray =>
        (0 until tpe.size).flatMap { idx =>
          val newBaseName = arrayVarName(baseName, idx)
          initArrayVars(state, baseType, newBaseName, isMutable, varInfoBuild)
        }
      case baseType =>
        (0 until tpe.size).map { idx =>
          val ident = Ast.Ident(arrayVarName(baseName, idx))
          state.addVariable(ident, baseType, isMutable, varInfoBuild)
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

  final case class ArrayRef(tpe: Type.FixedSizeArray, vars: Seq[Ast.Ident]) {
    def isMultiDim(): Boolean = tpe.size != tpe.flattenSize()

    def subArray(index: Int): ArrayRef = {
      tpe.baseType match {
        case baseType: Type.FixedSizeArray =>
          checkArrayIndex(index, tpe.size)
          val length = baseType.flattenSize()
          val offset = index * length
          ArrayRef(baseType, vars.slice(offset, offset + length))
        case _ =>
          throw Compiler.Error(s"Expect multi-dimension array type, have $tpe")
      }
    }

    @scala.annotation.tailrec
    def subArray(indexes: Seq[Int]): ArrayRef = {
      if (indexes.isEmpty) {
        this
      } else {
        subArray(indexes(0)).subArray(indexes.drop(1))
      }
    }

    @scala.annotation.tailrec
    def getVariable(indexes: Seq[Int]): Ast.Ident = {
      assume(indexes.nonEmpty)
      if (indexes.size == 1) {
        getVariable(indexes(0))
      } else {
        subArray(indexes(0)).getVariable(indexes.drop(1))
      }
    }

    def getVariable(index: Int): Ast.Ident = {
      checkArrayIndex(index, tpe.size)
      vars(index)
    }
  }
}
