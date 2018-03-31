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
}

trait FixedSizeSerde[T] extends Serde[T] {
  def serdeSize: Int

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

    override def deserialize(input: ByteString): Try[Byte] = Try {
      if (input.size == serdeSize) {
        input(0)
      } else throw InvalidNumberOfBytesException(serdeSize, input.size)
    }
  }

  object IntSerde extends FixedSizeSerde[Int] {
    override val serdeSize: Int = java.lang.Integer.BYTES

    override def serialize(input: Int): ByteString = {
      val buf = ByteBuffer.allocate(serdeSize).putInt(input)
      buf.flip()
      ByteString.fromByteBuffer(buf)
    }

    override def deserialize(input: ByteString): Try[Int] = Try {
      input.asByteBuffer.getInt()
    }
  }

  def fixedSizeBytesSerde[T: ClassTag](size: Int, serde: Serde[T]): Serde[Seq[T]] =
    new Serde[Seq[T]] {
      override def serialize(input: Seq[T]): ByteString = {
        input.map(serde.serialize).foldLeft(ByteString.empty)(_ ++ _)
      }

      @tailrec
      private def _deserialize(rest: ByteString,
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

      override def _deserialize(input: ByteString): Try[(Seq[T], ByteString)] = {
        _deserialize(input, size, Seq.empty)
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
