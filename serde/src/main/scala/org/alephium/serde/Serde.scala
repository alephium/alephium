package org.alephium.serde

import java.nio.ByteBuffer

import akka.util.ByteString

import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

trait Serializer[T] {
  def serialize(input: T): ByteString
}

trait Deserializer[T] { self =>
  def _deserialize(input: ByteString): Try[(T, ByteString)]

  def deserialize(input: ByteString): Try[T] =
    _deserialize(input).flatMap {
      case (output, rest) =>
        if (rest.isEmpty) Success(output)
        else Failure(WrongFormatException.redundant(input.size - rest.size, input.size))
    }

  def validateGet[U](get: T => Option[U], error: T => String): Deserializer[U] =
    (input: ByteString) => {
      self._deserialize(input).flatMap {
        case (t, rest) =>
          get(t) match {
            case Some(u) => Success((u, rest))
            case None    => Failure(new WrongFormatException(error(t)))
          }
      }
    }
}

trait Serde[T] extends Serializer[T] with Deserializer[T] { self =>
  // Note: make sure that T and S are isomorphic
  def xmap[S](to: T => S, from: S => T): Serde[S] = new Serde[S] {
    override def serialize(input: S): ByteString = {
      self.serialize(from(input))
    }

    override def _deserialize(input: ByteString): Try[(S, ByteString)] = {
      self._deserialize(input).map {
        case (t, rest) => (to(t), rest)
      }
    }

    override def deserialize(input: ByteString): Try[S] = {
      self.deserialize(input).map(to)
    }
  }

  def validate(predicate: T => Boolean, error: T => String): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = self.serialize(input)

    override def _deserialize(input: ByteString): Try[(T, ByteString)] = {
      // write explicitly for performance
      self._deserialize(input).flatMap {
        case (t, rest) =>
          if (predicate(t)) {
            Success((t, rest))
          } else Failure(new WrongFormatException(error(t)))
      }
    }

    override def deserialize(input: ByteString): Try[T] = {
      // write explicitly for performance
      self.deserialize(input).flatMap { t =>
        if (predicate(t)) {
          Success(t)
        } else Failure(new WrongFormatException(error(t)))
      }
    }
  }
}

trait FixedSizeSerde[T] extends Serde[T] {
  def serdeSize: Int

  def deserialize0(input: ByteString, f: ByteString => T): Try[T] = Try {
    if (input.size == serdeSize) {
      f(input)
    } else if (input.size > serdeSize) {
      throw WrongFormatException.redundant(serdeSize, input.size)
    } else {
      throw NotEnoughBytesException(serdeSize, input.size)
    }
  }

  override def _deserialize(input: ByteString): Try[(T, ByteString)] =
    if (input.size >= serdeSize) {
      val (init, rest) = input.splitAt(serdeSize)
      deserialize(init).map((_, rest))
    } else Failure(NotEnoughBytesException(serdeSize, input.size))
}

object Serde extends ProductSerde {
  object ByteSerde extends FixedSizeSerde[Byte] {
    override val serdeSize: Int = java.lang.Byte.BYTES

    override def serialize(input: Byte): ByteString = {
      ByteString(input)
    }

    override def deserialize(input: ByteString): Try[Byte] =
      deserialize0(input, _.apply(0))
  }

  object IntSerde extends FixedSizeSerde[Int] {
    override val serdeSize: Int = java.lang.Integer.BYTES

    override def serialize(input: Int): ByteString = {
      val buf = ByteBuffer.allocate(serdeSize).putInt(input)
      buf.flip()
      ByteString.fromByteBuffer(buf)
    }

    override def deserialize(input: ByteString): Try[Int] =
      deserialize0(input, _.asByteBuffer.getInt())
  }

  object LongSerde extends FixedSizeSerde[Long] {
    override val serdeSize: Int = java.lang.Long.BYTES

    override def serialize(input: Long): ByteString = {
      val buf = ByteBuffer.allocate(serdeSize).putLong(input)
      buf.flip()
      ByteString.fromByteBuffer(buf)
    }

    override def deserialize(input: ByteString): Try[Long] =
      deserialize0(input, _.asByteBuffer.getLong())
  }

  private abstract class SeqSerde[T: ClassTag](serde: Serde[T]) extends Serde[Seq[T]] {
    @tailrec
    final def _deserialize(rest: ByteString,
                           itemCnt: Int,
                           output: Seq[T]): Try[(Seq[T], ByteString)] = {
      if (itemCnt == 0) Success((output, rest))
      else {
        serde._deserialize(rest) match {
          case Success((t, tRest)) =>
            _deserialize(tRest, itemCnt - 1, output :+ t)
          case Failure(e) => Failure(e)
        }
      }
    }
  }

  def bytesSerde(bytes: Int): Serde[ByteString] = new FixedSizeSerde[ByteString] {
    override val serdeSize: Int = bytes

    override def serialize(bs: ByteString): ByteString = {
      assert(bs.length == serdeSize)
      bs
    }

    override def deserialize(input: ByteString): Try[ByteString] =
      deserialize0(input, identity)
  }

  def fixedSizeBytesSerde[T: ClassTag](size: Int, serde: Serde[T]): Serde[Seq[T]] =
    new SeqSerde[T](serde) {
      override def serialize(input: Seq[T]): ByteString = {
        input.map(serde.serialize).foldLeft(ByteString.empty)(_ ++ _)
      }

      override def _deserialize(input: ByteString): Try[(Seq[T], ByteString)] = {
        _deserialize(input, size, Seq.empty)
      }
    }

  def dynamicSizeBytesSerde[T: ClassTag](serde: Serde[T]): Serde[Seq[T]] =
    new SeqSerde[T](serde) {
      override def serialize(input: Seq[T]): ByteString = {
        input.map(serde.serialize).foldLeft(IntSerde.serialize(input.size))(_ ++ _)
      }

      override def _deserialize(input: ByteString): Try[(Seq[T], ByteString)] = {
        IntSerde._deserialize(input).flatMap {
          case (size, rest) =>
            _deserialize(rest, size, Seq.empty)
        }
      }
    }
}
