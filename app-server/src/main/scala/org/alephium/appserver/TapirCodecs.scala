package org.alephium.appserver

import sttp.tapir.{Codec, DecodeResult, Validator}
import sttp.tapir.CodecFormat.TextPlain

import org.alephium.protocol.ALF.Hash
import org.alephium.util.{Hex, TimeStamp}

object TapirCodecs {
  implicit val timestampCodec: Codec[String, TimeStamp, TextPlain] =
    Codec.long.validate(Validator.min(0L)).map(TimeStamp.unsafe(_))(_.millis)

  implicit val hashCodec: Codec[String, Hash, TextPlain] =
    Codec.string.mapDecode[Hash] { raw =>
      Hex.from(raw).flatMap(Hash.from) match {
        case Some(hash) => DecodeResult.Value(hash)
        case None       => DecodeResult.Error(raw, new IllegalArgumentException("cannot decode hash"))
      }
    }(_.toHexString)

}
