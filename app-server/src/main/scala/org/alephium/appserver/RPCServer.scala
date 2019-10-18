package org.alephium.appserver

import java.time.Instant

import scala.concurrent._
import scala.concurrent.duration.Duration

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import io.circe._
import io.circe.syntax._

import org.alephium.appserver.RPCModel.{FetchEntry, FetchRequest}
import org.alephium.flow.client.{FairMiner, Miner}
import org.alephium.flow.core.{BlockFlow, MultiChain}
import org.alephium.flow.network.DiscoveryServer
import org.alephium.flow.platform.{Mode, PlatformProfile}
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.{BlockHeader, CliqueInfo}
import org.alephium.rpc.{CORSHandler, JsonRPCHandler}
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.{Notification, Response}
import org.alephium.rpc.util.AVectorJson._
import org.alephium.util.{AVector, EventBus}

trait RPCServer extends RPCServerAbstract {
  import RPCServer._

  implicit val system: ActorSystem = mode.node.system
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val config: PlatformProfile = mode.profile
  implicit val rpcConfig: RPCConfig = RPCConfig.load(config.aleph)
  implicit val askTimeout: Timeout = Timeout(Duration.fromNanos(rpcConfig.askTimeout.toNanos))

  def mode: Mode

  def doBlockflowFetch(req: JsonRPC.Request): JsonRPC.Response =
    blockflowFetch(mode.node.blockFlow, req)

  def doCliqueInfo(req: JsonRPC.Request): Future[JsonRPC.Response] =
    mode.node.discoveryServer.ask(DiscoveryServer.GetPeerCliques).map { result =>
      val cliques = result.asInstanceOf[DiscoveryServer.PeerCliques]
      val json    = cliques.peers.asJson
      Response.successful(req, json)
    }

  def runServer(): Future[Unit] = {
    val miner = {
      val props = FairMiner.props(mode.node).withDispatcher("akka.actor.mining-dispatcher")
      system.actorOf(props, s"FairMiner")
    }

    Http().bindAndHandle(routeHttp(miner), rpcConfig.networkInterface.getHostAddress, mode.rpcHttpPort).map(_ => ())
    Http().bindAndHandle(routeWs(mode.node.eventBus), rpcConfig.networkInterface.getHostAddress, mode.rpcWsPort).map(_     => ())
  }
}

trait RPCServerAbstract extends StrictLogging {
  import RPCServer._

  implicit def system: ActorSystem
  implicit def materializer: ActorMaterializer
  implicit def executionContext: ExecutionContext
  implicit def config: PlatformProfile
  implicit def rpcConfig: RPCConfig
  implicit def askTimeout: Timeout

  def doBlockflowFetch(req: JsonRPC.Request): JsonRPC.Response

  def doCliqueInfo(req: JsonRPC.Request): Future[JsonRPC.Response]

  def runServer(): Future[Unit]

  def handleEvent(event: EventBus.Event): TextMessage = {
    // TODO Replace with concrete implementation.
    event match {
      case _ =>
        val ts = System.currentTimeMillis()
        val result = Notification("events_fake", Some(ts.asJson))
        TextMessage(result.asJson.noSpaces)
    }
  }

  def handlerRPC(miner: ActorRef): JsonRPC.Handler = Map.apply(
    "blockflow_fetch" -> { req => Future { doBlockflowFetch(req) }},
    "clique_info" -> { req => doCliqueInfo(req) },
    "mining_start" -> { req =>
      Future {
        miner ! Miner.Start
        Response.successful(req)
      }
    },
    "mining_stop" -> { req =>
      Future {
        miner ! Miner.Stop
        Response.successful(req)
      }
    }
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

  def wsFlow(eventBus: ActorRef, actor: ActorRef, source: Source[Nothing, NotUsed]): Flow[Any, TextMessage, Unit] = {
    Flow
      .fromSinkAndSourceCoupled(Sink.ignore, source.map(handleEvent))
      .watchTermination() { (_, termination) =>
        termination.onComplete(_ => eventBus.tell(EventBus.Unsubscribe, actor))
      }
  }
}

object RPCServer extends StrictLogging {
  import JsonRPC._

  val bufferSize = 64

  implicit val encodeCliqueInfo: Encoder[CliqueInfo] = new Encoder[CliqueInfo] {
    final def apply(ci: CliqueInfo): Json = {
      Json.obj(("id", Json.fromString(ci.id.toString)),
               ("peers", encodeAVector[String].apply(ci.peers.map(_.toString))))
    }
  }

  implicit val cliquesEncoder: Encoder[AVector[CliqueInfo]] = encodeAVector[CliqueInfo]

  def blockflowFetch(blockFlow: BlockFlow, req: Request)(implicit rpc: RPCConfig,
                                               cfg: ConsensusConfig): Response = {
    req.paramsAs[FetchRequest] match {
      case Right(query) =>
        val now        = Instant.now()
        val lowerBound = now.minus(rpc.blockflowFetchMaxAge).toEpochMilli

        val from = query.from match {
          case Some(ts) => Math.max(ts, lowerBound)
          case None     => lowerBound
        }

        val headers = blockFlow.getHeadersUnsafe(header => header.timestamp > from)
        val blocks  = headers.map(blockHeaderEncoder(blockFlow).apply)

        val json = Json.obj(("blocks", Json.arr(blocks: _*)))

        Response.successful(req, json)
      case Left(failure) => failure
    }
  }

  def blockHeaderEncoder(chain: MultiChain)(
      implicit config: ConsensusConfig): Encoder[BlockHeader] = new Encoder[BlockHeader] {
    final def apply(header: BlockHeader): Json = {
      import io.circe.syntax._

      val index = header.chainIndex

      FetchEntry(
        hash      = header.shortHex,
        timestamp = header.timestamp,
        chainFrom = index.from.value,
        chainTo   = index.to.value,
        height    = chain.getHeight(header),
        deps      = header.blockDeps.toIterable.map(_.shortHex).toList
      ).asJson
    }
  }
}
