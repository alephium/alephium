package org.alephium

import scala.concurrent.Future
import java.time.Instant

import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.StrictLogging
import io.circe.{Encoder, Json}

import org.alephium.flow.client.{FairMiner, Miner, Node}
import org.alephium.flow.storage.MultiChain
import org.alephium.flow.{Mode, Platform}
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.BlockHeader
import org.alephium.rpc.{CORSHandler, JsonRPCHandler, RPCConfig}
import org.alephium.rpc.model.{BlockFlowRPC, JsonRPC}

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
      val props = FairMiner.props(node).withDispatcher("akka.actor.mining-dispatcher")
      system.actorOf(props, s"FairMiner")
    }

    val rpcHandler: JsonRPC.Handler = {
      case "blockflow/fetch" =>
        req =>
          Future {
            blockflowFetch(node, req)
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

    val route = corsHandler(JsonRPCHandler.route(rpcHandler))

    Http().bindAndHandle(route, rpcConfig.networkInterface, mode.httpPort).map(_ => ())
  }
}

object RPCServer extends StrictLogging {
  import BlockFlowRPC._
  import JsonRPC._

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
