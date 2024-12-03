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

import sttp.apispec.openapi.{OpenAPI, Server, ServerVariable}
import sttp.tapir.Endpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import org.alephium.api.Endpoints
import org.alephium.protocol.model.ReleaseVersion

trait Documentation extends Endpoints with OpenAPIDocsInterpreter {

  def walletEndpoints: List[Endpoint[_, _, _, _, _]]
  def port: Int

  private lazy val blockflowEndpoints = List(
    getNodeInfo,
    getNodeVersion,
    getChainParams,
    getSelfClique,
    getInterCliquePeerInfo,
    getDiscoveredNeighbors,
    getMisbehaviors,
    misbehaviorAction,
    getUnreachableBrokers,
    discoveryAction,
    getHistoryHashRate,
    getCurrentHashRate,
    getCurrentDifficulty,
    getBlocks,
    getBlocksAndEvents,
    getRichBlocksAndEvents,
    getBlock,
    getMainChainBlockByGhostUncle,
    getBlockAndEvents,
    getRichBlockAndEvents,
    isBlockInMainChain,
    getBalance,
    getUTXOs,
    getGroup,
    getHashesAtHeight,
    getChainInfo,
    getBlockHeaderEntry,
    buildTransferTransaction,
    getRawBlock,
    buildTransferFromOneToManyGroups,
    buildMultiAddressesTransaction,
    buildSweepAddressTransactions,
    submitTransaction,
    decodeUnsignedTransaction,
    getTransaction,
    getRichTransaction,
    getRawTransaction,
    getTransactionStatus,
    getTxIdFromOutputRef,
    listMempoolTransactions,
    rebroadcastMempoolTransaction,
    clearMempool,
    validateMempoolTransactions,
    compileScript,
    buildExecuteScriptTx,
    compileContract,
    compileProject,
    buildDeployContractTx,
    buildChainedTransactions,
    contractState,
    contractCode,
    testContract,
    callContract,
    multiCallContract,
    parentContract,
    subContracts,
    subContractsCurrentCount,
    callTxScript,
    buildMultisigAddress,
    buildMultisig,
    buildSweepMultisig,
    submitMultisigTransaction,
    minerAction,
    mineOneBlock,
    minerListAddresses,
    minerUpdateAddresses,
    getContractEvents,
    getContractEventsCurrentCount,
    getEventsByTxId,
    getEventsByBlockHash,
    verifySignature,
    targetToHashrate,
    checkHashIndexing
  )

  private lazy val servers = List(
    Server("../"),
    Server("{protocol}://{host}:{port}")
      .variables(
        "protocol" -> ServerVariable(Some(List("http", "https")), "http", None),
        "host"     -> ServerVariable(None, "127.0.0.1", None),
        "port"     -> ServerVariable(None, port.toString, None)
      )
  )

  lazy val openAPI: OpenAPI =
    toOpenAPI(
      walletEndpoints ++ blockflowEndpoints.map(_.endpoint),
      "Alephium API",
      ReleaseVersion.current.toString.tail
    )
      .servers(servers)
}
