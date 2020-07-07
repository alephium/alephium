package org.alephium.util

import scala.collection.mutable

import akka.actor.{ActorRef, Props}

object EventBus {
  def props(): Props = {
    Props(new EventBus())
  }

  trait Message

  sealed trait Command    extends Message
  case object Subscribe   extends Command
  case object Unsubscribe extends Command

  trait Event extends Message
}

class EventBus() extends BaseActor {
  import EventBus._

  private val subscribers: mutable.HashSet[ActorRef] = mutable.HashSet.empty

  def receive: Receive = {
    case event: Event =>
      subscribers.foreach { subscriber =>
        subscriber ! event
      }
    case Subscribe =>
      if (!subscribers(sender)) { subscribers += sender }
    case Unsubscribe =>
      if (subscribers(sender)) { subscribers -= sender }
  }
}
