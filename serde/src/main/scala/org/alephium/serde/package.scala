package org.alephium

import java.net.{InetAddress, InetSocketAddress}

import scala.reflect.ClassTag

import akka.util.ByteString

import org.alephium.util.AVector

package object serde {
  import Serde._

  type SerdeResult[T] = Either[SerdeError, T]

  def serialize[T](input: T)(implicit serializer: Serde[T]): ByteString =
    serializer.serialize(input)

  def deserialize[T](input: ByteString)(implicit deserializer: Serde[T]): SerdeResult[T] =
    deserializer.deserialize(input)

  implicit val byteSerde: Serde[Byte] = ByteSerde

  implicit val intSerde: Serde[Int] = IntSerde

  implicit val longSerde: Serde[Long] = LongSerde

  implicit val bytestringSerde: Serde[ByteString] = ByteStringSerde

  implicit val stringSerde: Serde[String] =
    ByteStringSerde.xmap(_.utf8String, ByteString.fromString)

  implicit def optionSerde[T](implicit serde: Serde[T]): Serde[Option[T]] =
    new OptionSerde[T](serde)

  implicit def eitherSerde[A, B](implicit serdeA: Serde[A], serdeB: Serde[B]): Serde[Either[A, B]] =
    new EitherSerde[A, B](serdeA, serdeB)

  implicit def avectorSerde[T: ClassTag](implicit serde: Serde[T]): Serde[AVector[T]] =
    dynamicSizeSerde(serde)

  implicit def avectorSerializer[T: ClassTag](
      implicit serializer: Serializer[T]): Serializer[AVector[T]] =
    new AVectorSerializer[T](serializer)

  implicit def avectorDeserializer[T: ClassTag](
      implicit deserializer: Deserializer[T]): Deserializer[AVector[T]] =
    new AVectorDeserializer[T](deserializer)

  implicit val bigIntSerde: Serde[BigInt] =
    avectorSerde[Byte].xmap(vc => BigInt(vc.toArray), bi => AVector.unsafe(bi.toByteArray))

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
}
