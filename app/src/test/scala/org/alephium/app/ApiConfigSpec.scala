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

package org.alephium.app

import java.io.File

import scala.jdk.CollectionConverters._

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import org.alephium.util.AlephiumSpec

// scalastyle:off null
class ApiConfigSpec extends AlephiumSpec {
  it should "load api config" in {
    val path   = getClass.getResource(s"/system_test.conf.tmpl").getPath
    val file   = new File(path)
    val config = ConfigFactory.parseFile(file)
    ApiConfig.load(config)
  }

  it should "use defined api key" in new ApiKeyConfigFixture {
    override val apiKeyEnabled = true
    override val apiKey        = "74beb7e20967727763f3c88a1ef596e7b22049047cc6fa8ea27358b32c68377"

    apiConfig.apiKey.get.value is apiKey
  }

  it should "generate api key" in new ApiKeyConfigFixture {
    override val apiKeyEnabled = true
    override val apiKey        = null

    apiConfig.apiKey.isDefined is true
  }

  it should "ignore defined api key if api key is not enabled" in new ApiKeyConfigFixture {
    override val apiKeyEnabled = false
    override val apiKey        = "74beb7e20967727763f3c88a1ef596e7b22049047cc6fa8ea27358b32c68377"

    apiConfig.apiKey.isDefined is false
  }

  trait ApiKeyConfigFixture {
    def apiKey: String
    def apiKeyEnabled: Boolean

    lazy val configValues: Map[String, Any] = Map(
      ("alephium.api.network-interface", "127.0.0.1"),
      ("alephium.api.blockflow-fetch-max-age", "30 minutes"),
      ("alephium.api.ask-timeout", "5 seconds"),
      ("alephium.api.api-key-enabled", apiKeyEnabled),
      ("alephium.api.api-key", apiKey),
      ("alephium.api.gas-fee-cap", "1000000000000000000"),
      ("alephium.api.default-utxos-limit", 512)
    )

    lazy val config = ConfigFactory
      .parseMap(
        configValues.view
          .mapValues(ConfigValueFactory.fromAnyRef)
          .toMap
          .asJava
      )
    lazy val apiConfig = ApiConfig.load(config)
  }
}
