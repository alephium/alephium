package org.alephium.appserver

import io.circe
import io.circe.syntax._
import sttp.tapir.{Codec, DecodeResult, Validator}
import sttp.tapir.CodecFormat.TextPlain

import org.alephium.appserver.ApiModel._
import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.util.{TimeStamp, U64}

object TapirCodecs {
  implicit val timestampTapirCodec: Codec[String, TimeStamp, TextPlain] =
    Codec.long.validate(Validator.min(0L)).map(TimeStamp.unsafe(_))(_.millis)

  implicit val hashTapirCodec: Codec[String, Hash, TextPlain] =
    fromCirce[Hash]

  implicit val addressTapirCodec: Codec[String, Address, TextPlain] =
    fromCirce[Address]

  implicit val apiKeyTapirCodec: Codec[String, ApiKey, TextPlain] =
    fromCirce[ApiKey]

  implicit val publicKeyTapirCodec: Codec[String, ED25519PublicKey, TextPlain] =
    fromCirce[ED25519PublicKey]

  implicit val u64TapirCodec: Codec[String, U64, TextPlain] =
    fromCirce[U64]

  implicit val minerActionTapirCodec: Codec[String, MinerAction, TextPlain] =
    fromCirce[MinerAction]

  implicit def groupIndexCodec(
      implicit groupConfig: GroupConfig): Codec[String, GroupIndex, TextPlain] =
    Codec.int.mapDecode(int =>
      GroupIndex.from(int) match {
        case Some(groupIndex) => DecodeResult.Value(groupIndex)
        case None =>
          DecodeResult.Error(s"$int", new IllegalArgumentException("Invalid group index"))
    })(_.value)

  private def fromCirce[A: circe.Codec]: Codec[String, A, TextPlain] =
    Codec.string.mapDecode[A] { raw =>
      raw.asJson.as[A] match {
        case Right(a) => DecodeResult.Value(a)
        case Left(error) =>
          DecodeResult.Error(raw, new IllegalArgumentException(error.getMessage))
      }
    }(_.asJson.toString)

}
