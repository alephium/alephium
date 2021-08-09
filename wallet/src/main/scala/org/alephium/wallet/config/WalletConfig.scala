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

package org.alephium.wallet.config

import java.nio.file.Path

import com.typesafe.config.ConfigException
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader
import sttp.model.Uri

import org.alephium.api.model.ApiKey
import org.alephium.conf._
import org.alephium.protocol.model.ChainId
import org.alephium.util.Duration

final case class WalletConfig(
    port: Option[Int],
    secretDir: Path,
    chainId: ChainId,
    lockingTimeout: Duration,
    apiKey: Option[ApiKey],
    blockflow: WalletConfig.BlockFlow
)

object WalletConfig {
  final case class BlockFlow(
      host: String,
      port: Int,
      groups: Int,
      blockflowFetchMaxAge: Duration,
      apiKey: Option[ApiKey]
  ) {
    val uri: Uri = Uri(host, port)
  }

  implicit val chainIdReader: ValueReader[ChainId] = ValueReader[Int].map { id =>
    ChainId
      .from(id)
      .getOrElse(
        throw new ConfigException.BadValue("", s"invalid chain id: $id")
      )
  }

  implicit private val apiValueReader: ValueReader[ApiKey] =
    ValueReader[String].map { input =>
      ApiKey.from(input) match {
        case Right(apiKey) => apiKey
        case Left(error)   => throw new ConfigException.BadValue("ApiKey", error)
      }
    }

  implicit val walletConfigReader: ValueReader[WalletConfig] =
    valueReader { implicit cfg =>
      WalletConfig(
        as[Option[Int]]("port"),
        as[Path]("secretDir"),
        as[ChainId]("chainId"),
        as[Duration]("lockingTimeout"),
        as[Option[ApiKey]]("apiKey"),
        as[WalletConfig.BlockFlow]("blockflow")
      )
    }
}
