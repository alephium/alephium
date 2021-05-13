// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.util

import scala.collection.mutable

import akka.actor.{ActorRef, Props}

object EventBus {
  def props(): Props = {
    Props(new EventBus())
  }

  trait Message

  sealed trait Command        extends Message
  case object Subscribe       extends Command
  case object Unsubscribe     extends Command
  case object ListSubscribers extends Command

  trait Event extends Message

  final case class Subscribers(value: AVector[ActorRef]) extends Message
}

class EventBus() extends BaseActor {
  import EventBus._

  private val subscribers: mutable.HashSet[ActorRef] = mutable.HashSet.empty

  def receive: Receive = {
    case event: Event =>
      subscribers.foreach { subscriber => subscriber ! event }
    case Subscribe =>
      val subscriber = sender()
      if (!subscribers.contains(subscriber)) { subscribers += subscriber }
    case Unsubscribe =>
      val subscriber = sender()
      if (subscribers.contains(subscriber)) { subscribers -= subscriber }
    case ListSubscribers =>
      sender() ! Subscribers(AVector.unsafe(subscribers.toArray))
  }
}
