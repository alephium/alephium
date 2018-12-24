package org.alephium.rpc

import scala.concurrent.Future
import java.time.Instant

import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json

import org.alephium.flow.client.{FairMiner, Miner, Node}
import org.alephium.flow.{Mode, Platform}
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.rpc.model.JsonRPC

trait RPCServer extends Platform with CORSHandler with StrictLogging {
  import RPCServer._

  def mode: Mode

  def runServer(): Future[Unit] = {
    val node = mode.node

    implicit val system           = node.system
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val config           = mode.config
    implicit val rpcConfig        = RPCConfig.load(config.alephium)

    val miner = {
      val publicKey = config.discoveryConfig.discoveryPublicKey
      val props     = FairMiner.props(publicKey, node).withDispatcher("akka.actor.mining-dispatcher")
      system.actorOf(props, s"FairMiner")
    }

    val rpcHandler: JsonRPC.Handler = {
      case "blockflow/fetch" =>
        req =>
          blockflowFetch(node, req)

      case "mining/start" =>
        req =>
          miner ! Miner.Start
          req.successful()

      case "mining/stop" =>
        req =>
          miner ! Miner.Stop
          req.successful()
    }

    val route = corsHandler(JsonRPCHandler.route(rpcHandler))

    Http().bindAndHandle(route, rpcConfig.networkInterface, mode.httpPort).map(_ => ())
  }
}

object RPCServer extends StrictLogging {
  import JsonRPC._

  def blockflowFetch(node: Node, req: Request)(implicit rpc: RPCConfig,
                                               cfg: ConsensusConfig): Response = {
    import model.BlockFlowRPC._

    req.params.as[FetchRequest] match {
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
      case Left(decodingFailure) =>
        logger.debug(s"Unable to decode blokflow/fetch request. (${decodingFailure})")
        req.failure(Error.InvalidParams)
    }
  }
}
