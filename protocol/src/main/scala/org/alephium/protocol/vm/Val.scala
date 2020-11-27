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

package org.alephium.protocol.vm

import scala.collection.mutable.ArraySeq

import akka.util.ByteString

import org.alephium.serde.{_deserialize => decode, serialize => encode, _}
import org.alephium.util
import org.alephium.util._

sealed trait Val extends Any {
  def tpe: Val.Type
}

// scalastyle:off number.of.methods
object Val {
  implicit val serde: Serde[Val] = new Serde[Val] {
    override def serialize(input: Val): ByteString = {
      val content = input match {
        case Bool(v)    => encode(v)
        case Byte(v)    => encode(v)
        case I256(v)    => encode(v)
        case U256(v)    => encode(v)
        case BoolVec(a) => encode(a)
        case ByteVec(a) => encode(a)
        case I256Vec(a) => encode(a)
        case U256Vec(a) => encode(a)
        case Address(a) => encode(a)
      }
      ByteString(input.tpe.id) ++ content
    }

    override def _deserialize(input: ByteString): SerdeResult[Staging[Val]] = {
      byteSerde._deserialize(input).flatMap {
        case Staging(code, content) if code >= 0 && code < Type.types.length =>
          _deserialize(Type.types(code.toInt), content)
        case Staging(code, _) => Left(SerdeError.wrongFormat(s"Invalid type id: $code"))
      }
    }

    private def _deserialize(tpe: Type, content: ByteString): SerdeResult[Staging[Val]] =
      tpe match {
        case Bool    => decode[Boolean](content).map(_.mapValue(Bool(_)))
        case Byte    => decode[scala.Byte](content).map(_.mapValue(Byte(_)))
        case I256    => decode[util.I256](content).map(_.mapValue(I256(_)))
        case U256    => decode[util.U256](content).map(_.mapValue(U256(_)))
        case BoolVec => decode[ArraySeq[Boolean]](content).map(_.mapValue(BoolVec(_)))
        case ByteVec => decode[ArraySeq[scala.Byte]](content).map(_.mapValue(ByteVec(_)))
        case I256Vec => decode[ArraySeq[util.I256]](content).map(_.mapValue(I256Vec(_)))
        case U256Vec => decode[ArraySeq[util.U256]](content).map(_.mapValue(U256Vec(_)))
        case Address => decode[LockupScript](content).map(_.mapValue(Address(_)))
      }
  }

  sealed trait Type {
    def id: scala.Byte
    def default: Val
    def isNumeric: Boolean
  }
  object Type {
    implicit val serde: Serde[Type] =
      byteSerde.xfmap(byte => {
        types.get(Bytes.toPosInt(byte)).toRight(SerdeError.validation(s"Invalid Val Type"))
      }, _.id)

    val types: AVector[Type] = AVector[Type](Bool, Byte, I256, U256) ++
      AVector[Type](BoolVec, ByteVec, I256Vec, U256Vec, Address)

    def getId(tpe: Type): scala.Byte = types.indexWhere(_ == tpe).toByte
  }

  // TODO: optimize using value class
  final case class Bool(v: Boolean) extends Val {
    def tpe: Val.Type                  = Bool
    def not: Val.Bool                  = Bool(!v)
    def and(other: Val.Bool): Val.Bool = Val.Bool(v && other.v)
    def or(other: Val.Bool): Val.Bool  = Val.Bool(v || other.v)
  }
  // Byte are unsigned ints from [0, 0xFF]
  final case class Byte(v: scala.Byte) extends Val { def tpe: Val.Type = Byte }
  final case class I256(v: util.I256)  extends Val { def tpe: Val.Type = I256 }
  final case class U256(v: util.U256)  extends Val { def tpe: Val.Type = U256 }

