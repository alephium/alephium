package org.alephium.serde

import java.nio.ByteBuffer

import akka.util.ByteString
import shapeless._

import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

trait Serializer[T] {
  def serialize(input: T): ByteString
}

trait Deserializer[T] {
  def _deserialize(input: ByteString): Try[(T, ByteString)]

  def deserialize(input: ByteString): Try[T] =
    _deserialize(input).flatMap {
      case (output, rest) =>
        if (rest.isEmpty) Success(output)
        else Failure(InvalidNumberOfBytesException(input.size - rest.size, input.size))
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

  def deValidate(predictor: T => Boolean, error: String): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = self.serialize(input)

    override def _deserialize(input: ByteString): Try[(T, ByteString)] = {
      // write explicitly for performance
      self._deserialize(input).flatMap {
        case (t, rest) =>
          if (predictor(t)) {
            Success((t, rest))
          } else throw ValidationError(error)
      }
    }

    override def deserialize(input: ByteString): Try[T] = {
      // write explicitly for performance
      self.deserialize(input).flatMap { t =>
        if (predictor(t)) {
          Success(t)
        } else throw ValidationError(error)
      }
    }
  }
}

trait FixedSizeSerde[T] extends Serde[T] {
  def serdeSize: Int

  def deserialize0(input: ByteString, f: ByteString => T): Try[T] = Try {
    if (input.size == serdeSize) {
      f(input)
    } else throw InvalidNumberOfBytesException(serdeSize, input.size)
  }

  override def _deserialize(input: ByteString): Try[(T, ByteString)] =
    if (input.size >= serdeSize) {
      val (init, rest) = input.splitAt(serdeSize)
      deserialize(init).map((_, rest))
    } else Failure(InvalidNumberOfBytesException(serdeSize, input.size))
}

object Serde {
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

  object HNilSerde extends FixedSizeSerde[HNil] {

    override def serdeSize: Int = 0

    override def serialize(input: HNil): ByteString = ByteString.empty

    override def _deserialize(input: ByteString): Try[(HNil, ByteString)] = Success((HNil, input))

    override def deserialize(input: ByteString): Try[HNil] =
      if (input.isEmpty) Success(HNil) else throw InvalidNumberOfBytesException(0, input.size)
  }

  def prepend[A, L <: HList](a: Serde[A], l: Serde[L]): Serde[A :: L] =
    new Serde[A :: L] {
      override def serialize(input: A :: L): ByteString = {
        a.serialize(input.head) ++ l.serialize(input.tail)
      }

      override def _deserialize(input: ByteString): Try[(::[A, L], ByteString)] = {
        for {
          (a, aRest) <- a._deserialize(input)
          (l, lRest) <- l._deserialize(aRest)
        } yield (a :: l, lRest)
      }

      override def deserialize(input: ByteString): Try[A :: L] = {
        for {
          (a, aRest) <- a._deserialize(input)
          l          <- l.deserialize(aRest)
        } yield a :: l
      }
    }
}
