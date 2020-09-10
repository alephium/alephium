package org.alephium.wallet.circe

import java.net.InetAddress

import scala.util.Try

import _root_.io.circe._
import akka.util.ByteString

import org.alephium.util.Hex

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
