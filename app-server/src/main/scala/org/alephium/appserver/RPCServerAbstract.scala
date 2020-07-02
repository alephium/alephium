package org.alephium.appserver

import scala.concurrent.{ExecutionContext, Future}

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives.{complete, extractUpgradeToWebSocket, get, path}
import akka.http.scaladsl.server.Route
import akka.stream.{CompletionStrategy, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.typesafe.scalalogging.StrictLogging
import io.circe.{Encoder, Json}
import io.circe.syntax._

import org.alephium.appserver.RPCModel._
import org.alephium.flow.client.Miner
import org.alephium.flow.core.FlowHandler
import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.flow.platform.PlatformConfig
import org.alephium.rpc.{CirceUtils, JsonRPCHandler}
import org.alephium.rpc.model.JsonRPC.{Handler, Notification, Request, Response}
import org.alephium.util.{ActorRefT, EventBus}

trait RPCServerAbstract extends StrictLogging {
  import RPCServerAbstract._

  implicit def system: ActorSystem
  implicit def executionContext: ExecutionContext
  implicit def config: PlatformConfig
  implicit def rpcConfig: RPCConfig
  implicit def askTimeout: Timeout

  def doBlockNotify(blockNotify: BlockNotify): Json
  def doBlockflowFetch(req: Request): FutureTry[FetchResponse]
  def doGetNeighborCliques(req: Request): FutureTry[NeighborCliques]
  def doGetSelfClique(req: Request): FutureTry[SelfClique]
  def doGetSelfCliqueSynced(req: Request): FutureTry[Boolean]
  def doGetInterCliquePeerInfo(req: Request): FutureTry[Seq[InterCliquePeerInfo]]
  def doGetBalance(req: Request): FutureTry[Balance]
  def doGetGroup(req: Request): FutureTry[Group]
  def doGetHashesAtHeight(req: Request): FutureTry[HashesAtHeight]
  def doGetChainInfo(req: Request): FutureTry[ChainInfo]
  def doGetBlock(req: Request): FutureTry[BlockEntry]
  def doCreateTransaction(req: Request): FutureTry[CreateTransactionResult]
  def doSendTransaction(req: Request): FutureTry[TxResult]
  def doStartMining(miner: ActorRefT[Miner.Command]): FutureTry[Boolean] =
    execute(miner ! Miner.Start)
  def doStopMining(miner: ActorRefT[Miner.Command]): FutureTry[Boolean] =
    execute(miner ! Miner.Stop)

  def runServer(): Future[Unit]

  def handleEvent(event: EventBus.Event): TextMessage = {
    event match {
      case bn @ FlowHandler.BlockNotify(_, _) =>
        val params       = doBlockNotify(bn)
        val notification = Notification("block_notify", params)
        TextMessage(CirceUtils.print(notification.asJson))
    }
  }

  def handlerRPC(miner: ActorRefT[Miner.Command]): Handler = Map.apply(
    "blockflow_fetch"            -> (req => wrap(req, doBlockflowFetch(req))),
    "get_balance"                -> (req => wrap(req, doGetBalance(req))),
    "get_group"                  -> (req => wrap(req, doGetGroup(req))),
    "get_hashes_at_height"       -> (req => wrap(req, doGetHashesAtHeight(req))),
    "get_chain_info"             -> (req => wrap(req, doGetChainInfo(req))),
    "get_block"                  -> (req => wrap(req, doGetBlock(req))),
    "get_inter_clique_peer_info" -> (req => simpleWrap(req, doGetInterCliquePeerInfo(req))),
    "mining_start"               -> (req => simpleWrap(req, doStartMining(miner))),
    "mining_stop"                -> (req => simpleWrap(req, doStopMining(miner))),
    "neighbor_cliques"           -> (req => wrap(req, doGetNeighborCliques(req))),
    "create_transaction"         -> (req => wrap(req, doCreateTransaction(req))),
    "send_transaction"           -> (req => wrap(req, doSendTransaction(req))),
    "self_clique"                -> (req => wrap(req, doGetSelfClique(req))),
    "self_clique_synced"         -> (req => simpleWrap(req, doGetSelfCliqueSynced(req)))
  )

  def routeHttp(miner: ActorRefT[Miner.Command]): Route =
    cors()(JsonRPCHandler.routeHttp(handlerRPC(miner)))

  def routeWs(eventBus: ActorRefT[EventBus.Message]): Route = {
    path("events") {
      cors()(get {
        extractUpgradeToWebSocket { upgrade =>
          val (actor, source) = Websocket.actorRef
          eventBus.tell(EventBus.Subscribe, actor)
          val response = upgrade.handleMessages(wsFlow(eventBus, actor, source))
          complete(response)
        }
      })
    }
  }

  def wsFlow(eventBus: ActorRefT[EventBus.Message],
             actor: ActorRef,
             source: Source[Nothing, NotUsed]): Flow[Any, TextMessage, Unit] = {
    Flow
      .fromSinkAndSourceCoupled(Sink.ignore, source.map(handleEvent))
      .watchTermination() { (_, termination) =>
        termination.onComplete(_ => eventBus.tell(EventBus.Unsubscribe, actor))
      }
  }
}

object RPCServerAbstract {
  import Response.Failure
  type Try[T]       = Either[Failure, T]
  type FutureTry[T] = Future[Try[T]]

  val bufferSize: Int = 64

  def execute(f: => Unit)(implicit ec: ExecutionContext): FutureTry[Boolean] =
    Future {
      f
      Right(true)
    }

  def wrap[T <: RPCModel: Encoder](req: Request, result: FutureTry[T])(
      implicit ec: ExecutionContext): Future[Response] = result.map {
    case Right(t)    => Response.successful(req, t)
    case Left(error) => error
  }

  // Note: use wrap when T derives RPCModel
  def simpleWrap[T: Encoder](req: Request, result: FutureTry[T])(
      implicit ec: ExecutionContext): Future[Response] = result.map {
    case Right(t)    => Response.successful(req, t)
    case Left(error) => error
  }

  object Websocket {

    case object Completed
    case object Failed

    def actorRef(implicit system: ActorSystem): (ActorRef, Source[Nothing, NotUsed]) =
      Source
        .actorRef(
          {
            case Websocket.Completed =>
              CompletionStrategy.draining
          }, {
            case Websocket.Failed =>
              new Throwable("failure on events websocket")
          },
          bufferSize,
          OverflowStrategy.fail
        )
        .preMaterialize()

  }
}
