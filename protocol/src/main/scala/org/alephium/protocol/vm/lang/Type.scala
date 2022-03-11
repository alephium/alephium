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

import org.alephium.protocol.vm.Val
import org.alephium.util.AVector

sealed trait Type {
  def toVal: Val.Type

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def signature: String = toVal.toString

  def isArrayType: Boolean = this match {
    case _: Type.FixedSizeArray => true
    case _                      => false
  }
}

object Type {
  val primitives: AVector[Type] = AVector[Type](Bool, I256, U256, ByteVec, Address)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def fromVal(tpe: Val.Type): Type = {
    tpe match {
      case Val.Bool                           => Bool
      case Val.I256                           => I256
      case Val.U256                           => U256
      case Val.ByteVec                        => ByteVec
      case Val.Address                        => Address
      case Val.FixedSizeArray(baseType, size) => FixedSizeArray(fromVal(baseType), size)
    }
  }

  case object Bool    extends Type { def toVal: Val.Type = Val.Bool    }
  case object I256    extends Type { def toVal: Val.Type = Val.I256    }
  case object U256    extends Type { def toVal: Val.Type = Val.U256    }
  case object ByteVec extends Type { def toVal: Val.Type = Val.ByteVec }
  case object Address extends Type { def toVal: Val.Type = Val.Address }
  final case class FixedSizeArray(baseType: Type, size: Int) extends Type {
    override def toVal: Val.Type = Val.FixedSizeArray(baseType.toVal, size)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def flattenSize(): Int = baseType match {
      case baseType: FixedSizeArray =>
        baseType.flattenSize() * size
      case _ => size
    }
  }

  sealed trait Contract extends Type {
    def id: Ast.TypeId
    def toVal: Val.Type = Val.ByteVec

    override def hashCode(): Int = id.hashCode()

    override def equals(obj: Any): Boolean =
      obj match {
        case that: Contract => this.id == that.id
        case _              => false
      }
  }
  object Contract {
    def local(id: Ast.TypeId, variable: Ast.Ident): LocalVar   = new LocalVar(id, variable)
    def global(id: Ast.TypeId, variable: Ast.Ident): GlobalVar = new GlobalVar(id, variable)
    def stack(id: Ast.TypeId): Stack                           = new Stack(id)

    final class LocalVar(val id: Ast.TypeId, val variable: Ast.Ident)  extends Contract
    final class GlobalVar(val id: Ast.TypeId, val variable: Ast.Ident) extends Contract
    final class Stack(val id: Ast.TypeId)                              extends Contract
  }
}
