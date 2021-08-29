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

package org.alephium.serde

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.ClassTag

import akka.util.ByteString

import org.alephium.util.{AVector, Bytes, I256, TimeStamp, U256}
import org.alephium.util.U32

trait Serde[T] extends Serializer[T] with Deserializer[T] { self =>
  // Note: make sure that T and S are isomorphic
  def xmap[S](to: T => S, from: S => T): Serde[S] =
    new Serde[S] {
      override def serialize(input: S): ByteString = {
        self.serialize(from(input))
      }

      override def _deserialize(input: ByteString): SerdeResult[Staging[S]] = {
        self._deserialize(input).map { case Staging(t, rest) =>
          Staging(to(t), rest)
        }
      }

      override def deserialize(input: ByteString): SerdeResult[S] = {
        self.deserialize(input).map(to)
      }
    }

  def xfmap[S](to: T => SerdeResult[S], from: S => T): Serde[S] =
    new Serde[S] {
      override def serialize(input: S): ByteString = {
        self.serialize(from(input))
      }

      override def _deserialize(input: ByteString): SerdeResult[Staging[S]] = {
        self._deserialize(input).flatMap { case Staging(t, rest) =>
          to(t).map(Staging(_, rest))
        }
      }

      override def deserialize(input: ByteString): SerdeResult[S] = {
        self.deserialize(input).flatMap(to)
      }
    }

  def xomap[S](to: T => Option[S], from: S => T): Serde[S] =
    xfmap(
      to(_) match {
        case Some(s) => Right(s)
        case None    => Left(SerdeError.validation("validation error"))
      },
      from
    )

  def validate(test: T => Either[String, Unit]): Serde[T] =
    new Serde[T] {
      override def serialize(input: T): ByteString = self.serialize(input)

      override def _deserialize(input: ByteString): SerdeResult[Staging[T]] = {
        self._deserialize(input).flatMap { case Staging(t, rest) =>
          test(t) match {
            case Right(_)    => Right(Staging(t, rest))
            case Left(error) => Left(SerdeError.validation(error))
          }
        }
      }

      override def deserialize(input: ByteString): SerdeResult[T] = {
        self.deserialize(input).flatMap { t =>
          test(t) match {
            case Right(_)    => Right(t)
            case Left(error) => Left(SerdeError.validation(error))
          }
        }
      }
    }
}

trait FixedSizeSerde[T] extends Serde[T] {
  def serdeSize: Int

  def deserialize0(input: ByteString, f: ByteString => T): SerdeResult[T] =
    if (input.size == serdeSize) {
      Right(f(input))
    } else if (input.size > serdeSize) {
      Left(SerdeError.redundant(serdeSize, input.size))
    } else {
      Left(SerdeError.incompleteData(serdeSize, input.size))
    }

  def deserialize1(input: ByteString, f: ByteString => SerdeResult[T]): SerdeResult[T] = {
    if (input.size == serdeSize) {
      f(input)
    } else if (input.size > serdeSize) {
      Left(SerdeError.redundant(serdeSize, input.size))
    } else {
      Left(SerdeError.incompleteData(serdeSize, input.size))
    }
  }

  override def _deserialize(input: ByteString): SerdeResult[Staging[T]] =
    if (input.size >= serdeSize) {
      val (init, rest) = input.splitAt(serdeSize)
      deserialize(init).map(Staging(_, rest))
    } else {
      Left(SerdeError.incompleteData(serdeSize, input.size))
    }
}

object Serde extends ProductSerde {
  private[serde] object BoolSerde extends FixedSizeSerde[Boolean] {
    override val serdeSize: Int = java.lang.Byte.BYTES

    override def serialize(input: Boolean): ByteString = {
      ByteString(if (input) 1 else 0)
    }

    override def deserialize(input: ByteString): SerdeResult[Boolean] =
      ByteSerde.deserialize(input).flatMap {
        case 0    => Right(false)
        case 1    => Right(true)
        case byte => Left(SerdeError.validation(s"Invalid bool from byte $byte"))
      }
  }

  private[serde] object ByteSerde extends FixedSizeSerde[Byte] {
    override val serdeSize: Int = java.lang.Byte.BYTES

    override def serialize(input: Byte): ByteString = {
      ByteString(input)
    }

    override def deserialize(input: ByteString): SerdeResult[Byte] =
      deserialize0(input, _.apply(0))
  }

  private[serde] object IntSerde extends Serde[Int] {
    override def serialize(input: Int): ByteString =
      CompactInteger.Signed.encode(input)

    override def _deserialize(input: ByteString): SerdeResult[Staging[Int]] =
      CompactInteger.Signed.decodeInt(input)
  }

