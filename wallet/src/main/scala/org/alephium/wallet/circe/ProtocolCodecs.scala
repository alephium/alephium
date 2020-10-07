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

package org.alephium.wallet.circe

import io.circe._

import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Address, NetworkType}

trait ProtocolCodecs extends UtilCodecs {

  def networkType: NetworkType

  lazy val addressEncoder: Encoder[Address] =
    Encoder.encodeString.contramap[Address](_.toBase58)
  lazy val addressDecoder: Decoder[Address] =
    Decoder.decodeString.emap { input =>
      Address
        .fromBase58(input, networkType)
        .toRight(s"Unable to decode address from $input")
    }
  implicit lazy val addressCodec: Codec[Address] = Codec.from(addressDecoder, addressEncoder)

  implicit val hashEncoder: Encoder[Hash] = hash => Json.fromString(hash.toHexString)
  implicit val hashDecoder: Decoder[Hash] =
    byteStringDecoder.emap(Hash.from(_).toRight("cannot decode hash"))
  implicit val hashCodec: Codec[Hash] = Codec.from(hashDecoder, hashEncoder)
}
