package org.alephium.wallet.circe

import java.net.InetAddress

import scala.util.Try

import _root_.io.circe._
import akka.util.ByteString

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.util.{Hex, U64}

trait UtilCodecs {

  private val byteStringEncoder: Encoder[ByteString] =
    (bs: ByteString) => Json.fromString(Hex.toHexString(bs))

  private val byteStringDecoder: Decoder[ByteString] =
    (c: HCursor) => c.as[String].map(Hex.unsafe)

  implicit val byteStringCodec: Codec[ByteString] =
    Codec.from(byteStringDecoder, byteStringEncoder)

  implicit val inetAddressCodec: Codec[InetAddress] = {
    codecXemap[String, InetAddress](parseInetAddress, _.getHostAddress)
  }

  implicit val u64Encoder: Encoder[U64] = Encoder.encodeLong.contramap[U64](_.v)
  implicit val u64Decoder: Decoder[U64] =
    Decoder.decodeLong.emap(value => U64.from(value).toRight(s"Invalid amount: $value"))
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
