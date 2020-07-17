package org.alephium.appserver

import scala.concurrent._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import io.circe._
import io.circe.syntax._

import org.alephium.appserver.ApiModel._
import org.alephium.flow.Stoppable
import org.alephium.flow.client.Miner
import org.alephium.flow.core.{BlockFlow, TxHandler}
import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, InterCliqueManager}
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.flow.platform.{Mode, PlatformConfig}
import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.protocol.model.ChainIndex
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{ActorRefT, Duration}

class RPCServer(mode: Mode, rpcPort: Int, wsPort: Int, miner: ActorRefT[Miner.Command])(
    implicit val system: ActorSystem,
    val config: PlatformConfig,
    val executionContext: ExecutionContext)
    extends RPCServerAbstract
    with Stoppable {
  import RPCServer._
  import RPCServerAbstract.FutureTry

  implicit val rpcConfig: RPCConfig = RPCConfig.load(config.aleph)
  implicit val askTimeout: Timeout  = Timeout(rpcConfig.askTimeout.asScala)

  private val terminationHardDeadline = Duration.ofSecondsUnsafe(10).asScala

  private implicit val fetchRequestDecoder: Decoder[FetchRequest] = FetchRequest.decoder

  private val blockFlow: BlockFlow                    = mode.node.blockFlow
  private val txHandler: ActorRefT[TxHandler.Command] = mode.node.allHandlers.txHandler

  def doBlockNotify(blockNotify: BlockNotify): Json =
    blockNotifyEncode(blockNotify)

  def doBlockflowFetch(req: Request): FutureTry[FetchResponse] =
    Future.successful(
      withReqE[FetchRequest, FetchResponse](req)(ServerUtils.getBlockflow(blockFlow, _)))

  def doGetNeighborCliques(req: Request): FutureTry[NeighborCliques] =
    mode.node.discoveryServer
      .ask(DiscoveryServer.GetNeighborCliques)
      .mapTo[DiscoveryServer.NeighborCliques]
      .map { neighborCliques =>
        Right(NeighborCliques(neighborCliques.peers))
      }

  def doGetSelfClique(req: Request): FutureTry[SelfClique] =
    mode.node.boostraper.ask(Bootstrapper.GetIntraCliqueInfo).mapTo[IntraCliqueInfo].map {
      cliqueInfo =>
        Right(SelfClique.from(cliqueInfo))
    }

  def doGetSelfCliqueSynced(req: Request): FutureTry[Boolean] =
    mode.node.cliqueManager.ask(CliqueManager.IsSelfCliqueSynced).mapTo[Boolean].map(Right(_))

  def doGetInterCliquePeerInfo(req: Request): FutureTry[Seq[InterCliquePeerInfo]] =
    mode.node.cliqueManager
      .ask(InterCliqueManager.GetSyncStatuses)
      .mapTo[Seq[InterCliqueManager.SyncStatus]]
      .map { syncedStatuses =>
        Right(syncedStatuses.map(InterCliquePeerInfo.from))
      }

  def doGetBalance(req: Request): FutureTry[Balance] =
    Future.successful(withReqE[GetBalance, Balance](req)(ServerUtils.getBalance(blockFlow, _)))

  def doGetGroup(req: Request): FutureTry[Group] =
    Future.successful(withReqE[GetGroup, Group](req)(ServerUtils.getGroup(blockFlow, _)))

  def doGetHashesAtHeight(req: Request): FutureTry[HashesAtHeight] =
    Future.successful(
      perChainReqE[GetHashesAtHeight, HashesAtHeight](req) { (chainIndex, query) =>
        ServerUtils.getHashesAtHeight(blockFlow, chainIndex, query)
      }
    )

  def doGetChainInfo(req: Request): FutureTry[ChainInfo] =
    Future.successful(
      perChainReqE[GetChainInfo, ChainInfo](req) { (chainIndex, _) =>
        ServerUtils.getChainInfo(blockFlow, chainIndex)
      }
    )

  def doGetBlock(req: Request): FutureTry[BlockEntry] =
    Future.successful(withReqE[GetBlock, BlockEntry](req)(ServerUtils.getBlock(blockFlow, _)))

  def doCreateTransaction(req: Request): FutureTry[CreateTransactionResult] =
    Future.successful(
      withReqE[CreateTransaction, CreateTransactionResult](req)(
        ServerUtils.createTransaction(blockFlow, _)))

  def doSendTransaction(req: Request): FutureTry[TxResult] =
    withReqF[SendTransaction, TxResult](req)(ServerUtils.sendTransaction(txHandler, _))

  val httpRoute: Route = routeHttp(miner)
  val wsRoute: Route   = routeWs(mode.node.eventBus)

  private var started: Boolean                                = false
  private val httpBindingPromise: Promise[Http.ServerBinding] = Promise()
  private val wsBindingPromise: Promise[Http.ServerBinding]   = Promise()

  def runServer(): Future[Unit] = {
    started = true
    for {
      httpBinding <- Http()
        .bindAndHandle(httpRoute, rpcConfig.networkInterface.getHostAddress, rpcPort)
      wsBinding <- Http()
        .bindAndHandle(wsRoute, rpcConfig.networkInterface.getHostAddress, wsPort)
    } yield {
      logger.info(s"Listening http request on $httpBinding")
      logger.info(s"Listening ws request on $wsBinding")
      httpBindingPromise.success(httpBinding)
      wsBindingPromise.success(wsBinding)
    }
  }

  def stop(): Future[Unit] =
    if (started) {
      for {
        httpStop <- httpBindingPromise.future.flatMap(
          _.terminate(hardDeadline = terminationHardDeadline))
        wsStop <- wsBindingPromise.future.flatMap(
          _.terminate(hardDeadline = terminationHardDeadline))
      } yield {
        logger.info(s"http unbound with message $httpStop.")
        logger.info(s"ws unbound with message $wsStop.")
        ()
      }
    } else {
      Future.successful(())
    }
}

