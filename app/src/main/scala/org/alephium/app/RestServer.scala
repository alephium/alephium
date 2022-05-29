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

import com.typesafe.scalalogging.StrictLogging
import io.vertx.core.Vertx
import io.vertx.core.http.{HttpMethod, HttpServer}
import io.vertx.ext.web._
import io.vertx.ext.web.handler.CorsHandler
import sttp.tapir.client.sttp.SttpClientInterpreter
import sttp.tapir.server.vertx.VertxFutureServerInterpreter
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.api.OpenAPIWriters.openApiJson
import org.alephium.flow.client.Node
import org.alephium.flow.mining.Miner
import org.alephium.http.{ServerOptions, SwaggerVertx}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.util._
import org.alephium.wallet.web.WalletServer

// scalastyle:off method.length
class RestServer(
    val node: Node,
    val port: Int,
    val miner: ActorRefT[Miner.Command],
    val blocksExporter: BlocksExporter,
    val walletServer: Option[WalletServer]
)(implicit
    val brokerConfig: BrokerConfig,
    val apiConfig: ApiConfig,
    val executionContext: ExecutionContext
) extends EndpointsLogic
    with Documentation
    with Service
    with VertxFutureServerInterpreter
    with SttpClientInterpreter
    with StrictLogging {

  override val vertxFutureServerOptions = ServerOptions.serverOptions
  lazy val blockflowFetchMaxAge         = apiConfig.blockflowFetchMaxAge
  val walletEndpoints                   = walletServer.map(_.walletEndpoints).getOrElse(List.empty)

  override val maybeApiKey = apiConfig.apiKey

  private val swaggerUiRoute = new SwaggerVertx(openApiJson(openAPI, maybeApiKey.isEmpty)).route(_)

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
      getBlockflowLogic,
      getBlockLogic,
      isBlockInMainChainLogic,
      getBalanceLogic,
      getUTXOsLogic,
      getGroupLogic,
      getGroupLocalLogic,
      getHashesAtHeightLogic,
      getChainInfoLogic,
      getBlockHeaderEntryLogic,
      listUnconfirmedTransactionsLogic,
      buildTransactionLogic,
      buildSweepAddressTransactionsLogic,
      submitTransactionLogic,
      buildMultisigAddressLogic,
      buildMultisigLogic,
      submitMultisigTransactionLogic,
      getTransactionStatusLogic,
      getTransactionStatusLocalLogic,
      decodeUnsignedTransactionLogic,
      minerActionLogic,
      minerListAddressesLogic,
      minerUpdateAddressesLogic,
      compileScriptLogic,
      buildExecuteScriptTxLogic,
      compileContractLogic,
      buildDeployContractTxLogic,
      contractStateLogic,
      testContractLogic,
      exportBlocksLogic,
      verifySignatureLogic,
      checkHashIndexingLogic,
      getContractEventsLogic,
      getContractEventsCurrentCountLogic,
      getEventsByTxIdLogic,
      metricsLogic
    ).map(route(_)) :+ swaggerUiRoute

  val routes: AVector[Router => Route] =
    walletServer.map(wallet => wallet.routes).getOrElse(AVector.empty) ++ blockFlowRoute

  override def subServices: ArraySeq[Service] = ArraySeq(node)

  private val vertx  = Vertx.vertx()
  private val router = Router.router(vertx)
  vertx
    .fileSystem()
    .existsBlocking(
      "META-INF/resources/webjars/swagger-ui/"
    ) // Fix swagger ui being not found on the first call
  private val server = vertx.createHttpServer().requestHandler(router)

  // scalastyle:off magic.number
  router
    .route()
    .handler(
      CorsHandler
        .create(".*.")
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
    for {
      httpBinding <- server.listen(port, apiConfig.networkInterface.getHostAddress).asScala
    } yield {
      logger.info(s"Listening http request on ${httpBinding.actualPort}")
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

object RestServer {
  def apply(
      node: Node,
      miner: ActorRefT[Miner.Command],
      blocksExporter: BlocksExporter,
      walletServer: Option[WalletServer]
  )(implicit
      brokerConfig: BrokerConfig,
      apiConfig: ApiConfig,
      executionContext: ExecutionContext
  ): RestServer = {
    val restPort = node.config.network.restPort
    new RestServer(node, restPort, miner, blocksExporter, walletServer)
  }
}
