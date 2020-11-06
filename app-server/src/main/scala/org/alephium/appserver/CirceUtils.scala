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

package org.alephium.appserver

import akka.util.ByteString
import io.circe._
import java.net.{InetAddress, InetSocketAddress}
import scala.reflect.ClassTag

import org.alephium.util.{AVector, Hex, TimeStamp}

object CirceUtils {
  // scalastyle:off regex
  implicit val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)
  // scalastyle:on

  def print(json: Json): String = printer.print(json)

  def codecXmap[T, U](to: T => U, from: U => T)(implicit codec: Codec[T]): Codec[U] = {
    val encoder = codec.contramap(from)
    val decoder = codec.map(to)
    Codec.from(decoder, encoder)
  }

  def codecXemap[T, U](to: T => Either[String, U], from: U => T)(implicit _encoder: Encoder[T],
                                                                 _decoder: Decoder[T]): Codec[U] = {
    val encoder = _encoder.contramap(from)
    val decoder = _decoder.emap(to)
    Codec.from(decoder, encoder)
  }

  implicit def arrayEncoder[A: ClassTag](implicit encoder: Encoder[A]): Encoder[Array[A]] =
    (as: Array[A]) => Json.fromValues(as.map(encoder.apply))

  implicit def arrayDecoder[A: ClassTag](implicit decoder: Decoder[A]): Decoder[Array[A]] =
    Decoder.decodeArray[A]

  implicit def arrayCodec[A: ClassTag](implicit encoder: Encoder[A],
                                       decoder: Decoder[A]): Codec[Array[A]] = {
    Codec.from(arrayDecoder[A], arrayEncoder[A])
  }

  implicit def avectorEncoder[A: ClassTag](implicit encoder: Encoder[A]): Encoder[AVector[A]] =
    (as: AVector[A]) => Json.fromValues(as.toIterable.map(encoder.apply))

  implicit def avectorDecoder[A: ClassTag](implicit decoder: Decoder[A]): Decoder[AVector[A]] =
    Decoder.decodeArray[A].map(AVector.unsafe)

  implicit def avectorCodec[A: ClassTag](implicit encoder: Encoder[A],
                                         decoder: Decoder[A]): Codec[AVector[A]] = {
    Codec.from(avectorDecoder[A], avectorEncoder[A])
  }

  implicit val byteStringEncoder: Encoder[ByteString] =
    (bs: ByteString) => Json.fromString(Hex.toHexString(bs))

  implicit val byteStringDecoder: Decoder[ByteString] =
    Decoder.decodeString.emap { bs =>
      Hex.from(bs).toRight(s"Invalid hex string: $bs")
    }

  implicit val inetAddressCodec: Codec[InetAddress] = {
    codecXemap[String, InetAddress](createInetAddress, _.getHostAddress)
  }

  private def createInetAddress(s: String): Either[String, InetAddress] = {
    try Right(InetAddress.getByName(s))
    catch { case e: Throwable => Left(e.getMessage) }
  }

  implicit val socketAddressCodec: Codec[InetSocketAddress] = {
    val encoder = Encoder.forProduct2[InetSocketAddress, InetAddress, Int]("addr", "port")(sAddr =>
      (sAddr.getAddress, sAddr.getPort))
    val decoder = Decoder
      .forProduct2[(InetAddress, Int), InetAddress, Int]("addr", "port")((_, _))
      .emap { case (iAddr, port) => createSocketAddress(iAddr, port) }
    Codec.from(decoder, encoder)
  }

  private def createSocketAddress(address: InetAddress,
                                  port: Int): Either[String, InetSocketAddress] = {
    try Right(new InetSocketAddress(address, port))
    catch { case e: Throwable => Left(e.getMessage) }
  }

  implicit val timestampEncoder: Encoder[TimeStamp] = Encoder.encodeLong.contramap(_.millis)

  implicit val timestampDecoder: Decoder[TimeStamp] =
    Decoder.decodeLong.ensure(_ >= 0, s"expect positive timestamp").map(TimeStamp.unsafe)

  implicit val timestampCodec: Codec[TimeStamp] = Codec.from(timestampDecoder, timestampEncoder)
}
