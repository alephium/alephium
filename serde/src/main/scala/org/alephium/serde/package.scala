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

package org.alephium

import java.math.BigInteger
import java.net.{InetAddress, InetSocketAddress, UnknownHostException}

import scala.collection.immutable.ArraySeq
import scala.reflect.ClassTag

import akka.util.ByteString

import org.alephium.util._

package object serde {
  import Serde._

  type SerdeResult[T] = Either[SerdeError, T]

  def serdeImpl[T](implicit serde: Serde[T]): Serde[T] = serde
  def serdeImpl[T0, T1](implicit serde0: Serde[T0], serde1: Serde[T1]): Serde[(T0, T1)] =
    Serde.tuple2

  def serialize[T](input: T)(implicit serializer: Serializer[T]): ByteString =
    serializer.serialize(input)

  def deserialize[T](input: ByteString)(implicit deserializer: Deserializer[T]): SerdeResult[T] =
    deserializer.deserialize(input)

  def _deserialize[T](
      input: ByteString
  )(implicit deserializer: Deserializer[T]): SerdeResult[Staging[T]] =
    deserializer._deserialize(input)

  implicit val boolSerde: Serde[Boolean] = BoolSerde
  implicit val byteSerde: Serde[Byte]    = ByteSerde
  implicit val intSerde: Serde[Int]      = IntSerde
  implicit val u32Serde: Serde[U32]      = U32Serde
  implicit val i256Serde: Serde[I256]    = I256Serde
  implicit val u256Serde: Serde[U256]    = U256Serde

  implicit val bytestringSerde: Serde[ByteString] = ByteStringSerde

  implicit val stringSerde: Serde[String] =
    ByteStringSerde.xmap(_.utf8String, ByteString.fromString)

  implicit def optionSerde[T](implicit serde: Serde[T]): Serde[Option[T]] =
    new OptionSerde[T](serde)

  implicit def eitherSerde[A, B](implicit serdeA: Serde[A], serdeB: Serde[B]): Serde[Either[A, B]] =
    new EitherSerde[A, B](serdeA, serdeB)

  def fixedSizeSerde[T: ClassTag](size: Int)(implicit serde: Serde[T]): Serde[AVector[T]] =
    Serde.fixedSizeSerde[T](size, serde)

  implicit def avectorSerializer[T](implicit serializer: Serializer[T]): Serializer[AVector[T]] =
    new AVectorSerializer[T](serializer)

  implicit def avectorDeserializer[T: ClassTag](implicit
      deserializer: Deserializer[T]
  ): Deserializer[AVector[T]] =
    new AVectorDeserializer[T](deserializer)

  implicit val boolAVectorSerde: Serde[AVector[Boolean]] = avectorSerde[Boolean]
  implicit val byteAVectorSerde: Serde[AVector[Byte]]    = avectorSerde[Byte]
  implicit val intAVectorSerde: Serde[AVector[Int]]      = avectorSerde[Int]
  implicit val i256AVectorSerde: Serde[AVector[I256]]    = avectorSerde[I256]
  implicit val u256AVectorSerde: Serde[AVector[U256]]    = avectorSerde[U256]

  implicit def avectorSerde[T: ClassTag](implicit serde: Serde[T]): Serde[AVector[T]] =
    Serde.avectorSerde[T](serde)

  implicit def arraySeqSerde[T: ClassTag](implicit serde: Serde[T]): Serde[ArraySeq[T]] =
    dynamicSizeSerde[ArraySeq[T], T](serde, ArraySeq.newBuilder)

  implicit val bigIntegerSerde: Serde[BigInteger] =
    avectorSerde[Byte].xmap(vc => new BigInteger(vc.toArray), bi => AVector.unsafe(bi.toByteArray))

  /*
   * Note: only ipv4 and ipv6 addresses are supported in the following serdes
   * addresses based on hostnames are not supported
   */

  implicit val inetAddressSerde: Serde[InetAddress] = bytestringSerde
    .xfmap(bs => createInetAddress(bs), ia => ByteString.fromArrayUnsafe(ia.getAddress))

  def createInetAddress(bs: ByteString): SerdeResult[InetAddress] = {
    try Right(InetAddress.getByAddress(bs.toArray))
    catch { case e: UnknownHostException => Left(SerdeError.wrongFormat(e.getMessage)) }
  }

  implicit val inetSocketAddressSerde: Serde[InetSocketAddress] =
    tuple2[InetAddress, Int].xfmap(
      { case (address, port) => createSocketAddress(address, port) },
      sAddress => (sAddress.getAddress, sAddress.getPort)
    )

  def createSocketAddress(inetAddress: InetAddress, port: Int): SerdeResult[InetSocketAddress] = {
    try Right(new InetSocketAddress(inetAddress, port))
    catch { case e: IllegalArgumentException => Left(SerdeError.wrongFormat(e.getMessage)) }
  }

  implicit val serdeTS: Serde[TimeStamp] = TimeStampSerde
}
