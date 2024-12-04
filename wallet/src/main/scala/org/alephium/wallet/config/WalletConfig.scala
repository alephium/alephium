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

import scala.util.Try

import com.typesafe.config.ConfigException
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader
import sttp.model.Uri

import org.alephium.api.model.ApiKey
import org.alephium.conf._
import org.alephium.util.{AVector, Duration}

final case class WalletConfig(
    port: Option[Int],
    secretDir: Path,
    lockingTimeout: Duration,
    apiKey: AVector[ApiKey],
    enableHttpMetrics: Boolean,
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

  implicit private val apiValueReader: ValueReader[ApiKey] =
    ValueReader[String].map { input =>
      ApiKey.from(input) match {
        case Right(apiKey) => apiKey
        case Left(error)   => throw new ConfigException.BadValue("ApiKey", error)
      }
    }

  implicit val walletConfigReader: ValueReader[WalletConfig] =
    valueReader { implicit cfg =>
      def apiKeys = Try(as[AVector[ApiKey]]("apiKey")).getOrElse(
        AVector.from(as[Option[ApiKey]]("apiKey"))
      )

      WalletConfig(
        as[Option[Int]]("port"),
        as[Path]("secretDir"),
        as[Duration]("lockingTimeout"),
        apiKeys,
        as[Option[Boolean]]("enableHttpMetrics").getOrElse(false),
        as[WalletConfig.BlockFlow]("blockflow")
      )
    }
}
