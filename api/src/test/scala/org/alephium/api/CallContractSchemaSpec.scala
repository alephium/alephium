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

package org.alephium.api

import scala.io.Source
import scala.util.Using

import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import org.alephium.api.model.ApiKey
import org.alephium.protocol.config.GroupConfig
import org.alephium.util.{AlephiumSpec, AVector}

class CallContractSchemaSpec extends AlephiumSpec {
  "Contract call OpenAPI" should "match the published request and optional response schemas" in {
    val endpoints = new Endpoints {
      override val apiKeys: AVector[ApiKey] = AVector.empty
      implicit override val groupConfig: GroupConfig = new GroupConfig {
        override def groups: Int = 4
      }
    }
    val openApi = OpenAPIDocsInterpreter().toOpenAPI(
      List(endpoints.callContract.endpoint, endpoints.multiCallContract.endpoint),
      "Contract calls",
      "1"
    )
    val generated = ujson.read(
      OpenAPIWriters.openApiJson(openApi, dropAuth = true, truncateAddresses = true)
    )("components")("schemas")
    val published = Using.resource(Source.fromResource("openapi.json")) { source =>
      ujson.read(source.mkString)("components")("schemas")
    }
    Seq(
      "CallContract",
      "CallContractSucceeded",
      "CallContractFailed",
      "CallContractResult",
      "MultipleCallContract",
      "MultipleCallContractResult"
    ).foreach { name =>
      generated(name) is published(name)
    }
  }
}
