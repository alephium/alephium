package org.alephium

import akka.actor.{Actor, Props}
import akka.stream.{ActorMaterializer}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source, StreamRefs}
import akka.pattern.pipe
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

import org.alephium.flow.{PlatformEventBus}

class EventBusClient(eventBus: PlatformEventBus) extends Actor with StrictLogging {
  import EventBusClient._
  import PlatformEventBus.Event

  implicit val materializer         = ActorMaterializer()
  implicit val ec: ExecutionContext = context.dispatcher

  val (down, publisher) = Source
    .actorRef[Event](bufferSize, OverflowStrategy.fail)
    .toMat(Sink.asPublisher(fanout = false))(Keep.both)
    .run()

  val streamRef = Source.fromPublisher(publisher).runWith(StreamRefs.sourceRef())

  eventBus.subscribe(self, ())

  override def receive: Receive = {
    case event: Event =>
      down ! event
    case Connect =>
      streamRef.pipeTo(sender).onComplete {
        case Success(_) => // noop
        case Failure(err) =>
          logger.error("Unable to instantiate event bus publisher.", err)
      }
  }
}

object EventBusClient {
  def props(eventBus: PlatformEventBus): Props =
    Props(new EventBusClient(eventBus))
  case object Connect

  // TODO Proper buffer settings from config
  val bufferSize = 64
}
