package org.alephium.explorer

import scala.concurrent._
import scala.concurrent.duration._

import ExplorerRPC._
import ExplorerService._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import io.circe.Json
import io.circe.syntax._

import org.alephium.rpc.{CORSHandler, JsonRPCHandler}
import org.alephium.rpc.model.JsonRPC

// explorer/run 9000 ws://127.0.0.1:8080
object ExplorerServer extends CORSHandler {
  // TODO Move those to config system
  val address = "0.0.0.0"

  val sizeMin = 1
  val sizeMax = 100

  def main(args: Array[String]) {
    implicit val system           = ActorSystem("explorer-system")
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val askTimeout       = Timeout(100.milliseconds)

    val port             = args(0).toInt
    val blockflowAddress = args(1)

    val service = system.actorOf(ExplorerService.props(blockflowAddress), "ExplorerService")

    service ! ExplorerService.Connect

    val rpcHandler: JsonRPC.Handler = {
      case "blocks" =>
        req =>
          req.paramsAs[BlocksRequest] match {
            case Right(params) =>
              val size     = Math.max(sizeMin, Math.min(sizeMax, params.size))
              val response = (service ? Fetch).asInstanceOf[Future[Blocks]]

              response.map { result: Blocks =>
                req.success(Json.obj(("blocks", result.blocks.take(size).asJson)))
              }
            case Left(failure) => Future.successful(failure)
          }
    }

    val route   = corsHandler(JsonRPCHandler.route(rpcHandler))
    val binding = Http().bindAndHandle(route, address, port)

    Runtime
      .getRuntime()
      .addShutdownHook(new Thread() {
        override def run = {
          binding
            .flatMap(_.unbind())
            .onComplete(_ => system.terminate())
        }
      })
  }
}
