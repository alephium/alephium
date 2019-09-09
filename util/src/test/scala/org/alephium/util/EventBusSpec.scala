package org.alephium.util

import akka.testkit.TestProbe

class EventBusSpec extends AlephiumActorSpec("EventBus") {

  case object Dummy extends EventBus.Event

  trait Fixture { self =>
    val eventBus = system.actorOf(EventBus.props())
  }

  it should "forward event to one subscriber" in new Fixture {
    eventBus ! EventBus.Subscribe
    eventBus ! Dummy
    expectMsg(Dummy)
    eventBus ! EventBus.Unsubscribe
    eventBus ! Dummy
    expectNoMessage()
  }

  it should "broadcast event to many subscriber" in new Fixture {
    val probe0 = TestProbe()
    val probe1 = TestProbe()

    eventBus.tell(EventBus.Subscribe, probe0.ref)
    eventBus.tell(EventBus.Subscribe, probe1.ref)

    eventBus ! Dummy

    probe0.expectMsg(Dummy)
    probe1.expectMsg(Dummy)

    eventBus.tell(EventBus.Unsubscribe, probe0.ref)
    eventBus.tell(EventBus.Unsubscribe, probe1.ref)

    eventBus ! Dummy

    probe0.expectNoMessage()
    probe1.expectNoMessage()
  }
}
