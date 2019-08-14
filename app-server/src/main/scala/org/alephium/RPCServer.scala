package org.alephium

import java.time.Instant
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}
import akka.actor.ActorRef
import akka.pattern.ask
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.ws.{TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, SourceRef}
import akka.stream.scaladsl.{Flow, Sink}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import io.circe._
import org.alephium.flow.{Mode, Platform, PlatformEventBus}
import org.alephium.flow.client.{FairMiner, Miner, Node}
import org.alephium.flow.storage.MultiChain
import org.alephium.flow.network.DiscoveryServer
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.{BlockHeader, CliqueInfo}
import org.alephium.rpc.{CORSHandler, JsonRPCHandler, RPCConfig}
import org.alephium.rpc.AVectorJson._
import org.alephium.rpc.model.{JsonRPC, RPC}

trait RPCServer extends Platform with CORSHandler with StrictLogging {
  import RPCServer._

  def mode: Mode

  def handler(node: Node, miner: ActorRef)(implicit consus: ConsensusConfig,
                                           rpc: RPCConfig,
                                           timeout: Timeout,
                                           EC: ExecutionContext): JsonRPC.Handler = {
    case "blockflow/fetch" =>
      req =>
        Future {
          blockflowFetch(node, req)
        }

    case "clique/info" =>
      req =>
        node.discoveryServer.ask(DiscoveryServer.GetPeerCliques).map { result =>
          val cliques = result.asInstanceOf[DiscoveryServer.PeerCliques]
          req.success(encodeAVector[CliqueInfo].apply(cliques.peers))
        }

    case "mining/start" =>
      req =>
        Future {
          miner ! Miner.Start
          req.successful()
        }

    case "mining/stop" =>
      req =>
        Future {
          miner ! Miner.Stop
          req.successful()
        }
  }

  def runServer(): Future[Unit] = {
    val node = mode.node

    implicit val system           = node.system
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val config           = mode.config
    implicit val rpcConfig        = RPCConfig.load(config.alephium)
    implicit val askTimeout       = Timeout(Duration.fromNanos(rpcConfig.askTimeout.toNanos))

    val miner = {
      val props = FairMiner.props(node).withDispatcher("akka.actor.mining-dispatcher")
      system.actorOf(props, s"FairMiner")
    }

    val route = concat(
      path("rpc") {
        corsHandler(JsonRPCHandler.route(handler(node, miner)))
      },
      path("events") {
        corsHandler(get {
          // TODO When does the actor get cleaned?
          val eventBusClient = system.actorOf(EventBusClient.props(node.eventBus))
          val source =
            (eventBusClient ? EventBusClient.Connect).mapTo[SourceRef[PlatformEventBus.Event]]
          onComplete(source) {
            case Success(source) =>
              handleWebSocketMessages(
                Flow.fromSinkAndSource(
                  Sink.ignore,
                  source.map {
                    // TODO Replace with real impl
                    case PlatformEventBus.Event.Dummy =>
                      val ts = System.currentTimeMillis()
                      TextMessage(s"{ dummy: $ts}")
                  }
                ))
            case Failure(err) =>
              logger.error("Unable to connect event bus client.", err)
              complete(
                HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(err.getMessage)))
          }
        })
      }
    )

    Http().bindAndHandle(route, rpcConfig.networkInterface, mode.httpPort).map(_ => ())
  }
}

object RPCServer extends StrictLogging {
  import RPC._
  import JsonRPC._

  // TODO How to get this infer automatically? (semiauto generic derivation from Circe is failing)
  implicit val encodeCliqueInfo: Encoder[CliqueInfo] = new Encoder[CliqueInfo] {
    final def apply(ci: CliqueInfo): Json = {
      Json.obj(("id", Json.fromString(ci.id.toString)),
               ("peers", encodeAVector[String].apply(ci.peers.map(_.toString))))
    }
  }
  def blockflowFetch(node: Node, req: Request)(implicit rpc: RPCConfig,
                                               cfg: ConsensusConfig): Response = {
    req.paramsAs[FetchRequest] match {
      case Right(query) =>
        val now        = Instant.now()
        val lowerBound = now.minus(rpc.blockflowFetchMaxAge).toEpochMilli

        val from = query.from match {
          case Some(ts) => Math.max(ts, lowerBound)
          case None     => lowerBound
        }

        val headers = node.blockFlow.getHeadersUnsafe(header => header.timestamp > from)
        val blocks  = headers.map(blockHeaderEncoder(node.blockFlow).apply)

        val json = Json.obj(("blocks", Json.arr(blocks: _*)))

        req.success(json)
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
