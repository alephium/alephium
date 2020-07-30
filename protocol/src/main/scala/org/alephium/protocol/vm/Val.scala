package org.alephium.protocol.vm

import scala.collection.mutable.ArrayBuffer

import akka.util.ByteString

import org.alephium.crypto
import org.alephium.protocol.Hash
import org.alephium.serde.{_deserialize => decode, serialize => encode, _}
import org.alephium.util
import org.alephium.util._

sealed trait Val extends Any {
  def tpe: Val.Type
}

// scalastyle:off number.of.methods
object Val {
  implicit val byte32Serde: Serde[ArrayBuffer[crypto.Byte32]] = arrayBufferSerde[crypto.Byte32]

  implicit val serde: Serde[Val] = new Serde[Val] {
    override def serialize(input: Val): ByteString = {
      val content = input match {
        case Bool(v)      => encode(v)
        case Byte(v)      => encode(v)
        case I64(v)       => encode(v)
        case U64(v)       => encode(v)
        case I256(v)      => encode(v)
        case U256(v)      => encode(v)
        case Byte32(v)    => encode(v)
        case BoolVec(a)   => encode(a)
        case ByteVec(a)   => encode(a)
        case I64Vec(a)    => encode(a)
        case U64Vec(a)    => encode(a)
        case I256Vec(a)   => encode(a)
        case U256Vec(a)   => encode(a)
        case Byte32Vec(a) => encode(a)
      }
      ByteString(input.tpe.id) ++ content
    }

    override def _deserialize(input: ByteString): SerdeResult[(Val, ByteString)] = {
      byteSerde._deserialize(input).flatMap {
        case (code, content) if code >= 0 && code < Type.types.length =>
          _deserialize(Type.types(code.toInt), content)
        case (code, _) => Left(SerdeError.wrongFormat(s"Invalid type id: $code"))
      }
    }

    private def _deserialize(tpe: Type, content: ByteString): SerdeResult[(Val, ByteString)] =
      tpe match {
        case Bool    => decode[Boolean](content).map(t                 => Bool(t._1)    -> t._2)
        case Byte    => decode[scala.Byte](content).map(t              => Byte(t._1)    -> t._2)
        case I64     => decode[util.I64](content).map(t                => I64(t._1)     -> t._2)
        case U64     => decode[util.U64](content).map(t                => U64(t._1)     -> t._2)
        case I256    => decode[util.I256](content).map(t               => I256(t._1)    -> t._2)
        case U256    => decode[util.U256](content).map(t               => U256(t._1)    -> t._2)
        case Byte32  => decode[crypto.Byte32](content).map(t           => Byte32(t._1)  -> t._2)
        case BoolVec => decode[ArrayBuffer[Boolean]](content).map(t    => BoolVec(t._1) -> t._2)
        case ByteVec => decode[ArrayBuffer[scala.Byte]](content).map(t => ByteVec(t._1) -> t._2)
        case I64Vec  => decode[ArrayBuffer[util.I64]](content).map(t   => I64Vec(t._1)  -> t._2)
        case U64Vec  => decode[ArrayBuffer[util.U64]](content).map(t   => U64Vec(t._1)  -> t._2)
        case I256Vec => decode[ArrayBuffer[util.I256]](content).map(t  => I256Vec(t._1) -> t._2)
        case U256Vec => decode[ArrayBuffer[util.U256]](content).map(t  => U256Vec(t._1) -> t._2)
        case Byte32Vec =>
          decode[ArrayBuffer[crypto.Byte32]](content).map(t => Byte32Vec(t._1) -> t._2)
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

    val types: AVector[Type] = AVector[Type](Bool, Byte, I64, U64, I256, U256, Byte32) ++
      AVector[Type](BoolVec, ByteVec, I64Vec, U64Vec, I256Vec, U256Vec, Byte32Vec)
  }

  // TODO: optimize using value class
  final case class Bool(v: Boolean) extends Val {
    def tpe: Val.Type                  = Bool
    def not: Val.Bool                  = Bool(!v)
    def and(other: Val.Bool): Val.Bool = Val.Bool(v && other.v)
    def or(other: Val.Bool): Val.Bool  = Val.Bool(v || other.v)
  }
  // Byte are unsigned ints from [0, 0xFF]
  final case class Byte(v: scala.Byte)      extends Val { def tpe: Val.Type = Byte }
  final case class I64(v: util.I64)         extends Val { def tpe: Val.Type = I64 }
  final case class U64(v: util.U64)         extends Val { def tpe: Val.Type = U64 }
  final case class I256(v: util.I256)       extends Val { def tpe: Val.Type = I256 }
  final case class U256(v: util.U256)       extends Val { def tpe: Val.Type = U256 }
  final case class Byte32(v: crypto.Byte32) extends Val { def tpe: Val.Type = Byte32 }

  final case class BoolVec(a: ArrayBuffer[Boolean]) extends AnyVal with Val {
    def tpe: Val.Type = BoolVec
  }
  final case class ByteVec(a: ArrayBuffer[scala.Byte]) extends AnyVal with Val {
    def tpe: Val.Type = ByteVec
  }
  final case class I64Vec(a: ArrayBuffer[util.I64]) extends AnyVal with Val {
    def tpe: Val.Type = I64Vec
  }
  final case class U64Vec(a: ArrayBuffer[util.U64]) extends AnyVal with Val {
    def tpe: Val.Type = U64Vec
  }
  final case class I256Vec(a: ArrayBuffer[util.I256]) extends AnyVal with Val {
    def tpe: Val.Type = I256Vec
  }
  final case class U256Vec(a: ArrayBuffer[util.U256]) extends AnyVal with Val {
    def tpe: Val.Type = U256Vec
  }
  final case class Byte32Vec(a: ArrayBuffer[crypto.Byte32]) extends AnyVal with Val {
    def tpe: Val.Type = Byte32Vec
  }

  object Bool extends Type {
    override val id: scala.Byte     = 0.toByte
    override def default: Bool      = Bool(false)
    override def isNumeric: Boolean = false

    override def toString: String = "Bool"
  }
  object Byte extends Type {
    override val id: scala.Byte     = 1.toByte
    override def default: Byte      = Byte(0.toByte)
    override def isNumeric: Boolean = false

    override def toString: String = "Byte"
  }
  object I64 extends Type {
    implicit val serde: Serde[I64]  = i64Serde.xmap(I64(_), _.v)
    override val id: scala.Byte     = 2.toByte
    override def default: I64       = I64(util.I64.Zero)
    override def isNumeric: Boolean = true

    override def toString: String = "I64"
  }
  object U64 extends Type {
    implicit val serde: Serde[U64]  = u64Serde.xmap(U64(_), _.v)
    override val id: scala.Byte     = 3.toByte
    override def default: U64       = U64(util.U64.Zero)
    override def isNumeric: Boolean = true

    override def toString: String = "U64"
  }
  object I256 extends Type {
    implicit val serde: Serde[I256] = i256Serde.xmap(I256(_), _.v)
    override val id: scala.Byte     = 4.toByte
    override def default: I256      = I256(util.I256.Zero)
    override def isNumeric: Boolean = true

    override def toString: String = "I256"
  }
  object U256 extends Type {
    implicit val serde: Serde[U256] = u256Serde.xmap(U256(_), _.v)
    override val id: scala.Byte     = 5.toByte
    override def default: U256      = U256(util.U256.Zero)
    override def isNumeric: Boolean = true

    override def toString: String = "U256"
  }
  object Byte32 extends Type {
    implicit val serde: Serde[Byte32] = serdeImpl[crypto.Byte32].xmap(Byte32(_), _.v)
    override val id: scala.Byte       = 6.toByte
    override def default: Byte32      = Byte32(crypto.Byte32.zero)
    override def isNumeric: Boolean   = false

    override def toString: String = "Byte32"

    def from(hash: Hash): Byte32 = Byte32(crypto.Byte32.unsafe(hash.bytes))
  }

  object BoolVec extends Type {
    override val id: scala.Byte     = 7.toByte
    override def default: BoolVec   = BoolVec(ArrayBuffer.empty)
    override def isNumeric: Boolean = false

    override def toString: String = "BoolVec"
  }
  object ByteVec extends Type {
    override val id: scala.Byte     = 8.toByte
    override def default: ByteVec   = ByteVec(ArrayBuffer.empty)
    override def isNumeric: Boolean = false

    override def toString: String = "ByteVec"
  }
  object I64Vec extends Type {
    override val id: scala.Byte     = 9.toByte
    override def default: I64Vec    = I64Vec(ArrayBuffer.empty)
    override def isNumeric: Boolean = false

    override def toString: String = "I64Vec"
  }
  object U64Vec extends Type {
    override val id: scala.Byte     = 10.toByte
    override def default: U64Vec    = U64Vec(ArrayBuffer.empty)
    override def isNumeric: Boolean = false

    override def toString: String = "U64Vec"
  }
  object I256Vec extends Type {
    override val id: scala.Byte     = 11.toByte
    override def default: I256Vec   = I256Vec(ArrayBuffer.empty)
    override def isNumeric: Boolean = false

    override def toString: String = "I256Vec"
  }
  object U256Vec extends Type {
    override val id: scala.Byte     = 12.toByte
    override def default: U256Vec   = U256Vec(ArrayBuffer.empty)
    override def isNumeric: Boolean = false

    override def toString: String = "U256Vec"
  }
  object Byte32Vec extends Type {
    override val id: scala.Byte     = 13.toByte
    override def default: Byte32Vec = Byte32Vec(ArrayBuffer.empty)
    override def isNumeric: Boolean = false

    override def toString: String = "Byte32Vec"
  }

  val True: Bool  = Bool(true)
  val False: Bool = Bool(false)
}
// scalastyle:on number.of.methods
