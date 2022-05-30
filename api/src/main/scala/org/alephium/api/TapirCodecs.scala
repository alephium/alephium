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

package org.alephium.api

import scala.util.{Failure, Success, Try}

import sttp.tapir.{Codec, DecodeResult, Validator}
import sttp.tapir.CodecFormat.TextPlain

import org.alephium.api.model._
import org.alephium.json.Json._
import org.alephium.protocol.{BlockHash, Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, GroupIndex}
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util.{TimeStamp, U256}

trait TapirCodecs extends ApiModelCodec {

  implicit val timestampTapirCodec: Codec[String, TimeStamp, TextPlain] =
    Codec.long.validate(Validator.min(0L)).map(TimeStamp.unsafe(_))(_.millis)

  implicit val hashTapirCodec: Codec[String, Hash, TextPlain] =
    fromJson[Hash]

  implicit val blockHashTapirCodec: Codec[String, BlockHash, TextPlain] =
    fromJson[BlockHash]

  implicit val assetAddressTapirCodec: Codec[String, Address.Asset, TextPlain] =
    fromJson[Address.Asset]

  implicit val contractAddressTapirCodec: Codec[String, Address.Contract, TextPlain] =
    fromJson[Address.Contract]

  implicit val addressTapirCodec: Codec[String, Address, TextPlain] =
    fromJson[Address]

  implicit val apiKeyTapirCodec: Codec[String, ApiKey, TextPlain] =
    fromJson[ApiKey]

  implicit val publicKeyTapirCodec: Codec[String, PublicKey, TextPlain] =
    fromJson[PublicKey]

  implicit val u256TapirCodec: Codec[String, U256, TextPlain] =
    fromJson[U256]

  implicit val gasBoxCodec: Codec[String, GasBox, TextPlain] =
    Codec.int.mapDecode(value =>
      GasBox.from(value) match {
        case Some(gas) => DecodeResult.Value(gas)
        case None => DecodeResult.Error(s"$value", new IllegalArgumentException(s"Invalid gas"))
      }
    )(_.value)

  implicit val gasPriceCodec: Codec[String, GasPrice, TextPlain] =
    u256TapirCodec.map[GasPrice](GasPrice(_))(_.value)

  implicit val minerActionTapirCodec: Codec[String, MinerAction, TextPlain] =
    fromJson[MinerAction]

  implicit val timespanTapirCodec: Codec[String, TimeSpan, TextPlain] =
    Codec.long.validate(Validator.min(1)).map(TimeSpan(_))(_.millis)

  implicit def groupIndexCodec(implicit
      groupConfig: GroupConfig
  ): Codec[String, GroupIndex, TextPlain] =
    Codec.int.mapDecode(int =>
      GroupIndex.from(int) match {
        case Some(groupIndex) => DecodeResult.Value(groupIndex)
        case None =>
          DecodeResult.Error(s"$int", new IllegalArgumentException("Invalid group index"))
      }
    )(_.value)

  def fromJson[A: ReadWriter]: Codec[String, A, TextPlain] =
    Codec.string.mapDecode[A] { raw =>
      Try(read[A](ujson.Str(raw))) match {
        case Success(a) => DecodeResult.Value(a)
        case Failure(error) =>
          DecodeResult.Error(raw, new IllegalArgumentException(error.getMessage))
      }
    } { a =>
      writeJs(a) match {
        case ujson.Str(str) => str
        case other          => write(other)
      }
    }
}
