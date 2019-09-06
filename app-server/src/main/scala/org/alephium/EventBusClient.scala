package org.alephium

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

import akka.actor.{ActorRef, Props}
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source, StreamRefs}

import org.alephium.util.{BaseActor, EventBus}

class EventBusClient(eventBus: ActorRef) extends BaseActor {
  import EventBusClient._
  import EventBus.{Event, Subscribe, Unsubscribe}

  implicit val materializer         = ActorMaterializer()
  implicit val ec: ExecutionContext = context.dispatcher

  val (down, publisher) = Source
    .actorRef[Event](bufferSize, OverflowStrategy.fail)
    .toMat(Sink.asPublisher(fanout = false))(Keep.both)
    .run()

  val streamRef = Source.fromPublisher(publisher).runWith(StreamRefs.sourceRef())

  eventBus ! Subscribe

  override def receive: Receive = {
    case event: Event =>
      down ! event
    case Connect =>
      streamRef.pipeTo(sender).onComplete {
        case Success(_) => // noop
        case Failure(err) =>
          log.error("Unable to instantiate event bus publisher.", err)
      }
  }

  override def postStop: Unit = {
    eventBus ! Unsubscribe
  }
}

object EventBusClient {
  def props(eventBus: ActorRef): Props =
    Props(new EventBusClient(eventBus))
  case object Connect

  // TODO Proper buffer settings from config
  val bufferSize = 64
}
