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

import scala.collection.immutable.ArraySeq
import scala.concurrent._

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import io.vertx.core.Vertx
import io.vertx.core.http.{HttpMethod, HttpServer, HttpServerOptions}
import io.vertx.ext.web._
import io.vertx.ext.web.handler.CorsHandler
import sttp.tapir.server.vertx.VertxFutureServerInterpreter
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.api.OpenAPIWriters.openApiJson
import org.alephium.app.ws.WsServer
import org.alephium.flow.client.Node
import org.alephium.flow.mining.Miner
import org.alephium.flow.setting.NetworkSetting
import org.alephium.http.{EndpointSender, ServerOptions, SwaggerUI}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.NetworkId
import org.alephium.util._
import org.alephium.wallet.web.WalletServer

class RestServer(
    val node: Node,
    val port: Int,
    val miner: ActorRefT[Miner.Command],
    val blocksExporter: BlocksExporter,
    val httpServer: HttpServerLike,
    val walletServer: Option[WalletServer]
)(implicit
    val brokerConfig: BrokerConfig,
    val apiConfig: ApiConfig,
    val executionContext: ExecutionContext
) extends EndpointsLogic
    with Documentation
    with Service
    with VertxFutureServerInterpreter
    with StrictLogging {

  override val vertxFutureServerOptions = ServerOptions.serverOptions(apiConfig.enableHttpMetrics)
  lazy val blockflowFetchMaxAge         = apiConfig.blockflowFetchMaxAge
  val walletEndpoints                   = walletServer.map(_.walletEndpoints).getOrElse(List.empty)

  override val apiKeys = apiConfig.apiKey

  val endpointSender: EndpointSender = new EndpointSender(apiKeys.headOption)

  private val truncateAddresses = node.config.network.networkId == NetworkId.AlephiumMainNet
  private val swaggerUiRoute = SwaggerUI(
    openApiJson(openAPI, apiKeys.isEmpty, truncateAddresses)
  ).map(route(_))

  private val blockFlowRoute: AVector[Router => Route] =
    AVector(
      getNodeInfoLogic,
      getNodeVersionLogic,
      getChainParamsLogic,
      getSelfCliqueLogic,
      getInterCliquePeerInfoLogic,
      getDiscoveredNeighborsLogic,
      getMisbehaviorsLogic,
      misbehaviorActionLogic,
      getUnreachableBrokersLogic,
      discoveryActionLogic,
      getHistoryHashRateLogic,
      getCurrentHashRateLogic,
      getCurrentDifficultyLogic,
      getBlocksLogic,
      getBlocksAndEventsLogic,
      getRichBlocksAndEventsLogic,
      getBlockLogic,
      getMainChainBlockByGhostUncleLogic,
      getBlockAndEventsLogic,
      getRichBlockAndEventsLogic,
      isBlockInMainChainLogic,
      getBalanceLogic,
      getUTXOsLogic,
      getGroupLogic,
      getGroupLocalLogic,
      getHashesAtHeightLogic,
      getChainInfoLogic,
      getBlockHeaderEntryLogic,
      getRawBlockLogic,
      buildTransactionLogic,
      buildTransferFromOneToManyGroupsLogic,
      buildSweepAddressTransactionsLogic,
      submitTransactionLogic,
      buildMultisigAddressLogic,
      buildMultisigLogic,
      buildSweepMultisigLogic,
      buildMultiInputsTransactionLogic,
      submitMultisigTransactionLogic,
      getTransactionStatusLogic,
      getTransactionStatusLocalLogic,
      decodeUnsignedTransactionLogic,
      getTransactionLogic,
      getRichTransactionLogic,
      getRawTransactionLogic,
      listMempoolTransactionsLogic,
      clearMempoolLogic,
      validateMempoolTransactionsLogic,
      rebroadcastMempoolTransactionLogic,
      minerActionLogic,
      mineOneBlockLogic,
      minerListAddressesLogic,
      minerUpdateAddressesLogic,
      compileScriptLogic,
      buildExecuteScriptTxLogic,
      compileContractLogic,
      compileProjectLogic,
      buildDeployContractTxLogic,
      buildChainedTransactionsLogic,
      contractStateLogic,
      contractCodeLogic,
      testContractLogic,
      callContractLogic,
      multipleCallContractLogic,
      parentContractLogic,
      subContractsLogic,
      subContractsCurrentCountLogic,
      getTxIdFromOutputRefLogic,
      callTxScriptLogic,
      exportBlocksLogic,
      verifySignatureLogic,
      checkHashIndexingLogic,
      targetToHashrateLogic,
      getContractEventsLogic,
      getContractEventsCurrentCountLogic,
      getEventsByTxIdLogic,
      getEventsByBlockHashLogic,
      metricsLogic
    ).map(route(_)) ++ swaggerUiRoute

  val routes: AVector[Router => Route] =
    walletServer.map(wallet => wallet.routes).getOrElse(AVector.empty) ++ blockFlowRoute

  override def subServices: ArraySeq[Service] = ArraySeq(node, endpointSender)

  private val vertx  = Vertx.vertx()
  private val router = Router.router(vertx)
  vertx
    .fileSystem()
    .existsBlocking(
      "META-INF/resources/webjars/swagger-ui/"
    ) // Fix swagger ui being not found on the first call

  // scalastyle:off magic.number
  router
    .route()
    .handler(
      CorsHandler
        .create()
        .addRelativeOrigin(".*.")
        .allowedMethod(HttpMethod.GET)
        .allowedMethod(HttpMethod.POST)
        .allowedMethod(HttpMethod.PUT)
        .allowedMethod(HttpMethod.HEAD)
        .allowedMethod(HttpMethod.OPTIONS)
        .allowedHeader("*")
        .allowCredentials(true)
        .maxAgeSeconds(1800)
    )
  // scalastyle:on magic.number

  routes.foreach(route => route(router))

  private val httpBindingPromise: Promise[HttpServer] = Promise()

  protected def startSelfOnce(): Future[Unit] = {
    val address = apiConfig.networkInterface.getHostAddress
    for {
      httpBinding <- httpServer.httpServer
        .requestHandler(router)
        .listen(port, address)
        .asScala
    } yield {
      logger.info(
        s"Listening to rest-http requests including websocket endpoint '/ws' on /$address:${httpBinding.actualPort}"
      )
      httpBindingPromise.success(httpBinding)
    }
  }

  protected def stopSelfOnce(): Future[Unit] =
    for {
      binding <- httpBindingPromise.future
      _       <- binding.close().asScala
    } yield {
      logger.info(s"http unbound")
      ()
    }
}
// scalastyle:off parameter.number magic.number
object RestServer {
  def apply(
      flowSystem: ActorSystem,
      node: Node,
      miner: ActorRefT[Miner.Command],
      blocksExporter: BlocksExporter,
      walletServer: Option[WalletServer]
  )(implicit
      brokerConfig: BrokerConfig,
      apiConfig: ApiConfig,
      networkSetting: NetworkSetting,
      executionContext: ExecutionContext
  ): RestServer = {
    val restPort = node.config.network.restPort
    val httpOptions =
      new HttpServerOptions()
        .setMaxWebSocketFrameSize(networkSetting.wsMaxFrameSize)
        .setRegisterWebSocketWriteHandlers(true)
        .setMaxFormBufferedBytes(apiConfig.maxFormBufferedBytes)
    val webSocketServer =
      WsServer(
        flowSystem,
        node,
        networkSetting.wsMaxConnections,
        networkSetting.wsMaxSubscriptionsPerConnection,
        networkSetting.wsMaxContractEventAddresses,
        networkSetting.wsPingFrequency,
        httpOptions
      )
    new RestServer(node, restPort, miner, blocksExporter, webSocketServer, walletServer)
  }
}
// scalastyle:on parameter.number magic.number
