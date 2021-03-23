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

import java.net.{InetAddress, InetSocketAddress}

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration

import pureconfig.ConfigReader
import pureconfig.error._

import org.alephium.crypto.Sha256
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Address, GroupIndex, NetworkType}
import org.alephium.util.{AVector, Duration, Hex}

object PureConfigUtils {
  implicit val durationConfig: ConfigReader[Duration] =
    ConfigReader[FiniteDuration].emap { dt =>
      val millis = dt.toMillis
      if (millis >= 0) {
        Right(Duration.ofMillisUnsafe(millis))
      } else {
        Left(CannotConvert(dt.toString, "alephium Duration", "negative duration"))
      }
    }

  private def parseHost(unparsedHost: String): Option[InetAddress] = {
    val hostAndPort = """([a-zA-Z0-9\.\-]+)\s*""".r
    unparsedHost match {
      case hostAndPort(host) =>
        Some(InetAddress.getByName(host))
      case _ =>
        None
    }
  }

  private def parseHostAndPort(unparsedHostAndPort: String): Option[InetSocketAddress] = {
    val hostAndPort = """([a-zA-Z0-9\.\-]+)\s*:\s*(\d+)""".r
    unparsedHostAndPort match {
      case hostAndPort(host, port) =>
        Some(new InetSocketAddress(host, port.toInt))
      case _ =>
        None
    }
  }
  implicit val inetSocketAddressConfig: ConfigReader[InetSocketAddress] =
    ConfigReader[String].emap { addressInput =>
      parseHostAndPort(addressInput).toRight(
        CannotConvert(addressInput, "InetSocketAddress", "oops")
      )
    }

  implicit val inetAddressConfig: ConfigReader[InetAddress] =
    ConfigReader[String].emap { input =>
      parseHost(input).toRight(
        CannotConvert(input, "InetAddress", "oops")
      )
    }

  implicit val sha256Config: ConfigReader[Sha256] =
    ConfigReader[String].emap { hashInput =>
      Hex
        .from(hashInput)
        .flatMap(Sha256.from)
        .toRight(CannotConvert(hashInput, "Sha256", "oops"))
    }

  private val bootstrapStringReader = ConfigReader[String].map(_.split(",")).emap {
    case Array(empty) if empty == "" => Right(ArraySeq.empty)
    case inputs =>
      val result = inputs.flatMap(parseHostAndPort)
      Either.cond(
        result.size == inputs.size,
        ArraySeq.from(result),
        CannotConvert(inputs.mkString(", "), "ArraySeq[InetAddress]", "oops")
      )
  }

  //We can't put explicitly the type, otherwise the automatic derivation of `pureconfig` fail
  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  implicit val bootstrapReader =
    ConfigReader[ArraySeq[InetSocketAddress]].orElse(bootstrapStringReader)

  def parseMiners(
      minerAddressesOpt: Option[Seq[String]],
      networkType: NetworkType,
      brokerConfig: BrokerConfig
  ): Either[FailureReason, AVector[Address]] = {
    minerAddressesOpt match {
      case Some(minerAddresses) =>
        AVector.from(minerAddresses).mapE(parseAddresses(_, networkType))
      case None => generateMiners(networkType, brokerConfig)
    }
  }

  private def generateMiners(
      networkType: NetworkType,
      brokerConfig: BrokerConfig
  ): Either[FailureReason, AVector[Address]] = {
    networkType match {
      case NetworkType.Mainnet =>
        Left(
          ExceptionThrown(
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
  ): Either[FailureReason, Address] = {
    Address.fromBase58(rawAddress, networkType).toRight {
      CannotConvert(rawAddress, "Address", "Invalid base58 or network-type")
    }
  }
}
