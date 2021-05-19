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
import scala.util.Try

import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.ext.web._
import io.vertx.ext.web.handler.CorsHandler
import sttp.model.StatusCode
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._
import sttp.tapir.server.vertx.VertxFutureServerInterpreter.{route => toRoute}
import sttp.tapir.swagger.vertx.SwaggerVertx

import org.alephium.api.{ApiError, Endpoints}
import org.alephium.api.OpenAPIWriters.openApiJson
import org.alephium.api.model._
import org.alephium.app.ServerUtils.FutureTry
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.TxHandler
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, InterCliqueManager}
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.network.broker.MisbehaviorManager.Peers
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.http.ServerOptions
import org.alephium.protocol.config.GroupConfig
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
    val apiConfig: ApiConfig,
    val executionContext: ExecutionContext
) extends Endpoints
    with Documentation
    with Service
    with ServerOptions
    with StrictLogging {

  private val blockFlow: BlockFlow                    = node.blockFlow
  private val txHandler: ActorRefT[TxHandler.Command] = node.allHandlers.txHandler
  lazy val blockflowFetchMaxAge                       = apiConfig.blockflowFetchMaxAge

  implicit val groupConfig: GroupConfig = node.config.broker
  implicit val networkType: NetworkType = node.config.network.networkType
  implicit val askTimeout: Timeout      = Timeout(apiConfig.askTimeout.asScala)

  private val serverUtils: ServerUtils = new ServerUtils(networkType)

  //TODO Do we want to cache the result once it's synced?
  private def withSyncedClique[A](f: FutureTry[A]): FutureTry[A] = {
    node.cliqueManager
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

  private val getNodeInfoRoute = toRoute(getNodeInfo) { _ =>
    for {
      isMining <- miner.ask(Miner.IsMining).mapTo[Boolean]
    } yield {
      Right(NodeInfo(isMining = isMining))
    }
  }

  private val getSelfCliqueRoute = toRoute(getSelfClique) { _ =>
    for {
      synced     <- node.cliqueManager.ask(CliqueManager.IsSelfCliqueReady).mapTo[Boolean]
      cliqueInfo <- node.bootstrapper.ask(Bootstrapper.GetIntraCliqueInfo).mapTo[IntraCliqueInfo]
    } yield {
      Right(RestServer.selfCliqueFrom(cliqueInfo, node.config.consensus, synced))
    }
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
      .ask(DiscoveryServer.GetNeighborPeers)
      .mapTo[DiscoveryServer.NeighborPeers]
      .map(response => Right(response.peers))
  }

  private val getBlockflowRoute = toRoute(getBlockflow) { timeInterval =>
    Future.successful(
      serverUtils.getBlockflow(blockFlow, FetchRequest(timeInterval.from, timeInterval.to))
    )
  }

  private val getBlockRoute = toRoute(getBlock) { hash =>
    Future.successful(serverUtils.getBlock(blockFlow, GetBlock(hash)))
  }

  private val getBalanceRoute = toRoute(getBalance) { address =>
    Future.successful(serverUtils.getBalance(blockFlow, GetBalance(address)))
  }

  private val getGroupRoute = toRoute(getGroup) { address =>
    Future.successful(serverUtils.getGroup(blockFlow, GetGroup(address)))
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
      Future.successful(Right(node.misbehaviorManager ! MisbehaviorManager.Unban(peers)))
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

  private val buildTransactionRoute = toRoute(buildTransaction) {
    case (fromKey, toAddress, lockTime, value) =>
      withSyncedClique {
        Future.successful(
          serverUtils.buildTransaction(
            blockFlow,
            BuildTransaction(fromKey, toAddress, lockTime, value)
          )
        )
      }
  }

  private val sendTransactionRoute = toRoute(sendTransaction) { transaction =>
    withSyncedClique {
      serverUtils.sendTransaction(txHandler, transaction)
    }
  }

  private val getTransactionStatusRoute = toRoute(getTransactionStatus) { case (txId, chainIndex) =>
    Future.successful(serverUtils.getTransactionStatus(blockFlow, txId, chainIndex))
  }

  private val minerActionRoute = toRoute(minerAction) { action =>
    withSyncedClique {
      action match {
        case MinerAction.StartMining => serverUtils.execute(miner ! Miner.Start)
        case MinerAction.StopMining  => serverUtils.execute(miner ! Miner.Stop)
      }
    }
  }

  private val minerListAddressesRoute = toRoute(minerListAddresses) { _ =>
    miner
      .ask(Miner.GetAddresses)
      .mapTo[AVector[LockupScript]]
      .map { addresses =>
        Right(MinerAddresses(addresses.map(address => Address(networkType, address))))
      }
  }

  private val minerGetBlockCandidateRoute = toRoute(minerGetBlockCandidate) { chainIndex =>
    withSyncedClique {
      miner
        .ask(Miner.GetBlockCandidate(chainIndex))
        .mapTo[Miner.BlockCandidate]
        .map(_.maybeBlock match {
          case Some(block) => Right(RestServer.blockTempateToCandidate(block))
          case None =>
            Left(
              ApiError.InternalServerError("Cannot compute block candidate for given chain index")
            )
        })
    }
  }

  private val minerNewBlockRoute = toRoute(minerNewBlock) { solution =>
    withSyncedClique {
      Future.successful(
        RestServer.blockSolutionToBlock(solution).map { case (solution, chainIndex, miningCount) =>
          miner ! Miner.NewBlockSolution(
            solution,
            chainIndex,
            miningCount
          )
        }
      )
    }
  }

  private val minerUpdateAddressesRoute = toRoute(minerUpdateAddresses) { minerAddresses =>
    Future.successful {
      Miner
        .validateAddresses(minerAddresses.addresses)
        .map(_ => miner ! Miner.UpdateAddresses(minerAddresses.addresses))
        .left
        .map(ApiError.BadRequest(_))
    }
  }

  private val sendContractRoute = toRoute(sendContract) { query =>
    withSyncedClique {
      serverUtils.sendContract(txHandler, query)
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

  val walletEndpoints = walletServer.map(_.walletEndpoints).getOrElse(List.empty)

  private val swaggerUiRoute =
    new SwaggerVertx(openApiJson(openAPI), yamlName = "openapi.json").route

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
    sendTransactionRoute,
    getTransactionStatusRoute,
    minerActionRoute,
    minerGetBlockCandidateRoute,
    minerNewBlockRoute,
    minerListAddressesRoute,
    minerUpdateAddressesRoute,
    sendContractRoute,
    compileRoute,
    exportBlocksRoute,
    buildContractRoute,
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

  routes.foreach(route => route(router).handler(CorsHandler.create(".*.")))

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
      apiConfig: ApiConfig,
      executionContext: ExecutionContext
  ): RestServer = {
    val restPort = node.config.network.restPort
    new RestServer(node, restPort, miner, blocksExporter, walletServer)
  }

  def selfCliqueFrom(
      cliqueInfo: IntraCliqueInfo,
      consensus: ConsensusSetting,
      synced: Boolean
  )(implicit groupConfig: GroupConfig, networkType: NetworkType): SelfClique = {

    SelfClique(
      cliqueInfo.id,
      networkType,
      consensus.numZerosAtLeastInHash,
      cliqueInfo.peers.map(peer =>
        PeerAddress(peer.internalAddress.getAddress, peer.restPort, peer.wsPort)
      ),
      synced,
      cliqueInfo.groupNumPerBroker,
      groupConfig.groups
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
  def blockTempateToCandidate(template: BlockTemplate): BlockCandidate = {
    BlockCandidate(
      template.deps,
      template.depStateHash,
      template.target.bits,
      template.blockTs,
      template.txsHash,
      template.transactions.map(tx => Hex.toHexString(serialize(tx)))
    )
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def blockSolutionToBlock(
      solution: BlockSolution
  )(implicit
      groupConfig: GroupConfig
  ): Either[ApiError[_ <: StatusCode], (Block, ChainIndex, U256)] = {
    Try {
      val header = BlockHeader.unsafe(
        BlockDeps.build(solution.blockDeps),
        solution.depStateHash,
        solution.txsHash,
        solution.timestamp,
        Target(solution.target),
        solution.nonce
      )
      val transactions =
        solution.transactions.map(tx => deserialize[Transaction](Hex.unsafe(tx)).toOption.get)

      val chainIndex = ChainIndex.unsafe(solution.fromGroup, solution.toGroup)

      (Block(header, transactions), chainIndex, solution.miningCount)
    }.toEither.left.map { error =>
      //TODO improve error handling
      ApiError.BadRequest(error.getMessage)
    }
  }
}
