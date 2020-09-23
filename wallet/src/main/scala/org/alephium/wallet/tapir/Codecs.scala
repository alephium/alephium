package org.alephium.wallet.tapir

import io.circe
import io.circe.syntax._
import sttp.tapir.{Codec, DecodeResult}
import sttp.tapir.CodecFormat.TextPlain

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.Hash
import org.alephium.protocol.model.Address
import org.alephium.util.U64
import org.alephium.wallet.circe.ProtocolCodecs

trait Codecs extends ProtocolCodecs {

  implicit val addressTapirCodec: Codec[String, Address, TextPlain] =
    fromCirce[Address]

  implicit val hashTapirCodec: Codec[String, Hash, TextPlain] =
    fromCirce[Hash]

  implicit val mnemonicSizeTapirCodec: Codec[String, Mnemonic.Size, TextPlain] =
    fromCirce[Mnemonic.Size]

  implicit val u64TapirCodec: Codec[String, U64, TextPlain] =
    fromCirce[U64]

  private def fromCirce[A: circe.Codec]: Codec[String, A, TextPlain] =
    Codec.string.mapDecode[A] { raw =>
      raw.asJson.as[A] match {
        case Right(a) => DecodeResult.Value(a)
        case Left(error) =>
          DecodeResult.Error(raw, new IllegalArgumentException(error.getMessage))
      }
    }(_.asJson.toString)
}
