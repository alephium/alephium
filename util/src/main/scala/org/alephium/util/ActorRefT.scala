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

import scala.concurrent.Future

import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, Props}
import akka.util.Timeout

@SuppressWarnings(
  Array("org.wartremover.warts.ImplicitParameter", "org.wartremover.warts.DefaultArguments")
)
class ActorRefT[T](val ref: ActorRef) {
  def !(message: T)(implicit sender: ActorRef = Actor.noSender): Unit = ref.!(message)(sender)
  def forward(message: T)(implicit context: ActorContext): Unit       = ref.forward(message)
  def ask(message: T)(implicit timeout: Timeout): Future[Any] = akka.pattern.ask(ref, message)
  def tell(message: T, sender: ActorRef): Unit                = ref.tell(message, sender)

  override def equals(obj: Any): Boolean =
    obj match {
      case that: ActorRefT[_] => ref.equals(that.ref)
      case _                  => false
    }

  override def hashCode(): Int = ref.hashCode
}

object ActorRefT {
  def apply[T](ref: ActorRef): ActorRefT[T] = new ActorRefT[T](ref)

  def build[T](system: ActorSystem, props: Props): ActorRefT[T] = {
    val ref = system.actorOf(props)
    new ActorRefT[T](ref)
  }

  def build[T](system: ActorSystem, props: Props, name: String): ActorRefT[T] = {
    val ref = system.actorOf(props, name)
    new ActorRefT[T](ref)
  }
}
