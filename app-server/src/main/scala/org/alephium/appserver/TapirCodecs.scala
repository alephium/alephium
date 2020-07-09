package org.alephium.appserver

import io.circe
import io.circe.syntax._
import sttp.tapir.{Codec, DecodeResult, Validator}
import sttp.tapir.CodecFormat.TextPlain

import org.alephium.appserver.ApiModel._
import org.alephium.protocol.ALF.Hash
import org.alephium.util.TimeStamp

object TapirCodecs {
  implicit val timestampTapirCodec: Codec[String, TimeStamp, TextPlain] =
    Codec.long.validate(Validator.min(0L)).map(TimeStamp.unsafe(_))(_.millis)

  implicit val hashTapirCodec: Codec[String, Hash, TextPlain] =
    fromCirce[Hash]

  implicit val addressTapirCodec: Codec[String, Address, TextPlain] =
    fromCirce[Address]

  private def fromCirce[A: circe.Codec]: Codec[String, A, TextPlain] =
    Codec.string.mapDecode[A] { raw =>
      raw.asJson.as[A] match {
        case Right(a) => DecodeResult.Value(a)
        case Left(error) =>
          DecodeResult.Error(raw, new IllegalArgumentException(error.getMessage))
      }
    }(_.asJson.toString)

}
