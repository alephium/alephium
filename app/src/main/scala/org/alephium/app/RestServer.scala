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
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.typesafe.scalalogging.StrictLogging
import sttp.tapir.docs.openapi.RichOpenAPIEndpoints
import sttp.tapir.openapi.OpenAPI
import sttp.tapir.openapi.circe.yaml.RichOpenAPI
import sttp.tapir.server.akkahttp._
import sttp.tapir.swagger.akkahttp.SwaggerAkka

import org.alephium.api.Endpoints
import org.alephium.api.model._
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.TxHandler
import org.alephium.flow.network.{Bootstrapper, CliqueManager, InterCliqueManager}
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.protocol.config.{ChainsConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector, Duration, Service}
import org.alephium.wallet.web.WalletServer

// scalastyle:off method.length
class RestServer(
    node: Node,
    port: Int,
    miner: ActorRefT[Miner.Command],
    walletServer: Option[WalletServer]
)(implicit val apiConfig: ApiConfig,
  val actorSystem: ActorSystem,
  val executionContext: ExecutionContext)
    extends Endpoints
    with Service
    with StrictLogging {

  private val blockFlow: BlockFlow                    = node.blockFlow
  private val txHandler: ActorRefT[TxHandler.Command] = node.allHandlers.txHandler
  private val terminationHardDeadline                 = Duration.ofSecondsUnsafe(10).asScala
  lazy val blockflowFetchMaxAge                       = apiConfig.blockflowFetchMaxAge

  implicit val groupConfig: GroupConfig   = node.config.broker
  implicit val chainsConfig: ChainsConfig = node.config.chains
  implicit val networkType: NetworkType   = node.config.chains.networkType
  implicit val askTimeout: Timeout        = Timeout(apiConfig.askTimeout.asScala)

  private val getSelfCliqueRoute = getSelfClique.toRoute { _ =>
    node.bootstrapper.ask(Bootstrapper.GetIntraCliqueInfo).mapTo[IntraCliqueInfo].map {
      cliqueInfo =>
        Right(RestServer.selfCliqueFrom(cliqueInfo))
    }
  }

  private val getSelfCliqueSyncedRoute = getSelfCliqueSynced.toRoute { _ =>
    node.cliqueManager.ask(CliqueManager.IsSelfCliqueReady).mapTo[Boolean].map(Right(_))
  }

  private val getInterCliquePeerInfoRoute = getInterCliquePeerInfo.toRoute { _ =>
    node.cliqueManager
      .ask(InterCliqueManager.GetSyncStatuses)
      .mapTo[Seq[InterCliqueManager.SyncStatus]]
      .map { syncedStatuses =>
        Right(AVector.from(syncedStatuses.map(RestServer.inteCliquePeerInfoFrom)))
      }
  }

  private val getBlockflowRoute = getBlockflow.toRoute { timeInterval =>
    Future.successful(
      ServerUtils.getBlockflow(blockFlow, FetchRequest(timeInterval.from, timeInterval.to)))
  }

  private val getBlockRoute = getBlock.toRoute { hash =>
    Future.successful(ServerUtils.getBlock(blockFlow, GetBlock(hash)))
  }

  private val getBalanceRoute = getBalance.toRoute { address =>
    Future.successful(ServerUtils.getBalance(blockFlow, GetBalance(address)))
  }

  private val getGroupRoute = getGroup.toRoute { address =>
    Future.successful(ServerUtils.getGroup(blockFlow, GetGroup(address)))
  }

  private val getHashesAtHeightRoute = getHashesAtHeight.toRoute {
    case (from, to, height) =>
      Future.successful(
        ServerUtils.getHashesAtHeight(blockFlow,
                                      ChainIndex(from, to),
                                      GetHashesAtHeight(from.value, to.value, height)))
  }

  private val getChainInfoRoute = getChainInfo.toRoute {
    case (from, to) =>
      Future.successful(ServerUtils.getChainInfo(blockFlow, ChainIndex(from, to)))
  }

  private val listUnconfirmedTransactionsRoute = listUnconfirmedTransactions.toRoute {
    case (from, to) =>
      Future.successful(ServerUtils.listUnconfirmedTransactions(blockFlow, ChainIndex(from, to)))
  }

  private val buildTransactionRoute = buildTransaction.toRoute {
    case (fromKey, toAddress, value) =>
      Future.successful(
        ServerUtils.buildTransaction(blockFlow, BuildTransaction(fromKey, toAddress, value)))
  }

  private val sendTransactionLogic = sendTransaction.serverLogic { transaction =>
    ServerUtils.sendTransaction(txHandler, transaction)
  }

  private val minerActionLogic = minerAction.serverLogic {
    case MinerAction.StartMining => ServerUtils.execute(miner ! Miner.Start)
    case MinerAction.StopMining  => ServerUtils.execute(miner ! Miner.Stop)
  }

  private val sendContractRoute = sendContract.toRoute { query =>
    ServerUtils.sendContract(txHandler, query)
  }

  private val buildContractRoute = buildContract.toRoute { query =>
    ServerUtils.buildContract(blockFlow, query)
  }

  private val compileRoute = compile.toRoute { query =>
    ServerUtils.compile(query)
  }

  private val walletDocs = walletServer.map(_.docs).getOrElse(List.empty)
  private val blockflowDocs = List(
    getSelfClique,
    getSelfCliqueSynced,
    getInterCliquePeerInfo,
    getBlockflow,
    getBlock,
    getBalance,
    getGroup,
    getHashesAtHeight,
    getChainInfo,
    listUnconfirmedTransactions,
    buildTransaction,
    sendTransactionLogic.endpoint,
    sendContract,
    compile,
    buildContract,
    minerActionLogic.endpoint
  )

  private val docs: OpenAPI =
    (walletDocs ++ blockflowDocs).toOpenAPI("Alephium API", "1.0")

  private val swaggerUIRoute = new SwaggerAkka(docs.toYaml, yamlName = "openapi.yaml").routes

  private val blockFlowRoute: Route =
    getSelfCliqueRoute ~
      getSelfCliqueSyncedRoute ~
      getInterCliquePeerInfoRoute ~
      getBlockflowRoute ~
      getBlockRoute ~
      getBalanceRoute ~
      getGroupRoute ~
      getHashesAtHeightRoute ~
      getChainInfoRoute ~
      listUnconfirmedTransactionsRoute ~
      buildTransactionRoute ~
      sendTransactionLogic.toRoute ~
      minerActionLogic.toRoute ~
      sendContractRoute ~
      compileRoute ~
      buildContractRoute ~
      swaggerUIRoute

  val route: Route =
    cors()(
      walletServer.map(wallet => blockFlowRoute ~ wallet.route).getOrElse(blockFlowRoute)
    )

  private val httpBindingPromise: Promise[Http.ServerBinding] = Promise()

  override def subServices: ArraySeq[Service] = ArraySeq(node)

  protected def startSelfOnce(): Future[Unit] = {
    for {
      httpBinding <- Http()
        .bindAndHandle(route, apiConfig.networkInterface.getHostAddress, port)
    } yield {
      logger.info(s"Listening http request on $httpBinding")
      httpBindingPromise.success(httpBinding)
    }
  }

  protected def stopSelfOnce(): Future[Unit] =
    for {
      httpBinding <- httpBindingPromise.future
      httpStop    <- httpBinding.terminate(hardDeadline = terminationHardDeadline)
    } yield {
      logger.info(s"http unbound with message $httpStop.")
      ()
    }
}

object RestServer {
  def apply(node: Node, miner: ActorRefT[Miner.Command], walletServer: Option[WalletServer])(
      implicit system: ActorSystem,
      apiConfig: ApiConfig,
      executionContext: ExecutionContext): RestServer = {
    val restPort = node.config.network.restPort
    new RestServer(node, restPort, miner, walletServer)
  }
  def selfCliqueFrom(cliqueInfo: IntraCliqueInfo): SelfClique = {
    SelfClique(
      cliqueInfo.id,
      cliqueInfo.peers.map(peer =>
        PeerAddress(peer.internalAddress.getAddress, peer.rpcPort, peer.restPort, peer.wsPort)),
      cliqueInfo.groupNumPerBroker
    )
  }

  def inteCliquePeerInfoFrom(syncStatus: InterCliqueManager.SyncStatus): InterCliquePeerInfo = {
    val peerId = syncStatus.peerId
    InterCliquePeerInfo(peerId.cliqueId, peerId.brokerId, syncStatus.address, syncStatus.isSynced)
  }
}
