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

import akka.util.ByteString

import org.alephium.serde.{_deserialize => decode, serialize => encode, _}
import org.alephium.util
import org.alephium.util._

sealed trait Val extends Any {
  def tpe: Val.Type

  def toByteVec(): Val.ByteVec

  def estimateByteSize(): Int

  def toConstInstr: Instr[StatelessContext]
}

// scalastyle:off number.of.methods
object Val {
  implicit val serde: Serde[Val] = new Serde[Val] {
    override def serialize(input: Val): ByteString = {
      val content = input match {
        case Bool(v)    => encode(v)
        case I256(v)    => encode(v)
        case U256(v)    => encode(v)
        case ByteVec(a) => encode(a)
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
        case Bool              => decode[Boolean](content).map(_.mapValue(Bool(_)))
        case I256              => decode[util.I256](content).map(_.mapValue(I256(_)))
        case U256              => decode[util.U256](content).map(_.mapValue(U256(_)))
        case ByteVec           => decode[ByteString](content).map(_.mapValue(ByteVec(_)))
        case Address           => decode[LockupScript](content).map(_.mapValue(Address(_)))
        case _: FixedSizeArray => Left(SerdeError.Other("Unexpected type"))
      }
  }

  sealed trait Type {
    def id: scala.Byte
    def default: Val
    def isNumeric: Boolean
  }
  object Type {
    implicit val serde: Serde[Type] =
      byteSerde.xfmap(
        byte => {
          types.get(Bytes.toPosInt(byte)).toRight(SerdeError.validation(s"Invalid Val Type"))
        },
        _.id
      )

    val types: AVector[Type] = AVector[Type](Bool, I256, U256, ByteVec, Address)

    def getId(tpe: Type): scala.Byte = types.indexWhere(_ == tpe).toByte
  }

  // TODO: optimize using value class
  final case class Bool(v: Boolean) extends AnyVal with Val {
    def tpe: Val.Type                  = Bool
    def not: Val.Bool                  = Bool(!v)
    def and(other: Val.Bool): Val.Bool = Val.Bool(v && other.v)
    def or(other: Val.Bool): Val.Bool  = Val.Bool(v || other.v)

    override def toByteVec(): ByteVec = ByteVec(encode(v))

    override def estimateByteSize(): Int = 1

    override def toConstInstr: Instr[StatelessContext] = if (v) ConstTrue else ConstFalse
  }
  final case class I256(v: util.I256) extends Val {
    def tpe: Val.Type = I256

    override def toByteVec(): ByteVec = ByteVec(encode(v))

    override def estimateByteSize(): Int = 32

    override def toConstInstr: Instr[StatelessContext] = ConstInstr.i256(this)
  }
  final case class U256(v: util.U256) extends Val {
    def tpe: Val.Type = U256

    override def toByteVec(): ByteVec = ByteVec(encode(v))

    override def estimateByteSize(): Int = 32

    override def toConstInstr: Instr[StatelessContext] = ConstInstr.u256(this)
  }

  final case class ByteVec(bytes: ByteString) extends AnyVal with Val {
    def tpe: Val.Type = ByteVec

    override def toByteVec(): ByteVec = this

    override def estimateByteSize(): Int = bytes.length

    override def toConstInstr: Instr[StatelessContext] = BytesConst(this)
  }
  final case class Address(lockupScript: LockupScript) extends AnyVal with Val {
    def tpe: Val.Type = Address

    override def toByteVec(): ByteVec = ByteVec(encode(lockupScript))
    def toBase58: String              = Base58.encode(encode(lockupScript))

    override def estimateByteSize(): Int = lockupScript match {
      case LockupScript.P2MPKH(mpkh, _) => mpkh.length * 32
      case _                            => 32
    }

    override def toConstInstr: Instr[StatelessContext] = AddressConst(this)
  }

  object Bool extends Type {
    implicit val serde: Serde[Bool]  = boolSerde.xmap(Bool(_), _.v)
    override lazy val id: scala.Byte = Type.getId(this)
    override def default: Bool       = Bool(false)
    override def isNumeric: Boolean  = false

    override def toString: String = "Bool"
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

    def unsafe(v: Int): U256 = {
      U256(util.U256.unsafe(v))
    }
  }

  object ByteVec extends Type {
    implicit val serde: Serde[ByteVec] = bytestringSerde.xmap(ByteVec(_), _.bytes)
    override lazy val id: scala.Byte   = Type.getId(this)
    override def default: ByteVec      = ByteVec(ByteString.empty)
    override def isNumeric: Boolean    = false

    override def toString: String = "ByteVec"

    def from(bytes: RandomBytes): ByteVec = ByteVec(bytes.bytes)
  }
  object Address extends Type {
    implicit val serde: Serde[Address] = serdeImpl[LockupScript].xmap(Address(_), _.lockupScript)
    override lazy val id: scala.Byte   = Type.getId(this)
    override def default: Address      = Address(LockupScript.vmDefault)
    override def isNumeric: Boolean    = false

    override def toString: String = "Address"
  }
  final case class FixedSizeArray(baseType: Type, size: Int) extends Type {
    override def id: scala.Byte     = throw new RuntimeException("FixedArray has no type id")
    override def default: Val       = throw new RuntimeException("FixedArray has no default value")
    override def isNumeric: Boolean = false
    override def toString: String   = s"[$baseType;$size]"
  }

  val True: Bool  = Bool(true)
  val False: Bool = Bool(false)
}
// scalastyle:on number.of.methods
