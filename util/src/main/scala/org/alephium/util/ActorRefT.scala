package org.alephium.util

import scala.concurrent.Future

import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, Props}
import akka.util.Timeout

@SuppressWarnings(
  Array("org.wartremover.warts.ImplicitParameter", "org.wartremover.warts.DefaultArguments"))
final case class ActorRefT[T](ref: ActorRef) extends AnyVal {
  def !(message: T)(implicit sender: ActorRef = Actor.noSender): Unit = ref.!(message)(sender)
  def forward(message: T)(implicit context: ActorContext): Unit = ref.forward(message)
  def ask(message: T)(implicit timeout: Timeout): Future[Any]   = akka.pattern.ask(ref, message)
  def tell(message: T, sender: ActorRef): Unit                  = ref.tell(message, sender)
}

object ActorRefT {
  def build[T](system: ActorSystem, props: Props, name: String): ActorRefT[T] = {
    val ref = system.actorOf(props, name)
    ActorRefT[T](ref)
  }
}
