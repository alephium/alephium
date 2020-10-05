package org.alephium.appserver

import scala.collection.immutable.ArraySeq
import scala.concurrent._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.typesafe.scalalogging.StrictLogging
import sttp.tapir.docs.openapi.RichOpenAPIServerEndpoints
import sttp.tapir.openapi.OpenAPI
import sttp.tapir.openapi.circe.yaml.RichOpenAPI
import sttp.tapir.server.akkahttp._
import sttp.tapir.swagger.akkahttp.SwaggerAkka

import org.alephium.appserver.ApiModel._
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.TxHandler
import org.alephium.protocol.config.{ChainsConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, Duration, Service}

// scalastyle:off method.length
class RestServer(node: Node, port: Int, miner: ActorRefT[Miner.Command])(
    implicit val apiConfig: ApiConfig,
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext)
    extends Endpoints
    with Service
    with StrictLogging {

  private val blockFlow: BlockFlow                    = node.blockFlow
  private val txHandler: ActorRefT[TxHandler.Command] = node.allHandlers.txHandler
  private val terminationHardDeadline                 = Duration.ofSecondsUnsafe(10).asScala

  implicit val groupConfig: GroupConfig   = node.config.broker
  implicit val chainsConfig: ChainsConfig = node.config.chains
  implicit val networkType: NetworkType   = node.config.chains.networkType
  implicit val askTimeout: Timeout        = Timeout(apiConfig.askTimeout.asScala)

  private val getBlockflowLogic = getBlockflow.serverLogic { timeInterval =>
    Future.successful(
      ServerUtils.getBlockflow(blockFlow, FetchRequest(timeInterval.from, timeInterval.to)))
  }

  private val getBlockLogic = getBlock.serverLogic { hash =>
    Future.successful(ServerUtils.getBlock(blockFlow, GetBlock(hash)))
  }

  private val getBalanceLogic = getBalance.serverLogic { address =>
    Future.successful(ServerUtils.getBalance(blockFlow, GetBalance(address)))
  }

  private val getGroupLogic = getGroup.serverLogic { address =>
    Future.successful(ServerUtils.getGroup(blockFlow, GetGroup(address)))
  }

  private val getHashesAtHeightLogic = getHashesAtHeight.serverLogic {
    case (from, to, height) =>
      Future.successful(
        ServerUtils.getHashesAtHeight(blockFlow,
                                      ChainIndex(from, to),
                                      GetHashesAtHeight(from.value, to.value, height)))
  }

  private val getChainInfoLogic = getChainInfo.serverLogic {
    case (from, to) =>
      Future.successful(ServerUtils.getChainInfo(blockFlow, ChainIndex(from, to)))
  }

  private val createTransactionLogic = createTransaction.serverLogic {
    case (fromKey, toAddress, value) =>
      Future.successful(
        ServerUtils.createTransaction(blockFlow, CreateTransaction(fromKey, toAddress, value)))
  }

  private val sendTransactionLogic = sendTransaction.serverLogic {
    case (_, transaction) =>
      ServerUtils.sendTransaction(txHandler, transaction)
  }

  private val minerActionLogic = minerAction.serverLogic {
    case (_, action) =>
      action match {
        case MinerAction.StartMining => ServerUtils.execute(miner ! Miner.Start)
        case MinerAction.StopMining  => ServerUtils.execute(miner ! Miner.Stop)
      }
  }

  private val docs: OpenAPI = List(
    getBlockflowLogic,
    getBlockLogic,
    getBalanceLogic,
    getGroupLogic,
    getHashesAtHeightLogic,
    getChainInfoLogic,
    createTransactionLogic,
    sendTransactionLogic,
    minerActionLogic
  ).toOpenAPI("Alephium BlockFlow API", "1.0")

  private val swaggerUIRoute = new SwaggerAkka(docs.toYaml, yamlName = "openapi.yaml").routes

  val route: Route =
    cors()(
      getBlockflowLogic.toRoute ~
        getBlockLogic.toRoute ~
        getBalanceLogic.toRoute ~
        getGroupLogic.toRoute ~
        getHashesAtHeightLogic.toRoute ~
        getChainInfoLogic.toRoute ~
        createTransactionLogic.toRoute ~
        sendTransactionLogic.toRoute ~
        minerActionLogic.toRoute ~
        swaggerUIRoute
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
  def apply(node: Node, miner: ActorRefT[Miner.Command])(
      implicit system: ActorSystem,
      apiConfig: ApiConfig,
      executionContext: ExecutionContext): RestServer = {
    val restPort = node.config.network.restPort
    new RestServer(node, restPort, miner)
  }
}
