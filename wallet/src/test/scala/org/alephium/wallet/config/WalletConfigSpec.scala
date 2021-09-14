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

import scala.jdk.CollectionConverters._
import scala.util.Try

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import net.ceedubs.ficus.Ficus._

import org.alephium.api.model.ApiKey
import org.alephium.protocol.Hash
import org.alephium.util.AlephiumSpec

class WalletConfigSpec() extends AlephiumSpec {
  it should "load wallet config" in {

    val typesafeConfig: Config = ConfigFactory.load()

    typesafeConfig.as[WalletConfig]("wallet")
  }

  it should "load with api-key" in new Fixture {
    val walletApiKey    = Hash.generate.toHexString
    val blockflowApiKey = Hash.generate.toHexString

    override val configValues =
      Map(("wallet.api-key", walletApiKey), ("wallet.blockflow.api-key", blockflowApiKey))

    val config = typesafeConfig.as[WalletConfig]("wallet")

    config.apiKey.value is ApiKey.unsafe(walletApiKey)
    config.blockflow.apiKey.value is ApiKey.unsafe(blockflowApiKey)
  }

  it should "load without api-key" in new Fixture {
    // scalastyle:off null
    override val configValues = Map(("wallet.api-key", null), ("wallet.blockflow.api-key", null))
    // scalastyle:on null

    val config = typesafeConfig.as[WalletConfig]("wallet")

    config.apiKey is None
    config.blockflow.apiKey is None
  }

  it should "fail to load invalid api-key" in new Fixture {
    override val configValues = Map(("wallet.api-key", "to-short"))

    Try(
      typesafeConfig.as[WalletConfig]("wallet")
    ).toEither.leftValue.getMessage is "Invalid value at 'ApiKey': Api key must have at least 32 characters"
  }

  trait Fixture {

    val configValues: Map[String, Any] = Map.empty

    lazy val typesafeConfig = ConfigFactory
      .parseMap(configValues.view.mapValues(ConfigValueFactory.fromAnyRef).toMap.asJava)
      .withFallback(ConfigFactory.load)
  }
}
