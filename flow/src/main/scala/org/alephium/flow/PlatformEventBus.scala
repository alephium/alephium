package org.alephium.flow

import akka.actor.{ActorRef}
import akka.event.EventBus
import akka.event.LookupClassification

class PlatformEventBus extends EventBus with LookupClassification {
  type Event      = PlatformEventBus.Event
  type Classifier = Unit
  type Subscriber = ActorRef

  override protected def publish(event: Event, subscriber: Subscriber): Unit = {
    subscriber ! event
  }

  override protected def classify(event: Event): Classifier                    = ()
  override protected def compareSubscribers(a: Subscriber, b: Subscriber): Int = a.compareTo(b)
  override protected def mapSize: Int                                          = PlatformEventBus.mapSize
}

object PlatformEventBus {
  val mapSize = 128

  sealed trait Event
  object Event {
    // TODO Replace with real events
    case object Dummy extends Event
  }
}
