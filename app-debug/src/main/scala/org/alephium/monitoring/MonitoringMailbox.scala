package org.alephium.monitoring

import java.util.concurrent.ConcurrentLinkedQueue

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.{Envelope, MailboxType, MessageQueue, ProducesMessageQueue}
import com.codahale.metrics.{Counter, MetricRegistry}
import com.typesafe.config.Config

object MonitoringMailbox {

  class MonitoringMessageQueue(owner: ActorRef)
      extends MessageQueue
      with akka.event.LoggerMessageQueueSemantics
      with akka.dispatch.UnboundedMessageQueueSemantics
      with MonitoringMailboxSemantics {

    val queue: ConcurrentLinkedQueue[Envelope] = new ConcurrentLinkedQueue[Envelope]()
    @SuppressWarnings(Array("org.wartremover.warts.ToString"))
    val queueSize: Counter =
      Monitoring.metrics.counter(MetricRegistry.name(owner.path.toString, "queue"))

    def enqueue(receiver: ActorRef, handle: Envelope): Unit = {
      val _ = queue.offer(handle)
      queueSize.inc()
    }
    def dequeue(): Envelope = {
      val env = queue.poll()
      if (Envelope.unapply(env).isDefined) {
        queueSize.dec()
      }
      env
    }

    def numberOfMessages: Int = queue.size
    def hasMessages: Boolean  = !queue.isEmpty

    @SuppressWarnings(Array("org.wartremover.warts.While"))
    def cleanUp(owner: ActorRef, deadLetters: MessageQueue) {
      while (hasMessages) {
        deadLetters.enqueue(owner, dequeue())
      }
    }
  }
}

class MonitoringMailbox
    extends MailboxType
    with ProducesMessageQueue[MonitoringMailbox.MonitoringMessageQueue] {

  import MonitoringMailbox.MonitoringMessageQueue

  def this(settings: ActorSystem.Settings, config: Config) = {
    this()
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  final override def create(
      owner: Option[ActorRef],
      system: Option[ActorSystem]
  ): MessageQueue =
    new MonitoringMessageQueue(owner.get)
}

trait MonitoringMailboxSemantics
