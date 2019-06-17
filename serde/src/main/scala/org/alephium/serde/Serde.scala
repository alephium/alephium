package org.alephium.serde

import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer

import akka.util.ByteString
import org.alephium.util.AVector

import scala.annotation.tailrec
import scala.reflect.ClassTag

trait Serde[T] extends Serializer[T] with Deserializer[T] { self =>
  // Note: make sure that T and S are isomorphic
  def xmap[S](to: T => S, from: S => T): Serde[S] = new Serde[S] {
    override def serialize(input: S): ByteString = {
      self.serialize(from(input))
    }

    override def _deserialize(input: ByteString): SerdeResult[(S, ByteString)] = {
      self._deserialize(input).map {
        case (t, rest) => (to(t), rest)
      }
    }

    override def deserialize(input: ByteString): SerdeResult[S] = {
      self.deserialize(input).map(to)
    }
  }

  def xfmap[S](to: T => SerdeResult[S], from: S => T): Serde[S] = new Serde[S] {
    override def serialize(input: S): ByteString = {
      self.serialize(from(input))
    }

    override def _deserialize(input: ByteString): SerdeResult[(S, ByteString)] = {
      self._deserialize(input).flatMap {
        case (t, rest) => to(t).map((_, rest))
      }
    }

    override def deserialize(input: ByteString): SerdeResult[S] = {
      self.deserialize(input).flatMap(to)
    }
  }

  def validate(predicate: T => Boolean, error: T => String): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = self.serialize(input)

    override def _deserialize(input: ByteString): SerdeResult[(T, ByteString)] = {
      // write explicitly for performance
      self._deserialize(input).flatMap {
        case (t, rest) =>
          if (predicate(t)) {
            Right((t, rest))
          } else Left(SerdeError.wrongFormat(error(t)))
      }
    }

    override def deserialize(input: ByteString): SerdeResult[T] = {
      // write explicitly for performance
      self.deserialize(input).flatMap { t =>
        if (predicate(t)) {
          Right(t)
        } else Left(SerdeError.wrongFormat(error(t)))
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
      Left(SerdeError.notEnoughBytes(serdeSize, input.size))
    }

  override def _deserialize(input: ByteString): SerdeResult[(T, ByteString)] =
    if (input.size >= serdeSize) {
      val (init, rest) = input.splitAt(serdeSize)
      deserialize(init).map((_, rest))
    } else Left(SerdeError.notEnoughBytes(serdeSize, input.size))
}

object Serde extends ProductSerde {
  object ByteSerde extends FixedSizeSerde[Byte] {
    override val serdeSize: Int = java.lang.Byte.BYTES

    override def serialize(input: Byte): ByteString = {
      ByteString(input)
    }

    override def deserialize(input: ByteString): SerdeResult[Byte] =
      deserialize0(input, _.apply(0))
  }

  object IntSerde extends FixedSizeSerde[Int] {
    override val serdeSize: Int = java.lang.Integer.BYTES

    override def serialize(input: Int): ByteString = {
      val buf = ByteBuffer.allocate(serdeSize).putInt(input)
      buf.flip()
      ByteString.fromByteBuffer(buf)
    }

    override def deserialize(input: ByteString): SerdeResult[Int] =
      deserialize0(input, _.asByteBuffer.getInt())
  }

  object LongSerde extends FixedSizeSerde[Long] {
    override val serdeSize: Int = java.lang.Long.BYTES

    override def serialize(input: Long): ByteString = {
      val buf = ByteBuffer.allocate(serdeSize).putLong(input)
      buf.flip()
      ByteString.fromByteBuffer(buf)
    }

    override def deserialize(input: ByteString): SerdeResult[Long] =
      deserialize0(input, _.asByteBuffer.getLong())
  }

  object ByteStringSerde extends Serde[ByteString] {
    override def serialize(input: ByteString): ByteString = {
      IntSerde.serialize(input.size) ++ input
    }

    override def _deserialize(input: ByteString): SerdeResult[(ByteString, ByteString)] = {
      IntSerde._deserialize(input).flatMap {
        case (size, rest) =>
          if (rest.size >= size) {
            Right(rest.splitAt(size))
          } else {
            Left(SerdeError.notEnoughBytes(size, rest.size))
          }
      }
    }
  }

  implicit val inetAddressSerde: Serde[InetAddress] =
    bytesSerde(4).xmap(bs => InetAddress.getByAddress(bs.toArray),
                       ia => ByteString.fromArrayUnsafe(ia.getAddress))

  implicit val inetSocketAddressSerde: Serde[InetSocketAddress] =
    forProduct2[InetAddress, Int, InetSocketAddress](
      { (hostname, port) =>
        new InetSocketAddress(hostname, port)
      },
      isa => (isa.getAddress, isa.getPort)
    )

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

