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

import scala.concurrent.duration.FiniteDuration

import akka.http.scaladsl.model.Uri
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto._

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

  implicit val durationConfig: ConfigReader[Duration] =
    ConfigReader[FiniteDuration].emap { dt =>
      val millis = dt.toMillis
      if (millis >= 0) {
        Right(Duration.ofMillisUnsafe(millis))
      } else {
        Left(CannotConvert(dt.toString, "alephium Duration", "negative duration"))
      }
    }

  object BlockFlow {
    implicit val blockFlowReader: ConfigReader[BlockFlow] = deriveReader[BlockFlow]
  }
  implicit val walletConfigReader: ConfigReader[WalletConfig] = deriveReader[WalletConfig]

}