  final case class BoolVec(a: ArraySeq[Boolean]) extends AnyVal with Val {
    def tpe: Val.Type = BoolVec
  }
  final case class ByteVec(a: ArraySeq[scala.Byte]) extends AnyVal with Val {
    def tpe: Val.Type = ByteVec
  }
  final case class I256Vec(a: ArraySeq[util.I256]) extends AnyVal with Val {
    def tpe: Val.Type = I256Vec
  }
  final case class U256Vec(a: ArraySeq[util.U256]) extends AnyVal with Val {
    def tpe: Val.Type = U256Vec
  }

  final case class Address(lockupScript: LockupScript) extends AnyVal with Val {
    def tpe: Val.Type = Address
  }

  object Bool extends Type {
    implicit val serde: Serde[Bool]  = boolSerde.xmap(Bool(_), _.v)
    override lazy val id: scala.Byte = Type.getId(this)
    override def default: Bool       = Bool(false)
    override def isNumeric: Boolean  = false

    override def toString: String = "Bool"
  }
  object Byte extends Type {
    implicit val serde: Serde[Byte]  = byteSerde.xmap(Byte(_), _.v)
    override lazy val id: scala.Byte = Type.getId(this)
    override def default: Byte       = Byte(0.toByte)
    override def isNumeric: Boolean  = false

    override def toString: String = "Byte"
  }
  object I256 extends Type {
    implicit val serde: Serde[I256]  = i256Serde.xmap(I256(_), _.v)
    override lazy val id: scala.Byte = Type.getId(this)
    override def default: I256       = I256(util.I256.Zero)
    override def isNumeric: Boolean  = true

    override def toString: String = "I256"
  }
  object U256 extends Type {
    implicit val serde: Serde[U256]  = u256Serde.xmap(U256(_), _.v)
    override lazy val id: scala.Byte = Type.getId(this)
    override def default: U256       = U256(util.U256.Zero)
    override def isNumeric: Boolean  = true

    override def toString: String = "U256"
  }

  object BoolVec extends Type {
    implicit val serde: Serde[BoolVec] = boolArraySeqSerde.xmap(BoolVec(_), _.a)
    override lazy val id: scala.Byte   = Type.getId(this)
    override def default: BoolVec      = BoolVec(ArraySeq.empty)
    override def isNumeric: Boolean    = false

    override def toString: String = "BoolVec"
  }
  object ByteVec extends Type {
    implicit val serde: Serde[ByteVec] = byteArraySeqSerde.xmap(ByteVec(_), _.a)
    override lazy val id: scala.Byte   = Type.getId(this)
    override def default: ByteVec      = ByteVec(ArraySeq.empty)
    override def isNumeric: Boolean    = false

    override def toString: String = "ByteVec"

    def from(bytes: RandomBytes): ByteVec = ByteVec(ArraySeq.from(bytes.bytes))
  }
  object I256Vec extends Type {
    implicit val serde: Serde[I256Vec] = i256ArraySeqSerde.xmap(I256Vec(_), _.a)
    override lazy val id: scala.Byte   = Type.getId(this)
    override def default: I256Vec      = I256Vec(ArraySeq.empty)
    override def isNumeric: Boolean    = false

    override def toString: String = "I256Vec"
  }
  object U256Vec extends Type {
    implicit val serde: Serde[U256Vec] = u256ArraySeqSerde.xmap(U256Vec(_), _.a)
    override lazy val id: scala.Byte   = Type.getId(this)
    override def default: U256Vec      = U256Vec(ArraySeq.empty)
    override def isNumeric: Boolean    = false

    override def toString: String = "U256Vec"
  }
  object Address extends Type {
    implicit val serde: Serde[Address] = serdeImpl[LockupScript].xmap(Address(_), _.lockupScript)
    override lazy val id: scala.Byte   = Type.getId(this)
    override def default: Address      = Address(LockupScript.vmDefault)
    override def isNumeric: Boolean    = false

    override def toString: String = "Address"
  }

  val True: Bool  = Bool(true)
  val False: Bool = Bool(false)
}
// scalastyle:on number.of.methods
