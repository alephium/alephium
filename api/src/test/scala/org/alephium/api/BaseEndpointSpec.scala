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

import org.alephium.api.BaseEndpoint.checkApiKey
import org.alephium.api.model.ApiKey
import org.alephium.protocol.Hash
import org.alephium.util.{AlephiumSpec, AVector}

class BaseEndpointSpec extends AlephiumSpec {
  "BaseEndpoint.checkApiKey" should "check api key" in {
    val apiKeys     = AVector.fill(3)(Hash.generate.toHexString).map(ApiKey.unsafe(_))
    val wrongApiKey = ApiKey.unsafe(Hash.generate.toHexString)

    checkApiKey(AVector.empty, None).isRight is true
    checkApiKey(
      AVector.empty,
      apiKeys.headOption
    ).leftValue.detail is "Api key not configured in server"
    checkApiKey(apiKeys, None).leftValue.detail is "Missing api key"
    checkApiKey(apiKeys, apiKeys.shuffle().headOption).isRight is true
    checkApiKey(apiKeys, Some(wrongApiKey)).leftValue.detail is "Wrong api key"
  }
}