object RPCServer extends {
  import RPCServerAbstract._

  def apply(mode: Mode, miner: ActorRefT[Miner.Command])(
      implicit system: ActorSystem,
      config: PlatformConfig,
      executionContext: ExecutionContext): RPCServer = {
    (for {
      rpcPort <- mode.config.rpcPort
      wsPort  <- mode.config.wsPort
    } yield {
      new RPCServer(mode, rpcPort, wsPort, miner)
    }) match {
      case Some(server) => server
      case None         => throw new RuntimeException("rpc and ws ports are required")
    }
  }

  def withReq[T: Decoder, R](req: Request)(f: T => R): Try[R] = {
    req.paramsAs[T] match {
      case Right(query)  => Right(f(query))
      case Left(failure) => Left(failure)
    }
  }

  def withReqE[T <: ApiModel: Decoder, R <: ApiModel](req: Request)(f: T => Try[R]): Try[R] = {
    req.paramsAs[T] match {
      case Right(query)  => f(query)
      case Left(failure) => Left(failure)
    }
  }

  def perChainReqE[T <: ApiModel with PerChain: Decoder, R <: ApiModel](req: Request)(
      f: (ChainIndex, T) => Try[R])(implicit config: GroupConfig): Try[R] = {
    req.paramsAs[T] match {
      case Right(query) =>
        ChainIndex
          .from(query.fromGroup, query.toGroup)
          .toRight(Response.failed("Invalid chain index"))
          .flatMap(f(_, query))
      case Left(failure) => Left(failure)
    }
  }

  def withReqF[T <: ApiModel: Decoder, R <: ApiModel](req: Request)(
      f: T => FutureTry[R]): FutureTry[R] = {
    req.paramsAs[T] match {
      case Right(query)  => f(query)
      case Left(failure) => Future.successful(Left(failure))
    }
  }

  def blockNotifyEncode(blockNotify: BlockNotify)(implicit config: ConsensusConfig): Json =
    BlockEntry.from(blockNotify).asJson
}
