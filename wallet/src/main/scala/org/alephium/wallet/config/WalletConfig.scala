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

import akka.http.scaladsl.model.Uri
import com.typesafe.config.ConfigException
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader

import org.alephium.conf._
import org.alephium.protocol.model.NetworkType
import org.alephium.util.Duration

final case class WalletConfig(
    port: Option[Int],
    secretDir: Path,
    networkType: NetworkType,
    lockingTimeout: Duration,
    blockflow: WalletConfig.BlockFlow
)

object WalletConfig {
  final case class BlockFlow(host: String, port: Int, groups: Int, blockflowFetchMaxAge: Duration) {
    val uri: Uri = Uri(s"http://$host:$port")
  }

  implicit val networkTypeReader: ValueReader[NetworkType] = ValueReader[String].map { name =>
    NetworkType
      .fromName(name)
      .getOrElse(throw new ConfigException.BadValue("", s"invalid network type: $name"))
  }

  implicit val walletConfigReader: ValueReader[WalletConfig] =
    valueReader { implicit cfg =>
      WalletConfig(
        as[Option[Int]]("port"),
        as[Path]("secretDir"),
        as[NetworkType]("networkType"),
        as[Duration]("lockingTimeout"),
        as[WalletConfig.BlockFlow]("blockflow")
      )
    }
}
