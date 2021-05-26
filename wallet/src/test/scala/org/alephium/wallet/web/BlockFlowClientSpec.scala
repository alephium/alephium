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

import sttp.client3._
import sttp.tapir.client.sttp._

import org.alephium.api.Endpoints
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, Duration, U256}

class BlockFlowClientSpec() extends AlephiumSpec {
  it should "correclty create an sttp request" in new Fixture {
    val request = toRequestThrowDecodeFailures(buildTransaction, Some(uri"http://127.0.0.1:1234"))
      .apply((publicKey, toAddress, value, None, None, None))
    request.uri is uri"http://127.0.0.1:1234/transactions/build?fromKey=${publicKey.toHexString}&toAddress=${toAddress.toBase58}&value=${value.v}"
  }

  trait Fixture extends Endpoints with LockupScriptGenerators with SttpClientInterpreter {
    implicit val groupConfig: GroupConfig = new GroupConfig { val groups = 4 }
    val networkType                       = NetworkType.Devnet
    val groupIndex                        = GroupIndex.unsafe(0)
    val (script, publicKey, _)            = addressGen(groupIndex).sample.get
    val toAddress                         = Address(networkType, script)
    val value                             = U256.from(1000).get
    val blockflowFetchMaxAge              = Duration.unsafe(1000)
  }
}
