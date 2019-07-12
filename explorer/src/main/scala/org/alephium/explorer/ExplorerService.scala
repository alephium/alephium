package org.alephium.explorer

import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

import akka.{NotUsed}
import akka.actor.{ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws._
import akka.stream.{ActorMaterializer, OverflowStrategy, StreamTcpException}
import akka.stream.scaladsl._

import io.circe.Json
import io.circe.parser._
import io.circe.syntax._

import org.alephium.rpc.model.{BlockFlowRPC, JsonRPC}
import org.alephium.util.{BaseActor}

object ExplorerService {
  def props(wsAddress: String): Props =
    Props(new ExplorerService(wsAddress))

  sealed trait Command
  case object Connect extends Command
  case object Fetch   extends Command

  private[ExplorerService] case object Update extends Command

  sealed trait Event
  case class Blocks(blocks: List[BlockFlowRPC.FetchEntry])

  // WebSocket Events
  case object StreamClosed extends Command
}

class ExplorerService(wsAddress: String) extends BaseActor with StrictLogging {
  import ExplorerService._

  implicit val system: ActorSystem = context.system
  implicit val materializer        = ActorMaterializer()

  import context.dispatcher

  // TODO Move to config
  val connectRetryFrequency: FiniteDuration = FiniteDuration(5, TimeUnit.SECONDS)
  val updateFrequency: FiniteDuration       = FiniteDuration(1, TimeUnit.SECONDS)

  val rpcId = Json.fromString("explorer")

  val incoming: Sink[Message, NotUsed] =
    Sink.actorRef(self, StreamClosed)

  val outgoing: Source[Message, ActorRef] =
    Source.actorRef(1, OverflowStrategy.fail)

  var outgoingActorRef: ActorRef = _

  var online                                = false
  var blocks: List[BlockFlowRPC.FetchEntry] = Nil

  def receive: Receive = {
    case Connect =>
      val wsFlow = Http().webSocketClientFlow(WebSocketRequest(wsAddress))

      val ((sourceActorRef, upgradeResponse), _) =
        outgoing
          .viaMat(wsFlow)(Keep.both)
          .toMat(incoming)(Keep.both)
          .run()

      outgoingActorRef = sourceActorRef

      upgradeResponse.onComplete {
        case Success(_) =>
          logger.info(s"Successfully connected to $wsAddress")
          online = true
          self ! Update
        case Failure(_) =>
      }

    case Fetch => sender() ! Blocks(blocks)
    case Update => {
      if (online) {
        val rpcRequest = JsonRPC.Request(rpcId, "blockflow/fetch", Json.obj())

        outgoingActorRef ! TextMessage(rpcRequest.asJson.toString)

        scheduleOnce(self, Update, updateFrequency)
      } else {
        logger.warn("Can not update blocks, no connection to BlockFlow server.")
      }
    }

    case TextMessage.Strict(text) =>
      handleWSResponse(text)

    case Status.Failure(e: StreamTcpException) =>
      logger.error("Connection failure", e)
      online = false
      scheduleOnce(self, Connect, connectRetryFrequency)
  }

  def handleWSResponse(text: String) {
    import JsonRPC.Response

    parse(text) match {
      case Right(json) =>
        json.as[Response] match {
          case Right(Response.Success(_, response)) =>
            response.as[BlockFlowRPC.FetchResponse] match {
              case Right(fetchResponse) =>
                blocks = fetchResponse.blocks.sortBy(-_.timestamp)
              case Left(parsingFailure) =>
                logger.debug(s"Unable to parse FetchResponse. (${parsingFailure})")
            }
          case Right(Response.Failure(_, error)) =>
            logger.debug(s"Received error for JSON-RPC call. (${error})")
          case Left(parsingFailure) =>
            logger.debug(s"Unable to parse JSON-RPC response. (${parsingFailure})")
        }
      case Left(parsingFailure) =>
        logger.debug(s"Unable to parse JSON response. (${parsingFailure})")
    }
  }
}
