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

import sttp.tapir.Endpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.openapi.{OpenAPI, Server, ServerVariable}

import org.alephium.api.Endpoints

trait Documentation extends Endpoints with OpenAPIDocsInterpreter {

  def walletEndpoints: List[Endpoint[_, _, _, _]]
  def port: Int

  private lazy val blockflowEndpoints = List(
    getNodeInfo,
    getSelfClique,
    getInterCliquePeerInfo,
    getDiscoveredNeighbors,
    getMisbehaviors,
    misbehaviorAction,
    getBlockflow,
    getBlock,
    getBalance,
    getGroup,
    getHashesAtHeight,
    getChainInfo,
    listUnconfirmedTransactions,
    buildTransaction,
    buildSweepAllTransaction,
    submitTransaction,
    decodeUnsignedTransaction,
    getTransactionStatus,
    submitContract,
    compile,
    buildContract,
    minerAction,
    minerListAddresses,
    minerUpdateAddresses
  )

  private lazy val servers = List(
    Server("{protocol}://{host}:{port}")
      .variables(
        "protocol" -> ServerVariable(Some(List("http", "https")), "http", None),
        "host"     -> ServerVariable(None, "127.0.0.1", None),
        "port"     -> ServerVariable(None, port.toString, None)
      )
  )

  lazy val openAPI: OpenAPI =
    toOpenAPI(walletEndpoints ++ blockflowEndpoints.map(_.endpoint), "Alephium API", "1.0")
      .servers(servers)
}
