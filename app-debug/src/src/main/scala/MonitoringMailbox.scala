package org.alephium

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.dispatch.Envelope
import akka.dispatch.MailboxType
import akka.dispatch.MessageQueue
import akka.dispatch.ProducesMessageQueue
import com.typesafe.config.Config
import java.util.concurrent.ConcurrentLinkedQueue

object MonitoringMailbox {
  class MonitoringMessageQueue(owner: ActorRef) extends MessageQueue with akka.event.LoggerMessageQueueSemantics
      with akka.dispatch.UnboundedMessageQueueSemantics
      with MonitoringMailboxSemantics {

    val queue = new ConcurrentLinkedQueue[Envelope]()
    val queueSize = Monitoring.metrics.counter(MetricRegistry.name(owner.path.toString, "queue"))

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
    def hasMessages: Boolean = !queue.isEmpty

    def cleanUp(owner: ActorRef, deadLetters: MessageQueue) {
      while (hasMessages) {
        deadLetters.enqueue(owner, dequeue())
      }
    }
  }
}

class MonitoringMailbox extends MailboxType
  with ProducesMessageQueue[MonitoringMailbox.MonitoringMessageQueue] {

  import MonitoringMailbox.MonitoringMessageQueue

  def this(settings: ActorSystem.Settings, config: Config) = {
    this()
  }

  final override def create(
    owner:  Option[ActorRef],
    system: Option[ActorSystem]
  ): MessageQueue =
    new MonitoringMessageQueue(owner.get)
}

trait MonitoringMailboxSemantics
