package org.alephium

import scala.concurrent.duration._

import akka.stream.{ActorMaterializer, SourceRef}
import akka.stream.scaladsl.{Keep, Sink}
import akka.testkit.TestProbe
import org.scalatest.concurrent.ScalaFutures

import org.alephium.util.{AlephiumActorSpec, EventBus}

class EventBusClientSpec extends AlephiumActorSpec("EventBusClient") with ScalaFutures {

  case object Dummy extends EventBus.Event

  trait Fixture { self =>
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val eventBus = system.actorOf(EventBus.props(), "eventBus")
  }

  it should "push event in the stream" in new Fixture {
    val client = system.actorOf(EventBusClient.props(eventBus), "eventBusClient")

    val probe0 = TestProbe()
    client.tell(EventBusClient.Connect, probe0.ref)

    val source = probe0.expectMsgType[SourceRef[EventBus.Event]](1.seconds)

    client ! Dummy

    whenReady(source.toMat(Sink.head)(Keep.right).run) { event =>
      event is Dummy
    }
  }

}
