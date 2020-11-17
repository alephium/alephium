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

package org.alephium.wallet.circe

import java.net.InetAddress

import scala.reflect.ClassTag
import scala.util.Try

import _root_.io.circe._
import akka.util.ByteString

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.util.{AVector, Hex, U256}

trait UtilCodecs {

  implicit def avectorEncoder[A: ClassTag](implicit encoder: Encoder[A]): Encoder[AVector[A]] =
    (as: AVector[A]) => Json.fromValues(as.toIterable.map(encoder.apply))

  implicit def avectorDecoder[A: ClassTag](implicit decoder: Decoder[A]): Decoder[AVector[A]] =
    Decoder.decodeArray[A].map(AVector.unsafe(_))
  implicit def avectorCodec[A: ClassTag](implicit encoder: Encoder[A],
                                         decoder: Decoder[A]): Codec[AVector[A]] = {
    Codec.from(avectorDecoder[A], avectorEncoder[A])
  }
  private val byteStringEncoder: Encoder[ByteString] =
    (bs: ByteString) => Json.fromString(Hex.toHexString(bs))

  val byteStringDecoder: Decoder[ByteString] =
    Decoder.decodeString.emap { bs =>
      Hex.from(bs).toRight(s"Invalid hex string: $bs")
    }

  implicit val byteStringCodec: Codec[ByteString] =
    Codec.from(byteStringDecoder, byteStringEncoder)

  implicit val inetAddressCodec: Codec[InetAddress] = {
    codecXemap[String, InetAddress](parseInetAddress, _.getHostAddress)
  }

  implicit val u256Encoder: Encoder[U256] = Encoder.encodeJavaBigInteger.contramap[U256](_.toBigInt)
  implicit val u256Decoder: Decoder[U256] = Decoder.decodeJavaBigInteger.emap { u64 =>
    U256.from(u64).toRight(s"Invalid U256: $u64")
  }
  implicit val u64Codec: Codec[U256] = Codec.from(u256Decoder, u256Encoder)

  implicit val mnemonicSizeEncoder: Encoder[Mnemonic.Size] =
    Encoder.encodeInt.contramap[Mnemonic.Size](_.value)
  implicit val mnemonicSizeDecoder: Decoder[Mnemonic.Size] = Decoder.decodeInt.emap { size =>
    Mnemonic
      .Size(size)
      .toRight(
        s"Invalid mnemonic size: $size, expected: ${Mnemonic.Size.list.map(_.value).mkString(", ")}")
  }
  implicit val mnemonicSizeCodec: Codec[Mnemonic.Size] =
    Codec.from(mnemonicSizeDecoder, mnemonicSizeEncoder)

  private def parseInetAddress(inetAddressStr: String): Either[String, InetAddress] =
    Try(InetAddress.getByName(inetAddressStr)).toEither.left.map(_.getMessage)

  private def codecXemap[T, U](to: T => Either[String, U], from: U => T)(
      implicit encoderT: Encoder[T],
      decoderT: Decoder[T]): Codec[U] = {
    val encoder = encoderT.contramap(from)
    val decoder = decoderT.emap(to)
    Codec.from(decoder, encoder)
  }
}
