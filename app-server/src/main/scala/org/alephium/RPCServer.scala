package org.alephium

import java.time.Instant

import scala.concurrent._
import scala.concurrent.duration.Duration

import akka.actor.ActorRef
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import io.circe._

import org.alephium.flow.client.{FairMiner, Miner, Node}
import org.alephium.flow.core.MultiChain
import org.alephium.flow.network.DiscoveryServer
import org.alephium.flow.platform.Mode
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.{BlockHeader, CliqueInfo}
import org.alephium.rpc.{CORSHandler, JsonRPCHandler, RPCConfig}
import org.alephium.rpc.AVectorJson._
import org.alephium.rpc.model.{JsonRPC, Module, RPC}
import org.alephium.util.EventBus

trait RPCServer extends CORSHandler with StrictLogging {
  import RPCServer._

  def mode: Mode

  def parseMethod(str: String): Option[(Module, String)] = {
    val tokens = str.split('_')
    if (tokens.size < 2) { None }
    else {
      Module.fromString(tokens(0)).map((_, tokens(1)))
    }
  }

  def handler(node: Node, miner: ActorRef)(implicit consus: ConsensusConfig,
                                           rpc: RPCConfig,
                                           timeout: Timeout,
                                           EC: ExecutionContext): JsonRPC.Handler = {
   import Module._

   parseMethod(_).flatMap {
     case (BlockFlow, "fetch") =>
      Some(req =>
        Future {
          blockflowFetch(node, req)
        })
    case (Clique, "info") =>
      Some(req =>
        node.discoveryServer.ask(DiscoveryServer.GetPeerCliques).map { result =>
          val cliques = result.asInstanceOf[DiscoveryServer.PeerCliques]
          req.success(encodeAVector[CliqueInfo].apply(cliques.peers))
        })

    case (Mining, "start") =>
      Some(req =>
        Future {
          miner ! Miner.Start
          req.successful()
        })

    case (Mining, "stop") =>
      Some(req =>
        Future {
          miner ! Miner.Stop
          req.successful()
        })
     case _ => None
   }
  }

  def handleEvent(event: EventBus.Event): TextMessage = {
    // TODO Replace with concrete implementation.
    event match {
      case _ =>
        val ts = System.currentTimeMillis()
        TextMessage(s"{ dummy: $ts}")
    }
  }

  def runServer(): Future[Unit] = {
    val node = mode.node

    implicit val system           = node.system
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val config           = mode.profile
    implicit val rpcConfig        = RPCConfig.load(config.aleph)
    implicit val askTimeout       = Timeout(Duration.fromNanos(rpcConfig.askTimeout.toNanos))

    val miner = {
      val props = FairMiner.props(node).withDispatcher("akka.actor.mining-dispatcher")
      system.actorOf(props, s"FairMiner")
    }

    val routeHttp =
      corsHandler(JsonRPCHandler.routeHttp(handler(node, miner)))

    val routeWs = concat(
      path("rpc") {
        corsHandler(JsonRPCHandler.routeWs(handler(node, miner)))
      },
      path("events") {
        corsHandler(get {
          extractUpgradeToWebSocket {
            upgrade =>
              val (actor, source) =
                Source.actorRef(bufferSize, OverflowStrategy.fail).preMaterialize()
              node.eventBus.tell(EventBus.Subscribe, actor)
              complete(
                upgrade.handleMessagesWith(
                  Flow
                    .fromSinkAndSource(Sink.ignore, source.map(handleEvent))
                    .watchTermination() { (_, termination) =>
                      termination.onComplete {
                        case _ =>
                          node.eventBus.tell(EventBus.Unsubscribe, actor)
                      }
                    }
                ))
          }
        })
      }
    )

    Http().bindAndHandle(routeHttp, rpcConfig.networkInterface, mode.rpcHttpPort).map(_ => ())
    Http().bindAndHandle(routeWs, rpcConfig.networkInterface, mode.rpcWsPort).map(_     => ())
  }
}

object RPCServer extends StrictLogging {
  import RPC._
  import JsonRPC._

  val bufferSize = 64

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
