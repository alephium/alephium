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

sealed trait Type extends Ast.Positioned {
  def toVal: Val.Type

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def signature: String = toVal.toString

  def isPrimitive: Boolean = this match {
    case Type.Bool | Type.I256 | Type.U256 | Type.ByteVec | Type.Address => true
    case _                                                               => false
  }

  def isArrayType: Boolean = this match {
    case _: Type.FixedSizeArray => true
    case _                      => false
  }

  def isStructType: Boolean = this match {
    case _: Type.Struct => true
    case _              => false
  }

  def isTupleType: Boolean = this match {
    case _: Type.Tuple => true
    case _             => false
  }

  def isMapType: Boolean = this match {
    case _: Type.Map => true
    case _           => false
  }
}

object Type {
  val primitives: AVector[Type] = AVector[Type](Bool, I256, U256, ByteVec, Address)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def fromVal(value: Val): Type = {
    value match {
      case _: Val.Bool    => Bool
      case _: Val.I256    => I256
      case _: Val.U256    => U256
      case _: Val.ByteVec => ByteVec
      case _: Val.Address => Address
    }
  }

  case object Bool    extends Type { def toVal: Val.Type = Val.Bool    }
  case object I256    extends Type { def toVal: Val.Type = Val.I256    }
  case object U256    extends Type { def toVal: Val.Type = Val.U256    }
  case object ByteVec extends Type { def toVal: Val.Type = Val.ByteVec }
  case object Address extends Type { def toVal: Val.Type = Val.Address }
  final case class FixedSizeArray(
      baseType: Type,
      size: Either[Int, Ast.Expr[_]]
  ) extends Type {
    private[ralph] var sizeCalculated: Option[Int] = None

    def getArraySize: Int = size match {
      case Left(size) => size
      case Right(expr) =>
        sizeCalculated.getOrElse(throw Compiler.Error("Unresolved array size", expr.sourceIndex))
    }

    override def toVal: Val.Type = Val.FixedSizeArray(baseType.toVal, getArraySize)

    @scala.annotation.tailrec
    def elementType: Type = baseType match {
      case array: FixedSizeArray => array.elementType
      case tpe                   => tpe
    }

    override def signature: String = s"[${baseType.signature};$getArraySize]"
    override def toString: String  = s"FixedSizeArray($baseType,$getArraySize)"
  }

  final case class NamedType(id: Ast.TypeId) extends Type {
    def toVal: Val.Type            = ???
    override def signature: String = id.name
  }

  final case class Struct(id: Ast.TypeId) extends Type {
    def toVal: Val.Type           = Val.Struct(id.name)
    override def toString: String = id.name
  }

  final case class Map(key: Type, value: Type) extends Type {
    def toVal: Val.Type            = Val.Map(key.toVal, value.toVal)
    override def toString: String  = s"Map[$key,$value]"
    override def signature: String = s"Map[${key.signature},${value.signature}]"
  }

  final case class Tuple(types: Seq[Type]) extends Type {
    def toVal: Val.Type            = ???
    override def toString: String  = s"Tuple$signature"
    override def signature: String = s"(${types.map(_.signature).mkString(",")})"
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
