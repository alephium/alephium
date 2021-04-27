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
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Address, GroupIndex, NetworkType}
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
      minerAddressesOpt: Option[Seq[String]],
      networkType: NetworkType,
      brokerConfig: BrokerConfig
  ): Either[ConfigException, AVector[Address]] = {
    minerAddressesOpt match {
      case Some(minerAddresses) =>
        AVector.from(minerAddresses).mapE(parseAddresses(_, networkType))
      case None => generateMiners(networkType, brokerConfig)
    }
  }

  private def generateMiners(
      networkType: NetworkType,
      brokerConfig: BrokerConfig
  ): Either[ConfigException, AVector[Address]] = {
    networkType match {
      case NetworkType.Mainnet =>
        Left(
          new ConfigException.Missing(
            "miner-addresses",
            new Throwable(s"`miner-addresses` field is mandatory for ${networkType.name}")
          )
        )
      case networkType =>
        Right {
          AVector.tabulate(brokerConfig.groups) { i =>
            val index          = GroupIndex.unsafe(i)(brokerConfig)
            val (_, publicKey) = index.generateKey(brokerConfig)
            Address.p2pkh(networkType, publicKey)
          }
        }
    }
  }

  private def parseAddresses(
      rawAddress: String,
      networkType: NetworkType
  ): Either[ConfigException, Address] = {
    Address.fromBase58(rawAddress, networkType).toRight {
      new ConfigException.BadValue("address", "Invalid base58 or network-type")
    }
  }

  implicit val networkTypeReader: ValueReader[NetworkType] = ValueReader[String].map { name =>
    NetworkType
      .fromName(name)
      .getOrElse(throw new ConfigException.BadValue("", s"invalid network type: $name"))
  }
}
