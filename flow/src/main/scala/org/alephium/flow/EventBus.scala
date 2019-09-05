package org.alephium.flow

import scala.collection.mutable
import akka.actor.{ActorRef, Props}
import org.alephium.util.{BaseActor}

object EventBus {
  def props(): Props = {
    Props(new EventBus())
  }

  sealed trait Command
  case object Subscribe   extends Command
  case object Unsubscribe extends Command

  sealed trait Event
  object Event {
    // TODO Replace with real events
    case object Dummy extends Event
  }

}

class EventBus() extends BaseActor {
  import EventBus._

  private val subscribers: mutable.HashSet[ActorRef] = mutable.HashSet.empty

  def receive: Receive = {
    case Subscribe =>
      if (!subscribers(sender)) { subscribers += sender }
    case Unsubscribe =>
      if (subscribers(sender)) { subscribers -= sender }
    case event: Event =>
      subscribers.foreach { subscriber =>
        subscriber ! event
      }

  }
}
