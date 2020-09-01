package org.alephium.appserver

import scala.collection.immutable.ArraySeq
import scala.concurrent._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import io.circe._
import io.circe.syntax._

import org.alephium.appserver.ApiModel._
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.flow.handler.TxHandler
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, InterCliqueManager}
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{ActorRefT, Duration, Service}

class RPCServer(node: Node, rpcPort: Int, wsPort: Int, miner: ActorRefT[Miner.Command])(
    implicit val system: ActorSystem,
    val apiConfig: ApiConfig,
    val executionContext: ExecutionContext)
    extends RPCServerAbstract
    with Service {
  import RPCServer._
  import RPCServerAbstract.FutureTry

  implicit val groupConfig: GroupConfig = node.config.broker
  implicit val askTimeout: Timeout      = Timeout(apiConfig.askTimeout.asScala)

  private val terminationHardDeadline = Duration.ofSecondsUnsafe(10).asScala

  private implicit val fetchRequestDecoder: Decoder[FetchRequest] = FetchRequest.decoder

  private val blockFlow: BlockFlow                    = node.blockFlow
  private val txHandler: ActorRefT[TxHandler.Command] = node.allHandlers.txHandler

  def doBlockNotify(blockNotify: BlockNotify): Json =
    blockNotifyEncode(blockNotify)

  def doBlockflowFetch(req: Request): FutureTry[FetchResponse] =
    Future.successful(
      withReqE[FetchRequest, FetchResponse](req)(ServerUtils.getBlockflow(blockFlow, _)))

  def doGetNeighborCliques(req: Request): FutureTry[NeighborCliques] =
    node.discoveryServer
      .ask(DiscoveryServer.GetNeighborCliques)
      .mapTo[DiscoveryServer.NeighborCliques]
      .map { neighborCliques =>
        Right(NeighborCliques(neighborCliques.peers))
      }

  def doGetSelfClique(req: Request): FutureTry[SelfClique] =
    node.bootstrapper.ask(Bootstrapper.GetIntraCliqueInfo).mapTo[IntraCliqueInfo].map {
      cliqueInfo =>
        Right(SelfClique.from(cliqueInfo))
    }

  def doGetSelfCliqueSynced(req: Request): FutureTry[Boolean] =
    node.cliqueManager.ask(CliqueManager.IsSelfCliqueReady).mapTo[Boolean].map(Right(_))

  def doGetInterCliquePeerInfo(req: Request): FutureTry[Seq[InterCliquePeerInfo]] =
    node.cliqueManager
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
  val wsRoute: Route   = routeWs(node.eventBus)

  private val httpBindingPromise: Promise[Http.ServerBinding] = Promise()
  private val wsBindingPromise: Promise[Http.ServerBinding]   = Promise()

  override def subServices: ArraySeq[Service] = ArraySeq(node)

  protected def startSelfOnce(): Future[Unit] = {
    for {
      httpBinding <- Http()
        .bindAndHandle(httpRoute, apiConfig.networkInterface.getHostAddress, rpcPort)
      wsBinding <- Http()
        .bindAndHandle(wsRoute, apiConfig.networkInterface.getHostAddress, wsPort)
    } yield {
      logger.info(s"Listening http request on $httpBinding")
      logger.info(s"Listening ws request on $wsBinding")
      httpBindingPromise.success(httpBinding)
      wsBindingPromise.success(wsBinding)
    }
  }

  protected def stopSelfOnce(): Future[Unit] = {
    for {
      httpBinding <- httpBindingPromise.future
      httpStop    <- httpBinding.terminate(hardDeadline = terminationHardDeadline)
      wsBinding   <- wsBindingPromise.future
      wsStop      <- wsBinding.terminate(hardDeadline = terminationHardDeadline)
    } yield {
      logger.info(s"http unbound with message $httpStop.")
      logger.info(s"ws unbound with message $wsStop.")
      ()
    }
  }
}

object RPCServer extends {
  import RPCServerAbstract._

  def apply(node: Node, miner: ActorRefT[Miner.Command])(
      implicit system: ActorSystem,
      apiConfig: ApiConfig,
      executionContext: ExecutionContext): RPCServer = {
    (for {
      rpcPort <- node.config.network.rpcPort
      wsPort  <- node.config.network.wsPort
    } yield {
      new RPCServer(node, rpcPort, wsPort, miner)
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

  def blockNotifyEncode(blockNotify: BlockNotify)(implicit config: GroupConfig): Json =
    BlockEntry.from(blockNotify).asJson
}
