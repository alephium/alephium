package org.alephium.appserver

import scala.concurrent.{ExecutionContext, Future}

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives.{complete, extractUpgradeToWebSocket, get, path}
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import io.circe.syntax._

import org.alephium.appserver.RPCModel.{Balance, FetchResponse, PeerCliques, TransferResult}
import org.alephium.flow.client.Miner
import org.alephium.flow.platform.PlatformProfile
import org.alephium.rpc.{CORSHandler, JsonRPCHandler}
import org.alephium.rpc.model.JsonRPC.{Handler, Notification, Request}
import org.alephium.util.EventBus

trait RPCServerAbstract extends StrictLogging {
  import RPCServer._

  implicit def system: ActorSystem
  implicit def materializer: ActorMaterializer
  implicit def executionContext: ExecutionContext
  implicit def config: PlatformProfile
  implicit def rpcConfig: RPCConfig
  implicit def askTimeout: Timeout

  def doBlockflowFetch(req: Request): FutureTry[FetchResponse]
  def doGetPeerCliques(req: Request): FutureTry[PeerCliques]
  def doGetBalance(req: Request): FutureTry[Balance]
  def doTransfer(req: Request): FutureTry[TransferResult]
  def doStartMining(miner: ActorRef): FutureTry[Boolean] =
    execute(miner ! Miner.Start)
  def doStopMining(miner: ActorRef): FutureTry[Boolean] =
    execute(miner ! Miner.Stop)

  def runServer(): Future[Unit]

  def handleEvent(event: EventBus.Event): TextMessage = {
    // TODO Replace with concrete implementation.
    event match {
      case _ =>
        val ts     = System.currentTimeMillis()
        val result = Notification("events_fake", ts.asJson)
        TextMessage(result.asJson.noSpaces)
    }
  }

  def handlerRPC(miner: ActorRef): Handler = Map.apply(
    "blockflow_fetch" -> (req => wrap(req, doBlockflowFetch(req))),
    "peer_cliques"    -> (req => wrap(req, doGetPeerCliques(req))),
    "get_balance"     -> (req => wrap(req, doGetBalance(req))),
    "transfer"        -> (req => wrap(req, doTransfer(req))),
    "mining_start"    -> (req => wrap(req, doStartMining(miner))),
    "mining_stop"     -> (req => wrap(req, doStopMining(miner)))
  )

  def routeHttp(miner: ActorRef): Route =
    CORSHandler(JsonRPCHandler.routeHttp(handlerRPC(miner)))

  def routeWs(eventBus: ActorRef): Route = {
    path("events") {
      CORSHandler(get {
        extractUpgradeToWebSocket { upgrade =>
          val (actor, source) =
            Source.actorRef(bufferSize, OverflowStrategy.fail).preMaterialize()
          eventBus.tell(EventBus.Subscribe, actor)
          val response = upgrade.handleMessages(wsFlow(eventBus, actor, source))
          complete(response)
        }
      })
    }
  }

  def wsFlow(eventBus: ActorRef,
             actor: ActorRef,
             source: Source[Nothing, NotUsed]): Flow[Any, TextMessage, Unit] = {
    Flow
      .fromSinkAndSourceCoupled(Sink.ignore, source.map(handleEvent))
      .watchTermination() { (_, termination) =>
        termination.onComplete(_ => eventBus.tell(EventBus.Unsubscribe, actor))
      }
  }
}
