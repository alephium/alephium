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

package org.alephium.wallet.tapir

import io.circe
import io.circe.syntax._
import sttp.tapir.{Codec, DecodeResult}
import sttp.tapir.CodecFormat.TextPlain

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.Hash
import org.alephium.protocol.model.Address
import org.alephium.util.U256
import org.alephium.wallet.circe.ProtocolCodecs

trait Codecs extends ProtocolCodecs {

  implicit val addressTapirCodec: Codec[String, Address, TextPlain] =
    fromCirce[Address]

  implicit val hashTapirCodec: Codec[String, Hash, TextPlain] =
    fromCirce[Hash]

  implicit val mnemonicSizeTapirCodec: Codec[String, Mnemonic.Size, TextPlain] =
    fromCirce[Mnemonic.Size]

  implicit val u256TapirCodec: Codec[String, U256, TextPlain] =
    fromCirce[U256]

  private def fromCirce[A: circe.Codec]: Codec[String, A, TextPlain] =
    Codec.string.mapDecode[A] { raw =>
      raw.asJson.as[A] match {
        case Right(a) => DecodeResult.Value(a)
        case Left(error) =>
          DecodeResult.Error(raw, new IllegalArgumentException(error.getMessage))
      }
    }(_.asJson.toString)
}