  class OptionSerde[T](serde: Serde[T]) extends Serde[Option[T]] {
    override def serialize(input: Option[T]): ByteString = input match {
      case None    => ByteSerde.serialize(Flags.noneB)
      case Some(t) => ByteSerde.serialize(Flags.someB) ++ serde.serialize(t)
    }

    override def _deserialize(input: ByteString): SerdeResult[(Option[T], ByteString)] = {
      ByteSerde._deserialize(input).flatMap {
        case (flag, rest) =>
          if (flag == Flags.none) {
            Right((None, rest))
          } else if (flag == Flags.some) {
            serde._deserialize(rest).map { case (t, r) => (Some(t), r) }
          } else {
            Left(SerdeError.wrongFormat(s"expect 0 or 1 for option flag"))
          }
      }
    }
  }

  class EitherSerde[A, B](serdeA: Serde[A], serdeB: Serde[B]) extends Serde[Either[A, B]] {
    override def serialize(input: Either[A, B]): ByteString = input match {
      case Left(a)  => ByteSerde.serialize(Flags.leftB) ++ serdeA.serialize(a)
      case Right(b) => ByteSerde.serialize(Flags.rightB) ++ serdeB.serialize(b)
    }

    override def _deserialize(input: ByteString): SerdeResult[(Either[A, B], ByteString)] = {
      ByteSerde._deserialize(input).flatMap {
        case (flag, rest) =>
          if (flag == Flags.left) {
            serdeA._deserialize(rest).map { case (a, r) => (Left(a), r) }
          } else if (flag == Flags.right) {
            serdeB._deserialize(rest).map { case (b, r) => (Right(b), r) }
          } else {
            Left(SerdeError.wrongFormat(s"expect 0 or 1 for either flag"))
          }
      }
    }
  }

  class AVectorDe[T: ClassTag](deserializer: Deserializer[T]) {
    @tailrec
    final def _deserialize(rest: ByteString,
                           itemCnt: Int,
                           output: AVector[T]): SerdeResult[(AVector[T], ByteString)] = {
      if (itemCnt == 0) Right((output, rest))
      else {
        deserializer._deserialize(rest) match {
          case Right((t, tRest)) =>
            _deserialize(tRest, itemCnt - 1, output :+ t)
          case Left(e) => Left(e)
        }
      }
    }
  }

  def apply[T](implicit T: Serde[T]): Serde[T] = T

  def bytesSerde(bytes: Int): Serde[ByteString] = new FixedSizeSerde[ByteString] {
    override val serdeSize: Int = bytes

    override def serialize(bs: ByteString): ByteString = {
      assert(bs.length == serdeSize)
      bs
    }

    override def deserialize(input: ByteString): SerdeResult[ByteString] =
      deserialize0(input, identity)
  }

  def fixedSizeSerde[T: ClassTag](size: Int, serde: Serde[T]): Serde[AVector[T]] =
    new AVectorDe[T](serde) with Serde[AVector[T]] {
      override def serialize(input: AVector[T]): ByteString = {
        input.map(serde.serialize).fold(ByteString.empty)(_ ++ _)
      }

      override def _deserialize(input: ByteString): SerdeResult[(AVector[T], ByteString)] = {
        _deserialize(input, size, AVector.empty)
      }
    }

  class AVectorSerializer[T: ClassTag](serializer: Serializer[T]) extends Serializer[AVector[T]] {
    override def serialize(input: AVector[T]): ByteString = {
      input.map(serializer.serialize).fold(IntSerde.serialize(input.length))(_ ++ _)
    }
  }

  class AVectorDeserializer[T: ClassTag](deserializer: Deserializer[T])
      extends AVectorDe[T](deserializer)
      with Deserializer[AVector[T]] {
    override def _deserialize(input: ByteString): SerdeResult[(AVector[T], ByteString)] = {
      IntSerde._deserialize(input).flatMap {
        case (size, rest) =>
          _deserialize(rest, size, AVector.empty)
      }
    }
  }

  def dynamicSizeSerde[T: ClassTag](serde: Serde[T]): Serde[AVector[T]] =
    new AVectorDe[T](serde) with Serde[AVector[T]] {
      override def serialize(input: AVector[T]): ByteString = {
        input.map(serde.serialize).fold(IntSerde.serialize(input.length))(_ ++ _)
      }

      override def _deserialize(input: ByteString): SerdeResult[(AVector[T], ByteString)] = {
        IntSerde._deserialize(input).flatMap {
          case (size, rest) =>
            _deserialize(rest, size, AVector.empty)
        }
      }
    }
}
