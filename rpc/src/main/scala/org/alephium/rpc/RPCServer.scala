package org.alephium.rpc

import scala.concurrent.Future
import java.time.Instant

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow}
import com.typesafe.scalalogging.StrictLogging
import io.circe.parser._

import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.{Mode, Platform}
import org.alephium.flow.client.{Miner, Node}
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.Hex._

trait RPCServer extends Platform with StrictLogging {
  import RPCServer._

  def mode: Mode

  def runServer(node: Node): Future[Unit] = {
    implicit val system           = node.system
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val config           = mode.config
    implicit val rpcConfig        = RPCConfig.load(config.alephium)

    val groups = mode.config.groups
    val from   = mode.config.mainGroup.value

    logger.info(s"index: ${mode.index}, group: $from")

    val route = path("mining") {
      put {
        val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
          hex"2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1d")

        (0 until groups).foreach { to =>
          val chainIndex = ChainIndex(from, to)
          val props = mode.builders
            .createMiner(publicKey, node, chainIndex)
            .withDispatcher("akka.actor.mining-dispatcher")
          val miner = node.system.actorOf(props, s"Miner-$from-$to")
          miner ! Miner.Start
        }

        complete((StatusCodes.Accepted, "Start mining"))
      }
    } ~
      path("viewer") {
        get {
          handleWebSocketMessages(wsViewer(node))
        }
      }

    Http()
      .bindAndHandle(route, "0.0.0.0", mode.httpPort)
      .map(_ => ())
  }

}

object RPCServer {
  def wsViewer(node: Node)(implicit rpc: RPCConfig): Flow[Message, Message, Any] = {
    Flow[Message]
      .collect {
        case requestJson: TextMessage =>
          val now   = Instant.now()
          val limit = now.minus(rpc.viewerBlockAgeLimit).toEpochMilli()

          val request = parse(requestJson.getStrictText)
          val from = Math.max(
            request.flatMap(_.hcursor.get[Long]("from")).toOption.getOrElse(limit),
            limit
          )

          val blocks = node.blockFlow.getBlocks
            .filter { case (_, _, header) => header.timestamp > from }

          val json = {
            val blocksJson = blocks
              .map {
                case ((i, j), hash, header) =>
                  node.blockFlow.toJson(i, j, hash, header)
              }
              .mkString("[", ",", "]")

              val blocks = node.blockFlow.getHeaders(header => header.timestamp > from)

              val json = {
                val blocksJson = blocks
                  .map { header =>
                    node.blockFlow.toJsonUnsafe(header)
                  }
                  .mkString("[", ",", "]")

                s"""{"blocks":$blocksJson}"""
              }

              TextMessage(json)

            case Left(_) =>
              logger.info(s"Received non-valid RPC viewer request query: ${request}")
              failure
          }

          TextMessage(json)
      }
  }
}
