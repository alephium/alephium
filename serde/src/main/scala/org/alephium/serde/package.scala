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

import java.net.{InetAddress, InetSocketAddress, UnknownHostException}

import scala.collection.mutable
import scala.reflect.ClassTag

import akka.util.ByteString

import org.alephium.util._

package object serde {
  import Serde._

  type SerdeResult[T] = Either[SerdeError, T]

  def serdeImpl[T](implicit sd: Serde[T]): Serde[T] = sd

  def serialize[T](input: T)(implicit serializer: Serializer[T]): ByteString =
    serializer.serialize(input)

  def deserialize[T](input: ByteString)(implicit deserializer: Deserializer[T]): SerdeResult[T] =
    deserializer.deserialize(input)

  def _deserialize[T](input: ByteString)(
      implicit deserializer: Deserializer[T]): SerdeResult[Staging[T]] =
    deserializer._deserialize(input)

  implicit val boolSerde: Serde[Boolean] = BoolSerde
  implicit val byteSerde: Serde[Byte]    = ByteSerde
  implicit val intSerde: Serde[Int]      = IntSerde
  implicit val longSerde: Serde[Long]    = LongSerde
  implicit val i32Serde: Serde[I32]      = intSerde.xmap(I32.unsafe, _.v)
  implicit val u32Serde: Serde[U32]      = intSerde.xmap(U32.unsafe, _.v)
  implicit val i64Serde: Serde[I64]      = longSerde.xmap(I64.from, _.v)
  implicit val u64Serde: Serde[U64]      = longSerde.xmap(U64.unsafe, _.v)
  implicit val i256Serde: Serde[I256]    = Serde.bytesSerde(32).xmap(I256.unsafe, _.toBytes)
  implicit val u256Serde: Serde[U256]    = Serde.bytesSerde(32).xmap(U256.unsafe, _.toBytes)

  implicit val bytestringSerde: Serde[ByteString] = ByteStringSerde

  implicit val stringSerde: Serde[String] =
    ByteStringSerde.xmap(_.utf8String, ByteString.fromString)

  implicit def optionSerde[T](implicit serde: Serde[T]): Serde[Option[T]] =
    new OptionSerde[T](serde)

  implicit def eitherSerde[A, B](implicit serdeA: Serde[A], serdeB: Serde[B]): Serde[Either[A, B]] =
    new EitherSerde[A, B](serdeA, serdeB)

  def fixedSizeSerde[T: ClassTag](size: Int)(implicit serde: Serde[T]): Serde[AVector[T]] =
    Serde.fixedSizeSerde[T](size, serde)

  implicit def avectorSerializer[T: ClassTag](
      implicit serializer: Serializer[T]): Serializer[AVector[T]] =
    new AVectorSerializer[T](serializer)

  implicit def avectorDeserializer[T: ClassTag](
      implicit deserializer: Deserializer[T]): Deserializer[AVector[T]] =
    new AVectorDeserializer[T](deserializer)

  implicit val boolAVectorSerde: Serde[AVector[Boolean]] = avectorSerde[Boolean]
  implicit val byteAVectorSerde: Serde[AVector[Byte]]    = avectorSerde[Byte]
  implicit val intAVectorSerde: Serde[AVector[Int]]      = avectorSerde[Int]
  implicit val longAVectorSerde: Serde[AVector[Long]]    = avectorSerde[Long]
  implicit val i64AVectorSerde: Serde[AVector[I64]]      = avectorSerde[I64]
  implicit val u64AVectorSerde: Serde[AVector[U64]]      = avectorSerde[U64]
  implicit val i256AVectorSerde: Serde[AVector[I256]]    = avectorSerde[I256]
  implicit val u256AVectorSerde: Serde[AVector[U256]]    = avectorSerde[U256]

  implicit def avectorSerde[T: ClassTag](implicit serde: Serde[T]): Serde[AVector[T]] =
    Serde.avectorSerde[T](serde)

  implicit val boolArraySeqSerde: Serde[mutable.ArraySeq[Boolean]] = arraySeqSerde[Boolean]
  implicit val byteArraySeqSerde: Serde[mutable.ArraySeq[Byte]]    = arraySeqSerde[Byte]
  implicit val intArraySeqSerde: Serde[mutable.ArraySeq[Int]]      = arraySeqSerde[Int]
  implicit val longArraySeqSerde: Serde[mutable.ArraySeq[Long]]    = arraySeqSerde[Long]
  implicit val i64ArraySeqSerde: Serde[mutable.ArraySeq[I64]]      = arraySeqSerde[I64]
  implicit val u64ArraySeqSerde: Serde[mutable.ArraySeq[U64]]      = arraySeqSerde[U64]
  implicit val i256ArraySeqSerde: Serde[mutable.ArraySeq[I256]]    = arraySeqSerde[I256]
  implicit val u256ArraySeqSerde: Serde[mutable.ArraySeq[U256]]    = arraySeqSerde[U256]

  implicit def arraySeqSerde[T: ClassTag](implicit serde: Serde[T]): Serde[mutable.ArraySeq[T]] =
    dynamicSizeSerde(serde, mutable.ArraySeq.newBuilder)

  implicit val bigIntSerde: Serde[BigInt] =
    avectorSerde[Byte].xmap(vc => BigInt(vc.toArray), bi => AVector.unsafe(bi.toByteArray))

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
    tuple2[InetAddress, Int].xfmap({ case (address, port) => createSocketAddress(address, port) },
                                   sAddress => (sAddress.getAddress, sAddress.getPort))

  def createSocketAddress(inetAddress: InetAddress, port: Int): SerdeResult[InetSocketAddress] = {
    try Right(new InetSocketAddress(inetAddress, port))
    catch { case e: IllegalArgumentException => Left(SerdeError.wrongFormat(e.getMessage)) }
  }

  implicit val serdeTS: Serde[TimeStamp] = longSerde.xomap(TimeStamp.from, _.millis)
}
