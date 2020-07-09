package org.alephium.appserver

import scala.concurrent._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.typesafe.scalalogging.StrictLogging
import sttp.tapir.docs.openapi.RichOpenAPIEndpoints
import sttp.tapir.openapi.OpenAPI
import sttp.tapir.openapi.circe.yaml.RichOpenAPI
import sttp.tapir.server.akkahttp.RichAkkaHttpEndpoint

import org.alephium.appserver.ApiModel._
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.platform.{Mode, PlatformConfig}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.Duration

// scalastyle:off method.length
class RestServer(mode: Mode, port: Int)(implicit config: PlatformConfig,
                                        actorSystem: ActorSystem,
                                        executionContext: ExecutionContext)
    extends Endpoints
    with StrictLogging {

  private val blockFlow: BlockFlow    = mode.node.blockFlow
  private val terminationHardDeadline = Duration.ofSecondsUnsafe(10).asScala

  implicit val rpcConfig: RPCConfig = RPCConfig.load(config.aleph)
  implicit val groupConfig: GroupConfig = config

  private val docs: OpenAPI = List(
    getBlockflow,
    getBlock,
    getBalance,
    getGroup,
    getHashesAtHeight,
    getChainInfo,
    createTransaction
  ).toOpenAPI("Alephium BlockFlow API", "1.0")

  val route: Route =
    cors()(
      getBlockflow.toRoute(timeInterval =>
        Future.successful(
          ServerUtils.getBlockflow(blockFlow, FetchRequest(timeInterval.from, timeInterval.to)))) ~
        getBlock
          .toRoute(hash => Future.successful(ServerUtils.getBlock(blockFlow, GetBlock(hash)))) ~
        getBalance
          .toRoute(address =>
            Future.successful(ServerUtils.getBalance(blockFlow, GetBalance(address)))) ~
        getGroup
          .toRoute(address =>
            Future.successful(ServerUtils.getGroup(blockFlow, GetGroup(address)))) ~
        getHashesAtHeight
          .toRoute{ case (from, to, height) =>
            Future.successful(ServerUtils.getHashesAtHeight(blockFlow, ChainIndex(from,to),  GetHashesAtHeight(from.value,to.value,height)))} ~
        getChainInfo
          .toRoute{ case (from, to) =>
            Future.successful(ServerUtils.getChainInfo(blockFlow, ChainIndex(from,to)))} ~
        createTransaction
          .toRoute{ case (fromKey, toAddress, value) =>
            Future.successful(ServerUtils.createTransaction(blockFlow, CreateTransaction(fromKey, toAddress, value)))} ~
        getOpenapi.toRoute(_ => Future.successful(Right(docs.toYaml)))
    )

  private var started: Boolean                                = false
  private val httpBindingPromise: Promise[Http.ServerBinding] = Promise()

  def runServer(): Future[Unit] = {
    started = true
    for {
      httpBinding <- Http()
        .bindAndHandle(route, rpcConfig.networkInterface.getHostAddress, port)
    } yield {
      logger.info(s"Listening http request on $httpBinding")
      httpBindingPromise.success(httpBinding)
    }
  }

  def stop(): Future[Unit] =
    if (started) {
      for {
        httpStop <- httpBindingPromise.future.flatMap(
          _.terminate(hardDeadline = terminationHardDeadline))
      } yield {
        logger.info(s"http unbound with message $httpStop.")
        ()
      }
    } else {
      Future.successful(())
    }
}

object RestServer {
  def apply(mode: Mode)(implicit system: ActorSystem,
                        config: PlatformConfig,
                        executionContext: ExecutionContext): RestServer = {
    (for {
      restPort <- mode.config.restPort
    } yield {
      new RestServer(mode, restPort)
    }) match {
      case Some(server) => server
      case None         => throw new RuntimeException("rpc and ws ports are required")
    }
  }
}