  private[serde] object LongSerde extends Serde[Long] {
    override def serialize(input: Long): ByteString =
      CompactInteger.Signed.encode(input)

    override def _deserialize(input: ByteString): SerdeResult[Staging[Long]] =
      CompactInteger.Signed.decodeLong(input)
  }

  private[serde] object I256Serde extends Serde[I256] {
    override def serialize(input: I256): ByteString =
      CompactInteger.Signed.encode(input)

    override def _deserialize(input: ByteString): SerdeResult[Staging[I256]] =
      CompactInteger.Signed.decodeI256(input)
  }

  private[serde] object U256Serde extends Serde[U256] {
    override def serialize(input: U256): ByteString =
      CompactInteger.Unsigned.encode(input)

    override def _deserialize(input: ByteString): SerdeResult[Staging[U256]] =
      CompactInteger.Unsigned.decodeU256(input)
  }

  private[serde] object U32Serde extends Serde[U32] {
    override def serialize(input: U32): ByteString =
      CompactInteger.Unsigned.encode(input)

    override def _deserialize(input: ByteString): SerdeResult[Staging[U32]] =
      CompactInteger.Unsigned.decodeU32(input)
  }

  private[serde] object ByteStringSerde extends Serde[ByteString] {
    override def serialize(input: ByteString): ByteString = {
      IntSerde.serialize(input.size) ++ input
    }

    override def _deserialize(input: ByteString): SerdeResult[Staging[ByteString]] = {
      IntSerde._deserialize(input).flatMap { case Staging(size, rest) =>
        if (rest.size >= size) {
          Right(rest.splitAt(size) match { case (value, rest) => Staging(value, rest) })
        } else {
          Left(SerdeError.incompleteData(size, rest.size))
        }
      }
    }
  }

  private object Flags {
    val none: Int  = 0
    val some: Int  = 1
    val left: Int  = 0
    val right: Int = 1

    val noneB: Byte  = none.toByte
    val someB: Byte  = some.toByte
    val leftB: Byte  = left.toByte
    val rightB: Byte = right.toByte
  }

  private[serde] class OptionSerde[T](serde: Serde[T]) extends Serde[Option[T]] {
    override def serialize(input: Option[T]): ByteString =
      input match {
        case None    => ByteSerde.serialize(Flags.noneB)
        case Some(t) => ByteSerde.serialize(Flags.someB) ++ serde.serialize(t)
      }

    override def _deserialize(input: ByteString): SerdeResult[Staging[Option[T]]] = {
      ByteSerde._deserialize(input).flatMap { case Staging(flag, rest) =>
        if (flag == Flags.none) {
          Right(Staging(None, rest))
        } else if (flag == Flags.some) {
          serde._deserialize(rest).map { case Staging(t, r) => Staging(Some(t), r) }
        } else {
          Left(SerdeError.wrongFormat(s"expect 0 or 1 for option flag"))
        }
      }
    }
  }

  private[serde] class EitherSerde[A, B](serdeA: Serde[A], serdeB: Serde[B])
      extends Serde[Either[A, B]] {
    override def serialize(input: Either[A, B]): ByteString =
      input match {
        case Left(a)  => ByteSerde.serialize(Flags.leftB) ++ serdeA.serialize(a)
        case Right(b) => ByteSerde.serialize(Flags.rightB) ++ serdeB.serialize(b)
      }

    override def _deserialize(input: ByteString): SerdeResult[Staging[Either[A, B]]] = {
      ByteSerde._deserialize(input).flatMap { case Staging(flag, rest) =>
        if (flag == Flags.left) {
          serdeA._deserialize(rest).map { case Staging(a, r) => Staging(Left(a), r) }
        } else if (flag == Flags.right) {
          serdeB._deserialize(rest).map { case Staging(b, r) => Staging(Right(b), r) }
        } else {
          Left(SerdeError.wrongFormat(s"expect 0 or 1 for either flag"))
        }
      }
    }
  }

  class BatchDeserializer[T: ClassTag](deserializer: Deserializer[T]) {
    @tailrec
    private def __deserializeSeq[C <: IndexedSeq[T]](
        rest: ByteString,
        index: Int,
        length: Int,
        builder: mutable.Builder[T, C]
    ): SerdeResult[Staging[C]] = {
      if (index == length) {
        Right(Staging(builder.result(), rest))
      } else {
        deserializer._deserialize(rest) match {
          case Right(Staging(t, tRest)) =>
            builder += t
            __deserializeSeq(tRest, index + 1, length, builder)
          case Left(e) => Left(e)
        }
      }
    }

    final def _deserializeSeq[C <: IndexedSeq[T]](
        size: Int,
        input: ByteString,
        newBuilder: => mutable.Builder[T, C]
    ): SerdeResult[Staging[C]] = {
      val builder = newBuilder
      builder.sizeHint(size)
      __deserializeSeq(input, 0, size, builder)
    }

