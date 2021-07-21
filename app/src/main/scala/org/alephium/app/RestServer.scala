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

import java.io.{StringWriter, Writer}

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent._

import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import io.vertx.core.Vertx
import io.vertx.core.http.{HttpMethod, HttpServer}
import io.vertx.ext.web._
import io.vertx.ext.web.handler.CorsHandler
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend
import sttp.model.{StatusCode, Uri}
import sttp.tapir.client.sttp.SttpClientInterpreter
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._
import sttp.tapir.server.vertx.VertxFutureServerInterpreter.{route => toRoute}

import org.alephium.api.{ApiError, Endpoints}
import org.alephium.api.OpenAPIWriters.openApiJson
import org.alephium.api.model._
import org.alephium.app.ServerUtils.FutureTry
import org.alephium.flow.client.Node
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{TxHandler, ViewHandler}
import org.alephium.flow.mining.Miner
import org.alephium.flow.model.MiningBlob
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, InterCliqueManager}
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.network.broker.MisbehaviorManager.Peers
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.http.{ServerOptions, SwaggerVertx}
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde._
import org.alephium.util._
import org.alephium.wallet.web.WalletServer

// scalastyle:off method.length
class RestServer(
    node: Node,
    val port: Int,
    miner: ActorRefT[Miner.Command],
    blocksExporter: BlocksExporter,
    walletServer: Option[WalletServer]
)(implicit
    val brokerConfig: BrokerConfig,
    val apiConfig: ApiConfig,
    val executionContext: ExecutionContext
) extends Endpoints
    with Documentation
    with Service
    with ServerOptions
    with SttpClientInterpreter
    with StrictLogging {

  private val blockFlow: BlockFlow                        = node.blockFlow
  private val txHandler: ActorRefT[TxHandler.Command]     = node.allHandlers.txHandler
  private val viewHandler: ActorRefT[ViewHandler.Command] = node.allHandlers.viewHandler
  lazy val blockflowFetchMaxAge                           = apiConfig.blockflowFetchMaxAge

  implicit val groupConfig: GroupConfig = brokerConfig
  implicit val networkType: NetworkType = node.config.network.networkType
  implicit val askTimeout: Timeout      = Timeout(apiConfig.askTimeout.asScala)

  private val serverUtils: ServerUtils = new ServerUtils(networkType)

  private val backend = AsyncHttpClientFutureBackend()

  private var nodesOpt: Option[AVector[PeerAddress]] = None

  //TODO Do we want to cache the result once it's synced?
  private def withSyncedClique[A](f: => FutureTry[A]): FutureTry[A] = {
    viewHandler.ref
      .ask(InterCliqueManager.IsSynced)
      .mapTo[InterCliqueManager.SyncedResult]
      .flatMap { result =>
        if (result.isSynced) {
          f
        } else {
          Future.successful(Left(ApiError.ServiceUnavailable("The clique is not synced")))
        }
      }
  }

  private def withMinerAddressSet[A](f: => FutureTry[A]): FutureTry[A] = {
    node.allHandlers.viewHandler
      .ask(ViewHandler.GetMinerAddresses)
      .mapTo[Option[AVector[LockupScript]]]
      .flatMap {
        case Some(_) => f
        case None =>
          Future.successful(Left(ApiError.InternalServerError("Miner addresses are not set up")))
      }
  }

  private val getNodeInfoRoute = toRoute(getNodeInfo) { _ =>
    for {
      isMining <- miner.ask(Miner.IsMining).mapTo[Boolean]
    } yield {
      Right(NodeInfo(isMining = isMining))
    }
  }

  private val getSelfCliqueRoute = toRoute(getSelfClique) { _ =>
    fetchSelfClique()
  }

  private val getInterCliquePeerInfoRoute = toRoute(getInterCliquePeerInfo) { _ =>
    node.cliqueManager
      .ask(InterCliqueManager.GetSyncStatuses)
      .mapTo[Seq[InterCliqueManager.SyncStatus]]
      .map { syncedStatuses =>
        Right(AVector.from(syncedStatuses.map(RestServer.interCliquePeerInfoFrom)))
      }
  }

  private val getDiscoveredNeighborsRoute = toRoute(getDiscoveredNeighbors) { _ =>
    node.discoveryServer
      .ask(DiscoveryServer.GetNeighborPeers(None))
      .mapTo[DiscoveryServer.NeighborPeers]
      .map(response => Right(response.peers))
  }

  private val getBlockflowRoute = toRoute(getBlockflow) { timeInterval =>
    //TODO Validation can be moved to the `EndpointInput[TimeInterval]` once
    //we update tapir to 0.18.0
    if (timeInterval.from > timeInterval.to) {
      Future.successful(
        Left(ApiError.BadRequest(s"`fromTs` must be before `toTs`"))
      )
    } else {
      Future.successful(
        serverUtils.getBlockflow(blockFlow, FetchRequest(timeInterval.from, timeInterval.to))
      )
    }
  }

  private val getBlockRoute = toRoute(getBlock) { hash =>
    Future.successful(serverUtils.getBlock(blockFlow, GetBlock(hash)))
  }

  private val getBalanceRoute = toRoute(getBalance) { address =>
    Future.successful(serverUtils.getBalance(blockFlow, GetBalance(address)))
  }

  private val getGroupRoute = toRoute(getGroup) { address =>
    Future.successful(serverUtils.getGroup(GetGroup(address)))
  }

  private val getMisbehaviorsRoute = toRoute(getMisbehaviors) { _ =>
    for {
      brokerPeers <- node.misbehaviorManager.ask(MisbehaviorManager.GetPeers).mapTo[Peers]
    } yield {
      Right(
        brokerPeers.peers.map { case MisbehaviorManager.Peer(addr, misbehavior) =>
          val status: PeerStatus = misbehavior match {
            case MisbehaviorManager.Penalty(value, _) => PeerStatus.Penalty(value)
            case MisbehaviorManager.Banned(until)     => PeerStatus.Banned(until)
          }
          PeerMisbehavior(addr, status)
        }
      )
    }
  }

  private val misbehaviorActionRoute = toRoute(misbehaviorAction) {
    case MisbehaviorAction.Unban(peers) =>
      node.misbehaviorManager ! MisbehaviorManager.Unban(peers)
      node.discoveryServer ! DiscoveryServer.Unban(peers)
      Future.successful(Right(()))
  }

  private val getHashesAtHeightRoute = toRoute(getHashesAtHeight) { case (chainIndex, height) =>
    Future.successful(
      serverUtils.getHashesAtHeight(
        blockFlow,
        chainIndex,
        GetHashesAtHeight(chainIndex.from.value, chainIndex.to.value, height)
      )
    )
  }

  private val getChainInfoRoute = toRoute(getChainInfo) { chainIndex =>
    Future.successful(serverUtils.getChainInfo(blockFlow, chainIndex))
  }

  private val listUnconfirmedTransactionsRoute = toRoute(listUnconfirmedTransactions) {
    chainIndex =>
      Future.successful(serverUtils.listUnconfirmedTransactions(blockFlow, chainIndex))
  }

  private val buildTransactionRoute = toRouteRedirect(buildTransaction)(
    buildTransaction =>
      withSyncedClique {
        Future.successful(
          serverUtils.buildTransaction(
            blockFlow,
            buildTransaction
          )
        )
      },
    bt => LockupScript.p2pkh(bt.fromPublicKey).groupIndex(brokerConfig)
  )

  private val buildSweepAllTransactionRoute = toRouteRedirect(buildSweepAllTransaction)(
    buildSweepAllTransaction =>
      withSyncedClique {
        Future.successful(
          serverUtils.buildSweepAllTransaction(
            blockFlow,
            buildSweepAllTransaction
          )
        )
      },
    bst => LockupScript.p2pkh(bst.fromPublicKey).groupIndex(brokerConfig)
  )

  private val submitTransactionRoute =
    toRouteRedirectWith[SubmitTransaction, TransactionTemplate, TxResult](submitTransaction)(
      tx => serverUtils.createTxTemplate(tx),
      tx =>
        withSyncedClique {
          serverUtils.submitTransaction(txHandler, tx)
        },
      _.fromGroup
    )

  private val getTransactionStatusRoute = toRoute(getTransactionStatus) {
    case (txId, fromGroup, toGroup) =>
      searchTransactionStatus(txId, fromGroup, toGroup)
  }

  private def searchTransactionStatus(
      txId: Hash,
      chainFrom: Option[GroupIndex],
      chainTo: Option[GroupIndex]
  ): Future[ServerUtils.Try[TxStatus]] = {
    (chainFrom, chainTo) match {
      case (Some(from), Some(to)) =>
        Future.successful(
          serverUtils.getTransactionStatus(blockFlow, txId, ChainIndex(from, to))
        )
      case (Some(from), None) =>
        Future.successful(
          searchLocalTransactionStatus(txId, brokerConfig.chainIndexes.filter(_.from == from))
        )
      case (None, Some(to)) =>
        Future.successful(
          searchLocalTransactionStatus(txId, brokerConfig.chainIndexes.filter(_.to == to))
        )
      case (None, None) =>
        searchLocalTransactionStatus(txId, brokerConfig.chainIndexes) match {
          case Right(NotFound) =>
            searchTransactionStatusInOtherNodes(txId)
          case other => Future.successful(other)
        }
    }

  }

  private def searchLocalTransactionStatus(
      txId: Hash,
      chainIndexes: AVector[ChainIndex]
  ): ServerUtils.Try[TxStatus] = {
    @tailrec
    def rec(
        indexes: AVector[ChainIndex],
        currentRes: ServerUtils.Try[TxStatus]
    ): ServerUtils.Try[TxStatus] = {
      if (indexes.isEmpty) {
        currentRes
      } else {
        val index = indexes.head
        val res   = serverUtils.getTransactionStatus(blockFlow, txId, index)
        res match {
          case Right(NotFound) => rec(indexes.tail, res)
          case Right(_)        => res
          case Left(_)         => res
        }
      }
    }
    rec(chainIndexes, Right(NotFound))
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  private def searchTransactionStatusInOtherNodes(txId: Hash): Future[ServerUtils.Try[TxStatus]] = {
    val otherGroupFrom = groupConfig.allGroups.filterNot(brokerConfig.contains)
    if (otherGroupFrom.isEmpty) {
      Future.successful(Right(NotFound))
    } else {
      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      def rec(
          from: GroupIndex,
          remaining: AVector[GroupIndex]
      ): Future[ServerUtils.Try[TxStatus]] = {
        requestFromGroupIndex(
          from,
          Future.successful(Right(NotFound)),
          getTransactionStatus,
          (txId, Some(from), None)
        ).flatMap {
          case Right(NotFound) =>
            if (remaining.isEmpty) {
              Future.successful(Right(NotFound))
            } else {
              rec(remaining.head, remaining.tail)
            }
          case other => Future.successful(other)

        }
      }
      rec(otherGroupFrom.head, otherGroupFrom.tail)
    }
  }

  private val decodeUnsignedTransactionRoute = toRoute(decodeUnsignedTransaction) { tx =>
    Future.successful(
      serverUtils.decodeUnsignedTransaction(tx.unsignedTx).map(Tx.from(_, networkType))
    )
  }

  private val minerActionRoute = toRoute(minerAction) { action =>
    withSyncedClique {
      withMinerAddressSet {
        action match {
          case MinerAction.StartMining => serverUtils.execute(miner ! Miner.Start)
          case MinerAction.StopMining  => serverUtils.execute(miner ! Miner.Stop)
        }
      }
    }
  }

  private val minerListAddressesRoute = toRoute(minerListAddresses) { _ =>
    viewHandler
      .ask(ViewHandler.GetMinerAddresses)
      .mapTo[Option[AVector[LockupScript]]]
      .map {
        case Some(addresses) =>
          Right(MinerAddresses(addresses.map(address => Address(networkType, address))))
        case None => Left(ApiError.InternalServerError(s"Miner addresses are not set up"))
      }
  }

  private val minerUpdateAddressesRoute = toRoute(minerUpdateAddresses) { minerAddresses =>
    Future.successful {
      Miner
        .validateAddresses(minerAddresses.addresses)
        .map(_ => viewHandler ! ViewHandler.UpdateMinerAddresses(minerAddresses.addresses))
        .left
        .map(ApiError.BadRequest(_))
    }
  }

  private val submitContractRoute = toRoute(submitContract) { query =>
    withSyncedClique {
      serverUtils.submitContract(txHandler, query)
    }
  }

  private val buildContractRoute = toRoute(buildContract) { query =>
    serverUtils.buildContract(blockFlow, query)
  }

  private val compileRoute = toRoute(compile) { query => serverUtils.compile(query) }

  private val exportBlocksRoute = toRoute(exportBlocks) { exportFile =>
    //Run the export in background
    Future.successful(
      blocksExporter
        .export(exportFile.filename)
        .left
        .map(error => logger.error(error.getMessage))
    )
    //Just validate the filename and return success
    Future.successful {
      blocksExporter
        .validateFilename(exportFile.filename)
        .map(_ => ())
        .left
        .map(error => ApiError.BadRequest(error.getMessage))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  private val metricsRoute = toRoute(metrics) { _ =>
    Future.successful {
      val writer: Writer = new StringWriter()
      try {
        TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
        Right(writer.toString)
      } catch {
        case error: Throwable =>
          Left(ApiError.InternalServerError(error.getMessage))
      } finally {
        writer.close
      }
    }
  }

  val walletEndpoints = walletServer.map(_.walletEndpoints).getOrElse(List.empty)

  private val swaggerUiRoute = new SwaggerVertx(openApiJson(openAPI)).route

  private val blockFlowRoute: AVector[Router => Route] = AVector(
    getNodeInfoRoute,
    getSelfCliqueRoute,
    getInterCliquePeerInfoRoute,
    getDiscoveredNeighborsRoute,
    getMisbehaviorsRoute,
    misbehaviorActionRoute,
    getBlockflowRoute,
    getBlockRoute,
    getBalanceRoute,
    getGroupRoute,
    getHashesAtHeightRoute,
    getChainInfoRoute,
    listUnconfirmedTransactionsRoute,
    buildTransactionRoute,
    buildSweepAllTransactionRoute,
    submitTransactionRoute,
    getTransactionStatusRoute,
    decodeUnsignedTransactionRoute,
    minerActionRoute,
    minerListAddressesRoute,
    minerUpdateAddressesRoute,
    submitContractRoute,
    compileRoute,
    exportBlocksRoute,
    buildContractRoute,
    metricsRoute,
    swaggerUiRoute
  )

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

  private def fetchSelfClique(): FutureTry[SelfClique] = {
    for {
      selfReady <- node.cliqueManager.ask(CliqueManager.IsSelfCliqueReady).mapTo[Boolean]
      synced <-
        if (selfReady) {
          viewHandler.ref
            .ask(InterCliqueManager.IsSynced)
            .mapTo[InterCliqueManager.SyncedResult]
            .map(_.isSynced)
        } else {
          Future.successful(false)
        }
      cliqueInfo <- node.bootstrapper.ask(Bootstrapper.GetIntraCliqueInfo).mapTo[IntraCliqueInfo]
    } yield {
      val selfClique = RestServer.selfCliqueFrom(
        cliqueInfo,
        node.config.consensus,
        selfReady = selfReady,
        synced = synced
      )
      if (selfReady) {
        nodesOpt = Some(selfClique.nodes)
      }
      Right(
        selfClique
      )
    }
  }

  private def toRouteRedirect[P, A](
      endpoint: BaseEndpoint[P, A]
  )(localLogic: P => Future[Either[ApiError[_ <: StatusCode], A]], getIndex: P => GroupIndex) = {
    toRoute(endpoint) { params =>
      requestFromGroupIndex(
        getIndex(params),
        localLogic(params),
        endpoint,
        params
      )
    }
  }

  private def toRouteRedirectWith[R, P, A](
      endpoint: BaseEndpoint[R, A]
  )(
      paramsConvert: R => ServerUtils.Try[P],
      localLogic: P => Future[Either[ApiError[_ <: StatusCode], A]],
      getIndex: P => GroupIndex
  ) = {
    toRoute(endpoint) { params =>
      paramsConvert(params) match {
        case Left(error) => Future.successful(Left(error))
        case Right(converted) =>
          requestFromGroupIndex(
            getIndex(converted),
            localLogic(converted),
            endpoint,
            params
          )
      }
    }
  }

  private def requestFromGroupIndex[P, A](
      groupIndex: GroupIndex,
      f: => Future[Either[ApiError[_ <: StatusCode], A]],
      endpoint: BaseEndpoint[P, A],
      params: P
  ): Future[Either[ApiError[_ <: StatusCode], A]] =
    serverUtils.checkGroup(groupIndex) match {
      case Right(_) => f
      case Left(_) =>
        uriFromGroup(groupIndex).flatMap {
          case Left(error) => Future.successful(Left(error))
          case Right(uri) =>
            backend
              .send(toRequestThrowDecodeFailures(endpoint, Some(uri)).apply(params))
              .map(_.body)
        }
    }

  private def uriFromGroup(
      fromGroup: GroupIndex
  ): Future[Either[ApiError[_ <: StatusCode], Uri]] =
    nodesOpt match {
      case Some(nodes) =>
        val peer = nodes((fromGroup.value / brokerConfig.groupNumPerBroker) % nodes.length)
        Future.successful(Right(Uri(peer.address.getHostAddress, peer.restPort)))
      case None =>
        fetchSelfClique().map { selfCliqueEither =>
          for {
            selfClique <- selfCliqueEither
          } yield {
            val peer = selfClique.peer(fromGroup)
            Uri(peer.address.getHostAddress, peer.restPort)
          }
        }
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

  def selfCliqueFrom(
      cliqueInfo: IntraCliqueInfo,
      consensus: ConsensusSetting,
      selfReady: Boolean,
      synced: Boolean
  )(implicit brokerConfig: BrokerConfig, networkType: NetworkType): SelfClique = {

    SelfClique(
      cliqueInfo.id,
      networkType,
      consensus.numZerosAtLeastInHash,
      cliqueInfo.peers.map(peer =>
        PeerAddress(peer.internalAddress.getAddress, peer.restPort, peer.wsPort, peer.minerApiPort)
      ),
      selfReady = selfReady,
      synced = synced,
      cliqueInfo.groupNumPerBroker,
      brokerConfig.groups
    )
  }

  def interCliquePeerInfoFrom(syncStatus: InterCliqueManager.SyncStatus): InterCliquePeerInfo = {
    val peerId = syncStatus.peerId
    InterCliquePeerInfo(
      peerId.cliqueId,
      peerId.brokerId,
      syncStatus.groupNumPerBroker,
      syncStatus.address,
      syncStatus.isSynced
    )
  }

  //Cannot do this in `BlockCandidate` as `flow.BlockTemplate` isn't accessible in `api`
  def blockTempateToCandidate(
      chainIndex: ChainIndex,
      template: MiningBlob
  ): BlockCandidate = {
    BlockCandidate(
      fromGroup = chainIndex.from.value,
      toGroup = chainIndex.to.value,
      headerBlob = template.headerBlob,
      target = template.target,
      txsBlob = template.txsBlob
    )
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def blockSolutionToBlock(
      solution: BlockSolution
  ): Either[ApiError[_ <: StatusCode], (Block, U256)] = {
    deserialize[Block](solution.blockBlob) match {
      case Right(block) =>
        Right(block -> solution.miningCount)
      case Left(error) =>
        Left(ApiError.InternalServerError(s"Block deserialization error: ${error.getMessage}"))
    }
  }
}
