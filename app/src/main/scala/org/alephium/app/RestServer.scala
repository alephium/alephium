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
import sttp.tapir.openapi.{OpenAPI, Server, ServerVariable}
import sttp.tapir.openapi.circe.yaml.RichOpenAPI
import sttp.tapir.server.akkahttp._
import sttp.tapir.swagger.akkahttp.SwaggerAkka

import org.alephium.api.{ApiModel, Endpoints}
import org.alephium.api.model._
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.TxHandler
import org.alephium.flow.network.{Bootstrapper, CliqueManager, InterCliqueManager}
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{ActorRefT, AVector, Duration, Service}
import org.alephium.wallet.web.WalletServer

// scalastyle:off method.length
class RestServer(
    node: Node,
    port: Int,
    miner: ActorRefT[Miner.Command],
    blocksExporter: BlocksExporter,
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

  implicit val groupConfig: GroupConfig = node.config.broker
  implicit val networkType: NetworkType = node.config.network.networkType
  implicit val askTimeout: Timeout      = Timeout(apiConfig.askTimeout.asScala)

  private val serverUtils: ServerUtils = new ServerUtils(networkType)

  private val getNetworkRoute = getNetwork.toRoute { _ =>
    Future.successful(Right(Network(networkType)))
  }

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
        Right(AVector.from(syncedStatuses.map(RestServer.interCliquePeerInfoFrom)))
      }
  }

  private val getBlockflowRoute = getBlockflow.toRoute { timeInterval =>
    Future.successful(
      serverUtils.getBlockflow(blockFlow, FetchRequest(timeInterval.from, timeInterval.to)))
  }

  private val getBlockRoute = getBlock.toRoute { hash =>
    Future.successful(serverUtils.getBlock(blockFlow, GetBlock(hash)))
  }

  private val getBalanceRoute = getBalance.toRoute { address =>
    Future.successful(serverUtils.getBalance(blockFlow, GetBalance(address)))
  }

  private val getGroupRoute = getGroup.toRoute { address =>
    Future.successful(serverUtils.getGroup(blockFlow, GetGroup(address)))
  }

  private val getHashesAtHeightRoute = getHashesAtHeight.toRoute {
    case (from, to, height) =>
      Future.successful(
        serverUtils.getHashesAtHeight(blockFlow,
                                      ChainIndex(from, to),
                                      GetHashesAtHeight(from.value, to.value, height)))
  }

  private val getChainInfoRoute = getChainInfo.toRoute {
    case (from, to) =>
      Future.successful(serverUtils.getChainInfo(blockFlow, ChainIndex(from, to)))
  }

  private val listUnconfirmedTransactionsRoute = listUnconfirmedTransactions.toRoute {
    case (from, to) =>
      Future.successful(serverUtils.listUnconfirmedTransactions(blockFlow, ChainIndex(from, to)))
  }

  private val buildTransactionRoute = buildTransaction.toRoute {
    case (fromKey, toAddress, lockTime, value) =>
      Future.successful(
        serverUtils.buildTransaction(blockFlow,
                                     BuildTransaction(fromKey, toAddress, lockTime, value)))
  }

  private val sendTransactionRoute = sendTransaction.toRoute { transaction =>
    serverUtils.sendTransaction(txHandler, transaction)
  }

  private val getTransactionStatusRoute = getTransactionStatus.toRoute {
    case (txId, from, to) =>
      Future.successful(serverUtils.getTransactionStatus(blockFlow, txId, ChainIndex(from, to)))
  }

  private val minerActionRoute = minerAction.toRoute {
    case MinerAction.StartMining => serverUtils.execute(miner ! Miner.Start)
    case MinerAction.StopMining  => serverUtils.execute(miner ! Miner.Stop)
  }

  private val minerListAddressesRoute = minerListAddresses.toRoute { _ =>
    miner
      .ask(Miner.GetAddresses)
      .mapTo[AVector[LockupScript]]
      .map { addresses =>
        Right(MinerAddresses(addresses.map(address => Address(networkType, address))))
      }
  }

  private val minerUpdateAddressesRoute = minerUpdateAddresses.toRoute { minerAddresses =>
    Future.successful {
      MinerAddresses
        .validate(minerAddresses.addresses)
        .map { addresses =>
          miner ! Miner.UpdateAddresses(addresses.addresses.map(_.lockupScript))
        }
        .left
        .map(ApiModel.Error.server)
    }
  }

  private val sendContractRoute = sendContract.toRoute { query =>
    serverUtils.sendContract(txHandler, query)
  }

  private val buildContractRoute = buildContract.toRoute { query =>
    serverUtils.buildContract(blockFlow, query)
  }

  private val compileRoute = compile.toRoute { query =>
    serverUtils.compile(query)
  }

  private val exportBlocksRoute = exportBlocks.toRoute { exportFile =>
    //Run the export in background
    Future.successful(
      blocksExporter
        .export(exportFile.filename)
        .left
        .map(error => logger.error(error.getMessage)))
    //Just validate the filename and return success
    Future.successful {
      blocksExporter
        .validateFilename(exportFile.filename)
        .map(_ => ())
        .left
        .map(error => ApiModel.Error.server(error.getMessage))
    }
  }
  private val walletDocs = walletServer.map(_.docs).getOrElse(List.empty)
  private val blockflowDocs = List(
    getNetwork,
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
    sendTransaction,
    getTransactionStatus,
    sendContract,
    compile,
    buildContract,
    minerAction,
    minerListAddresses,
    minerUpdateAddresses
  )

  private val docs: OpenAPI =
    (walletDocs ++ blockflowDocs).toOpenAPI("Alephium API", "1.0")

  private val servers = List(
    Server("http://{host}:{port}")
      .variables(
        "host" -> ServerVariable(None, "localhost", None),
        "port" -> ServerVariable(None, port.toString, None)
      )
  )

  private val swaggerUIRoute =
    new SwaggerAkka(docs.servers(servers).toYaml, yamlName = "openapi.yaml").routes

  private val blockFlowRoute: Route =
    getNetworkRoute ~
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
      sendTransactionRoute ~
      getTransactionStatusRoute ~
      minerActionRoute ~
      minerListAddressesRoute ~
      minerUpdateAddressesRoute ~
      sendContractRoute ~
      compileRoute ~
      exportBlocksRoute ~
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
  def apply(node: Node,
            miner: ActorRefT[Miner.Command],
            blocksExporter: BlocksExporter,
            walletServer: Option[WalletServer])(implicit system: ActorSystem,
                                                apiConfig: ApiConfig,
                                                executionContext: ExecutionContext): RestServer = {
    val restPort = node.config.network.restPort
    new RestServer(node, restPort, miner, blocksExporter, walletServer)
  }

  def selfCliqueFrom(cliqueInfo: IntraCliqueInfo): SelfClique = {
    SelfClique(
      cliqueInfo.id,
      cliqueInfo.peers.map(peer =>
        PeerAddress(peer.internalAddress.getAddress, peer.restPort, peer.wsPort)),
      cliqueInfo.groupNumPerBroker
    )
  }

  def interCliquePeerInfoFrom(syncStatus: InterCliqueManager.SyncStatus): InterCliquePeerInfo = {
    val peerId = syncStatus.peerId
    InterCliquePeerInfo(peerId.cliqueId, peerId.brokerId, syncStatus.address, syncStatus.isSynced)
  }
}
