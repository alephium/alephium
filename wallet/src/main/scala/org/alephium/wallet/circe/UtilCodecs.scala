package org.alephium.wallet.circe

import java.net.InetAddress

import scala.reflect.ClassTag
import scala.util.Try

import _root_.io.circe._
import akka.util.ByteString

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.util.{AVector, Hex, U64}

trait UtilCodecs {

  implicit def avectorEncoder[A: ClassTag](implicit encoder: Encoder[A]): Encoder[AVector[A]] =
    (as: AVector[A]) => Json.fromValues(as.toIterable.map(encoder.apply))

  implicit def avectorDecoder[A: ClassTag](implicit decoder: Decoder[A]): Decoder[AVector[A]] =
    Decoder.decodeArray[A].map(AVector.unsafe)
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

  implicit val u64Encoder: Encoder[U64] = Encoder.encodeJavaBigInteger.contramap[U64](_.toBigInt)

  implicit val u64Decoder: Decoder[U64] = Decoder.decodeJavaBigInteger.emap { u64 =>
    U64.from(u64).toRight(s"Invalid U64: $u64")
  }

  implicit val u64Codec: Codec[U64] = Codec.from(u64Decoder, u64Encoder)

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
