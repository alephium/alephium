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

package org.alephium.wallet.web

import org.scalatest.Inside
import sttp.client3._

import org.alephium.api.Endpoints
import org.alephium.api.model.{Amount, BuildTransaction, Destination}
import org.alephium.http.EndpointSender
import org.alephium.json.Json._
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector, Duration, U256}

class BlockFlowClientSpec() extends AlephiumSpec with Inside {
  it should "correclty create an sttp request" in new Fixture {
    val destinations       = AVector(Destination(toAddress, value, None, None))
    val buildTransactionIn = BuildTransaction(publicKey, destinations, None, None)
    val request = createRequest(buildTransaction, buildTransactionIn, uri"http://127.0.0.1:1234")
    request.uri is uri"http://127.0.0.1:1234/transactions/build"

    inside(request.body) { case body: StringBody =>
      read[BuildTransaction](body.s) is buildTransactionIn
    }
  }

  trait Fixture extends Endpoints with LockupScriptGenerators with EndpointSender {
    implicit val groupConfig: GroupConfig = new GroupConfig { val groups = 4 }
    val groupIndex                        = GroupIndex.unsafe(0)
    val (script, publicKey, _)            = addressGen(groupIndex).sample.get
    val toAddress                         = Address.Asset(script)
    val value                             = Amount(U256.unsafe(1000))
    val blockflowFetchMaxAge              = Duration.unsafe(1000)
    val maybeApiKey                       = None
  }
}
