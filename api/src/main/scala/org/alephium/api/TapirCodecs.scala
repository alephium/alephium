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
import sttp.tapir.Codec.PlainCodec

import org.alephium.api.model._
import org.alephium.json.Json._
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, BlockHash, GroupIndex, TransactionId}
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util.{TimeStamp, U256}

trait TapirCodecs extends ApiModelCodec {

  implicit val timestampTapirCodec: PlainCodec[TimeStamp] =
    Codec.long.validate(Validator.min(0L)).map(TimeStamp.unsafe(_))(_.millis)

  implicit val hashTapirCodec: PlainCodec[Hash] =
    fromJson[Hash]

  implicit val blockHashTapirCodec: PlainCodec[BlockHash] =
    fromJson[BlockHash]

  implicit val transactionIdCodec: PlainCodec[TransactionId] =
    fromJson[TransactionId]

  implicit val assetAddressTapirCodec: PlainCodec[Address.Asset] =
    fromJson[Address.Asset]

  implicit val contractAddressTapirCodec: PlainCodec[Address.Contract] =
    fromJson[Address.Contract]

  implicit val addressTapirCodec: PlainCodec[Address] =
    fromJson[Address]

  implicit val apiKeyTapirCodec: PlainCodec[ApiKey] =
    fromJson[ApiKey]

  implicit val publicKeyTapirCodec: PlainCodec[PublicKey] =
    fromJson[PublicKey]

  implicit val u256TapirCodec: PlainCodec[U256] =
    fromJson[U256]

  implicit val gasBoxCodec: PlainCodec[GasBox] =
    Codec.int.mapDecode(value =>
      GasBox.from(value) match {
        case Some(gas) => DecodeResult.Value(gas)
        case None => DecodeResult.Error(s"$value", new IllegalArgumentException(s"Invalid gas"))
      }
    )(_.value)

  implicit val gasPriceCodec: PlainCodec[GasPrice] =
    u256TapirCodec.map[GasPrice](GasPrice(_))(_.value)

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.JavaSerializable",
      "org.wartremover.warts.Product",
      "org.wartremover.warts.Serializable"
    )
  ) // Wartremover is complaining, maybe because of tapir macros
  implicit val minerActionTapirCodec: PlainCodec[MinerAction] =
    Codec.derivedEnumeration[String, MinerAction](
      MinerAction.validate,
      MinerAction.write
    )

  implicit val timespanTapirCodec: PlainCodec[TimeSpan] =
    Codec.long.validate(Validator.min(1)).map(TimeSpan(_))(_.millis)

  implicit def groupIndexCodec(implicit
      groupConfig: GroupConfig
  ): PlainCodec[GroupIndex] =
    Codec.int.mapDecode(int =>
      GroupIndex.from(int) match {
        case Some(groupIndex) => DecodeResult.Value(groupIndex)
        case None =>
          DecodeResult.Error(s"$int", new IllegalArgumentException("Invalid group index"))
      }
    )(_.value)

  def fromJson[A: ReadWriter]: PlainCodec[A] =
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
