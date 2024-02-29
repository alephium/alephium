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

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def update(typer: Type.NamedType => Type): Type = {
    this match {
      case t: Type.NamedType => typer(t)
      case Type.FixedSizeArray(t, size) =>
        Type.FixedSizeArray(t.update(typer), size)
      case _ => this
    }
  }
}

object Type {
  def flattenTypeLength(types: Seq[Type]): Int = {
    types.foldLeft(0) { case (acc, tpe) =>
      tpe match {
        case t: Type.FixedSizeArray => acc + t.flattenSize()
        case _                      => acc + 1
      }
    }
  }

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
      case Val.Struct(name, size)             => Struct(Ast.TypeId(name), size)
    }
  }

  case object Bool    extends Type { def toVal: Val.Type = Val.Bool    }
  case object I256    extends Type { def toVal: Val.Type = Val.I256    }
  case object U256    extends Type { def toVal: Val.Type = Val.U256    }
  case object ByteVec extends Type { def toVal: Val.Type = Val.ByteVec }
  case object Address extends Type { def toVal: Val.Type = Val.Address }
  final case class FixedSizeArray(baseType: Type, size: Int) extends Type {
    override def toVal: Val.Type = Val.FixedSizeArray(baseType.toVal, size)

    @scala.annotation.tailrec
    def elementType: Type = baseType match {
      case array: FixedSizeArray => array.elementType
      case tpe                   => tpe
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def flattenSize(): Int = baseType match {
      case baseType: FixedSizeArray =>
        baseType.flattenSize() * size
      case baseType: Struct =>
        baseType.flattenSize * size
      case _ => size
    }
  }

  final case class NamedType(id: Ast.TypeId) extends Type {
    def toVal: Val.Type           = ???
    override def toString: String = id.name
  }

  final case class Struct(id: Ast.TypeId, flattenSize: Int) extends Type {
    def toVal: Val.Type           = Val.Struct(id.name, flattenSize)
    override def toString: String = id.name
  }

  final case class Contract(id: Ast.TypeId) extends Type {
    def toVal: Val.Type           = Val.ByteVec
    override def toString: String = id.name
  }

  // The naming is more specific than Bottom or Nothing
  case object Panic extends Type {
    def toVal: Val.Type = throw new RuntimeException("Unable to convert Bottom type to Val type")
  }
}
