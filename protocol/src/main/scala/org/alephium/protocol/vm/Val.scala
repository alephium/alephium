package org.alephium.protocol.vm

import org.alephium.serde._
import org.alephium.util
import org.alephium.util.{AVector, Bytes}

trait Val extends Any {
  def tpe: Val.Type
}

// scalastyle:off number.of.methods
object Val {
  trait Type {
    def id: scala.Byte
    def default: Val
  }
  object Type {
    implicit val serde: Serde[Type] =
      byteSerde.xfmap(byte => {
        types.get(Bytes.toPosInt(byte)).toRight(SerdeError.validation(s"Invalid Val Type"))
      }, _.id)

    val types: AVector[Type] = AVector[Type](Bool, Byte, I32, U32, I64, U64, I256, U256, Bool) ++
      AVector[Type](ByteVec, I32Vec, U32Vec, I64Vec, U64Vec, I256Vec, U256Vec)
  }

  // TODO: optimize using value class
  case class Bool(v: Boolean)    extends Val { def tpe: Val.Type = Bool }
  case class Byte(v: scala.Byte) extends Val { def tpe: Val.Type = Byte }
  case class I32(v: util.I32)    extends Val { def tpe: Val.Type = I32 }
  case class U32(v: util.U32)    extends Val { def tpe: Val.Type = U32 }
  case class I64(v: util.I64)    extends Val { def tpe: Val.Type = I64 }
  case class U64(v: util.U64)    extends Val { def tpe: Val.Type = U64 }
  case class I256(v: util.I256)  extends Val { def tpe: Val.Type = I256 }
  case class U256(v: util.U256)  extends Val { def tpe: Val.Type = U256 }

  case class BoolVec(a: Array[Bool]) extends AnyVal with Val { def tpe: Val.Type = BoolVec }
  case class ByteVec(a: Array[Byte]) extends AnyVal with Val { def tpe: Val.Type = ByteVec }
  case class I32Vec(a: Array[I32])   extends AnyVal with Val { def tpe: Val.Type = I32Vec }
  case class U32Vec(a: Array[U32])   extends AnyVal with Val { def tpe: Val.Type = U32Vec }
  case class I64Vec(a: Array[I64])   extends AnyVal with Val { def tpe: Val.Type = I64Vec }
  case class U64Vec(a: Array[U64])   extends AnyVal with Val { def tpe: Val.Type = U64Vec }
  case class I256Vec(a: Array[I256]) extends AnyVal with Val { def tpe: Val.Type = I256Vec }
  case class U256Vec(a: Array[U256]) extends AnyVal with Val { def tpe: Val.Type = U256Vec }

  object Bool extends Type {
    override val id: scala.Byte = 0.toByte
    override def default: Bool  = Bool(false)
  }
  object Byte extends Type {
    override val id: scala.Byte = 1.toByte
    override def default: Byte  = Byte(0.toByte)
  }
  object I32 extends Type {
    override val id: scala.Byte = 2.toByte
    override def default: I32   = I32(util.I32.Zero)
  }
  object U32 extends Type {
    override val id: scala.Byte = 3.toByte
    override def default: U32   = U32(util.U32.Zero)
  }
  object I64 extends Type {
    implicit val serde: Serde[I64] = i64Serde.xmap(I64(_), _.v)
    override val id: scala.Byte    = 4.toByte
    override def default: I64      = I64(util.I64.Zero)
  }
  object U64 extends Type {
    implicit val serde: Serde[U64] = u64Serde.xmap(U64(_), _.v)
    override val id: scala.Byte    = 5.toByte
    override def default: U64      = U64(util.U64.Zero)
  }
  object I256 extends Type {
    override val id: scala.Byte = 6.toByte
    override def default: I256  = I256(util.I256.Zero)
  }
  object U256 extends Type {
    override val id: scala.Byte = 7.toByte
    override def default: U256  = U256(util.U256.Zero)
  }

  object BoolVec extends Type {
    override val id: scala.Byte   = 8.toByte
    override def default: BoolVec = BoolVec(Array.empty)
  }
  object ByteVec extends Type {
    override val id: scala.Byte   = 9.toByte
    override def default: ByteVec = ByteVec(Array.empty)
  }
  object I32Vec extends Type {
    override val id: scala.Byte  = 10.toByte
    override def default: I32Vec = I32Vec(Array.empty)
  }
  object U32Vec extends Type {
    override val id: scala.Byte  = 11.toByte
    override def default: U32Vec = U32Vec(Array.empty)
  }
  object I64Vec extends Type {
    override val id: scala.Byte  = 12.toByte
    override def default: I64Vec = I64Vec(Array.empty)
  }
  object U64Vec extends Type {
    override val id: scala.Byte  = 13.toByte
    override def default: U64Vec = U64Vec(Array.empty)
  }
  object I256Vec extends Type {
    override val id: scala.Byte   = 14.toByte
    override def default: I256Vec = I256Vec(Array.empty)
  }
  object U256Vec extends Type {
    override val id: scala.Byte   = 15.toByte
    override def default: U256Vec = U256Vec(Array.empty)
  }
}
// scalastyle:on number.of.methods
