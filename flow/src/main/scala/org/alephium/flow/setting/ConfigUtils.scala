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

package org.alephium.flow.setting

import com.typesafe.config.ConfigException
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader

import org.alephium.crypto.Sha256
import org.alephium.protocol.model.{Address, NetworkId}
import org.alephium.util.{AVector, Hex}

object ConfigUtils {

  implicit val sha256Config: ValueReader[Sha256] =
    ValueReader[String].map { hashInput =>
      Hex
        .from(hashInput)
        .flatMap(Sha256.from)
        .getOrElse(throw new ConfigException.BadValue("Sha256", "oops"))
    }

  def parseMiners(
      minerAddressesOpt: Option[Seq[String]]
  ): Either[ConfigException, Option[AVector[Address.Asset]]] = {
    minerAddressesOpt match {
      case Some(minerAddresses) =>
        AVector.from(minerAddresses).mapE(parseAddresses).map(Option.apply)
      case None => Right(None)
    }
  }

  private def parseAddresses(rawAddress: String): Either[ConfigException, Address.Asset] = {
    Address.fromBase58(rawAddress) match {
      case Some(address: Address.Asset) => Right(address)
      case Some(_: Address.Contract) =>
        Left(
          new ConfigException.BadValue(
            "address",
            s"Unexpected contract address for miner: $rawAddress"
          )
        )
      case None =>
        Left(
          new ConfigException.BadValue(
            "address",
            s"Invalid base58 encoding: $rawAddress"
          )
        )
    }
  }

  implicit val networkIdReader: ValueReader[NetworkId] = ValueReader[Int].map { id =>
    NetworkId
      .from(id)
      .getOrElse(
        throw new ConfigException.BadValue("", s"invalid chain id: $id")
      )
  }
}
