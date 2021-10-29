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

import scala.util.{Failure, Success, Try}

import com.typesafe.config.ConfigException
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader

import org.alephium.crypto.Sha256
import org.alephium.flow.mining.Miner
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, NetworkId}
import org.alephium.util.{AVector, Hex, U256}

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
  )(implicit groupConfig: GroupConfig): Either[ConfigException, Option[AVector[Address.Asset]]] = {
    minerAddressesOpt match {
      case Some(minerAddresses) => parseAddresses(AVector.from(minerAddresses)).map(Option(_))
      case None                 => Right(None)
    }
  }

  private def parseAddresses(
      rawAddresses: AVector[String]
  )(implicit groupConfig: GroupConfig): Either[ConfigException, AVector[Address.Asset]] = {
    rawAddresses.mapE(parseAddress).flatMap { addresses =>
      Miner.validateAddresses(addresses) match {
        case Right(_)    => Right(addresses)
        case Left(error) => Left(new ConfigException.BadValue("minerAddresses", error))
      }
    }
  }

  private def parseAddress(rawAddress: String): Either[ConfigException, Address.Asset] = {
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

  implicit val allocationAmountReader: ValueReader[Allocation.Amount] = ValueReader[String].map {
    input =>
      Try(new java.math.BigInteger(input)) match {
        case Success(bigInt) =>
          Allocation.Amount(
            U256
              .from(bigInt)
              .getOrElse(throw new ConfigException.BadValue("amount", s"Invalid amount: $bigInt"))
          )
        case Failure(_) =>
          Allocation.Amount
            .from(input)
            .getOrElse(throw new ConfigException.BadValue("amount", s"Invalid amount: $input"))
      }
  }
}