    @tailrec
    private def _deserializeArray(
        rest: ByteString,
        index: Int,
        output: Array[T]
    ): SerdeResult[Staging[Array[T]]] = {
      if (index == output.length) {
        Right(Staging(output, rest))
      } else {
        deserializer._deserialize(rest) match {
          case Right(Staging(t, tRest)) =>
            output.update(index, t)
            _deserializeArray(tRest, index + 1, output)
          case Left(e) => Left(e)
        }
      }
    }

    def _deserializeArray(n: Int, input: ByteString): SerdeResult[Staging[Array[T]]] = {
      if (n < 0) {
        Left(SerdeError.validation(s"Negative array size: $n"))
      } else if (n > input.length) { // might cause memory issues if n is too large
        Left(SerdeError.validation(s"Malicious array size: $n"))
      } else {
        _deserializeArray(input, 0, Array.ofDim[T](n))
      }
    }

    def _deserializeAVector(n: Int, input: ByteString): SerdeResult[Staging[AVector[T]]] = {
      _deserializeArray(n, input).map(t => Staging(AVector.unsafe(t.value), t.rest))
    }
  }

  def bytesSerde(bytes: Int): Serde[ByteString] =
    new FixedSizeSerde[ByteString] {
      def serdeSize: Int = bytes

      override def serialize(bs: ByteString): ByteString = {
        assume(bs.length == serdeSize)
        bs
      }

      override def deserialize(input: ByteString): SerdeResult[ByteString] =
        deserialize0(input, identity)
    }

  private[serde] def fixedSizeSerde[T: ClassTag](size: Int, serde: Serde[T]): Serde[AVector[T]] = {
    assume(size >= 0)
    new BatchDeserializer[T](serde) with Serde[AVector[T]] {
      override def serialize(input: AVector[T]): ByteString = {
        input.map(serde.serialize).fold(ByteString.empty)(_ ++ _)
      }

      override def _deserialize(input: ByteString): SerdeResult[Staging[AVector[T]]] = {
        _deserializeAVector(size, input)
      }
    }
  }

  private[serde] class AVectorSerializer[T](serializer: Serializer[T])
      extends Serializer[AVector[T]] {
    override def serialize(input: AVector[T]): ByteString = {
      input.map(serializer.serialize).fold(IntSerde.serialize(input.length))(_ ++ _)
    }
  }

  private[serde] class AVectorDeserializer[T: ClassTag](deserializer: Deserializer[T])
      extends BatchDeserializer[T](deserializer)
      with Deserializer[AVector[T]] {
    override def _deserialize(input: ByteString): SerdeResult[Staging[AVector[T]]] = {
      IntSerde._deserialize(input).flatMap { case Staging(size, rest) =>
        _deserializeAVector(size, rest)
      }
    }
  }

  private[serde] def avectorSerde[T: ClassTag](serde: Serde[T]): Serde[AVector[T]] =
    new BatchDeserializer[T](serde) with Serde[AVector[T]] {
      override def serialize(input: AVector[T]): ByteString = {
        input.map(serde.serialize).fold(IntSerde.serialize(input.length))(_ ++ _)
      }

      override def _deserialize(input: ByteString): SerdeResult[Staging[AVector[T]]] = {
        IntSerde._deserialize(input).flatMap { case Staging(size, rest) =>
          _deserializeAVector(size, rest)
        }
      }
    }

  private[serde] def dynamicSizeSerde[C <: IndexedSeq[T], T: ClassTag](
      serde: Serde[T],
      newBuilder: => mutable.Builder[T, C]
  ): Serde[C] =
    new BatchDeserializer[T](serde) with Serde[C] {
      override def serialize(input: C): ByteString = {
        input.map(serde.serialize).fold(IntSerde.serialize(input.length))(_ ++ _)
      }

      override def _deserialize(input: ByteString): SerdeResult[Staging[C]] = {
        IntSerde._deserialize(input).flatMap { case Staging(size, rest) =>
          _deserializeSeq(size, rest, newBuilder)
        }
      }
    }

  private[serde] object TimeStampSerde extends FixedSizeSerde[TimeStamp] {
    override val serdeSize: Int = 8

    override def serialize(input: TimeStamp): ByteString = {
      Bytes.from(input.millis)
    }

    override def deserialize(input: ByteString): SerdeResult[TimeStamp] = {
      deserialize1(
        input,
        input =>
          TimeStamp.from(Bytes.toLongUnsafe(input)) match {
            case Some(ts) => Right(ts)
            case None     => Left(SerdeError.validation(s"Negative timestamp"))
          }
      )
    }
  }
}
