package org.alephium.serde

import java.nio.ByteBuffer

import akka.util.ByteString
import shapeless._

import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

trait Serde[T] { self =>
  // Number of bytes necessary for serialization
  def serdeSize: Int

  def serialize(input: T): ByteString

  def deserialize(input: ByteString): Try[T]

  // Note: make sure that T and S are isomorphic
  def xmap[S](to: T => S, from: S => T): Serde[S] = new Serde[S] {
    override def serdeSize: Int = self.serdeSize

    override def serialize(input: S): ByteString = {
      self.serialize(from(input))
    }

    override def deserialize(input: ByteString): Try[S] = {
      self.deserialize(input).map(to)
    }
  }
}

object Serde {
  object ByteSerde extends Serde[Byte] {
    override def serdeSize: Int = java.lang.Byte.BYTES

    override def serialize(input: Byte): ByteString = {
      ByteString(input)
    }

    override def deserialize(input: ByteString): Try[Byte] = Try {
      if (input.size == serdeSize) {
        input(0)
      } else throw InvalidNumberOfBytesException(serdeSize, input.size)
    }
  }

  object IntSerde extends Serde[Int] {
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
      override val serdeSize: Int = size * serde.serdeSize

      override def serialize(input: Seq[T]): ByteString = {
        input.map(serde.serialize).foldLeft(ByteString.empty)(_ ++ _)
      }

      override def deserialize(input: ByteString): Try[Seq[T]] = Try {
        if (input.size == size) {
          input
            .sliding(serde.serdeSize, serde.serdeSize)
            .map { bs =>
              serde.deserialize(bs) match {
                case Success(value)     => value
                case Failure(exception) => throw exception
              }
            }
            .toSeq
        } else throw InvalidNumberOfBytesException(serdeSize, input.size)
      }
    }

  def prepend[A, L <: HList](a: Serde[A], l: Serde[L]): Serde[A :: L] = new Serde[A :: L] {
    override val serdeSize: Int = a.serdeSize + l.serdeSize

    override def serialize(input: A :: L): ByteString = {
      a.serialize(input.head) ++ l.serialize(input.tail)
    }

    override def deserialize(input: ByteString): Try[A :: L] = {
      for {
        head <- a.deserialize(input.slice(0, a.serdeSize))
        tail <- l.deserialize(input.slice(a.serdeSize, serdeSize))
      } yield head :: tail
    }
  }
}
