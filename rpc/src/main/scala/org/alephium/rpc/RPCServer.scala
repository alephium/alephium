package org.alephium.rpc

import java.time.Instant

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging
import io.circe.parser._
import org.alephium.flow.client.{FairMiner, Miner, Node}
import org.alephium.flow.{Mode, Platform}
import org.alephium.rpc.model.ViewerQuery

import scala.concurrent.Future

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

    val miningRoute = path("mining") {
      put {
        val publicKey = config.discoveryConfig.discoveryPublicKey
        val props     = FairMiner.props(publicKey, node).withDispatcher("akka.actor.mining-dispatcher")
        val miner     = system.actorOf(props, s"FairMiner")
        miner ! Miner.Start

        complete((StatusCodes.Accepted, "Start mining"))
      }
    }

    val viewerRoute = corsHandler(path("viewer") {
      get {
        handleWebSocketMessages(wsViewer(node))
      }
    })

    val route = miningRoute ~ viewerRoute

    Http().bindAndHandle(route, "0.0.0.0", mode.httpPort).map(_ => ())
  }
}

object RPCServer extends StrictLogging {
  private val failure = TextMessage("""{"error": "Bad request"}"""")

  private def handleQuery(text: String, node: Node)(implicit rpc: RPCConfig) = {
    decode[ViewerQuery](text) match {
      case Right(query) =>
        val now        = Instant.now()
        val lowerBound = now.minus(rpc.viewerBlockAgeLimit).toEpochMilli

        val from = query.from match {
          case Some(ts) => Math.max(ts, lowerBound)
          case None     => lowerBound
        }

        val headers = node.blockFlow.getHeadersUnsafe(header => header.timestamp > from)
        val json = headers
          .map(node.blockFlow.toJson)
          .mkString("""{"blocks":[""", ",", "]}")
        TextMessage(json)

      case Left(e) =>
        logger.info(s"Decode error for request test: $text: ${e.toString}")
        failure
    }
  }

  def wsViewer(node: Node)(implicit rpc: RPCConfig): Flow[Message, Message, Any] = {
    Flow[Message].collect {
      case request: TextMessage.Strict =>
        handleQuery(request.text, node)
      case request: TextMessage.Streamed =>
        logger.info(s"Received non-valid RPC viewer request: $request")
        failure
    }
  }
}
