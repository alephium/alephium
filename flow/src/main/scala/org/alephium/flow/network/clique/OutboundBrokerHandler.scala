package org.alephium.flow.network.clique

import java.net.InetSocketAddress
import java.time.Instant

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}

import scala.concurrent.duration._

object OutboundBrokerHandler {
  def props(selfCliqueInfo: CliqueInfo,
            remoteCliqueId: CliqueId,
            remoteBroker: BrokerInfo,
            allHandlers: AllHandlers)(implicit config: PlatformConfig): Props =
    Props(new OutboundBrokerHandler(selfCliqueInfo, remoteCliqueId, remoteBroker, allHandlers))

  sealed trait Command
  case object Retry extends Command

  sealed trait Event
}

class OutboundBrokerHandler(val selfCliqueInfo: CliqueInfo,
                            val remoteCliqueId: CliqueId,
                            val remoteBroker: BrokerInfo,
                            val allHandlers: AllHandlers)(implicit val config: PlatformConfig)
    extends BrokerHandler {
  override def remote: InetSocketAddress = remoteBroker.address

  val until: Instant = Instant.now().plusMillis(config.retryTimeout.toMillis)

  IO(Tcp)(context.system) ! Tcp.Connect(remoteBroker.address)

  var connection: ActorRef = _

  override def receive: Receive = connecting

  def connecting: Receive = {
    case OutboundBrokerHandler.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(remoteBroker.address)

    case _: Tcp.Connected =>
      connection = sender()
      connection ! Tcp.Register(self)
      handshakeIn()

    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val current = Instant.now()
      if (current isBefore until) {
        scheduleOnce(self, OutboundBrokerHandler.Retry, 1.second)
      } else {
        log.info(s"Cannot connect to ${c.remoteAddress}")
        stop()
      }
  }

  def handle(_remoteCliqueId: CliqueId, brokerInfo: BrokerInfo): Unit = {
    if (_remoteCliqueId != remoteCliqueId ||
        remoteBroker.id != brokerInfo.id ||
        remoteBroker.groupNumPerBroker != brokerInfo.groupNumPerBroker) {
      context stop self
    }
  }
}
