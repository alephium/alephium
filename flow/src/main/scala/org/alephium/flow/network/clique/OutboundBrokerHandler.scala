package org.alephium.flow.network.clique

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}

import org.alephium.flow.core.AllHandlers
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}
import org.alephium.util.{Duration, TimeStamp}

object OutboundBrokerHandler {
  def props(selfCliqueInfo: CliqueInfo,
            remoteCliqueId: CliqueId,
            remoteBroker: BrokerInfo,
            allHandlers: AllHandlers,
            cliqueManager: ActorRef)(implicit config: PlatformProfile): Props =
    Props(
      new OutboundBrokerHandler(selfCliqueInfo,
                                remoteCliqueId,
                                remoteBroker,
                                allHandlers,
                                cliqueManager))

  sealed trait Command
  case object Retry extends Command

  sealed trait Event
}

class OutboundBrokerHandler(val selfCliqueInfo: CliqueInfo,
                            val remoteCliqueId: CliqueId,
                            val remoteBrokerInfo: BrokerInfo,
                            val allHandlers: AllHandlers,
                            val cliqueManager: ActorRef)(implicit val config: PlatformProfile)
    extends BrokerHandler {
  override def remote: InetSocketAddress = remoteBrokerInfo.address

  val until: TimeStamp = TimeStamp.now() + config.retryTimeout

  IO(Tcp)(context.system) ! Tcp.Connect(remoteBrokerInfo.address)

  var connection: ActorRef = _

  override def receive: Receive = connecting

  def connecting: Receive = {
    case OutboundBrokerHandler.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(remoteBrokerInfo.address)

    case _: Tcp.Connected =>
      connection = sender()
      connection ! Tcp.Register(self, keepOpenOnPeerClosed = true)
      handshakeIn()

    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val current = TimeStamp.now()
      if (current isBefore until) {
        scheduleOnce(self, OutboundBrokerHandler.Retry, Duration.ofSecondsUnsafe(1))
      } else {
        log.info(s"Cannot connect to ${c.remoteAddress}")
        stop()
      }
  }

  def handleBrokerInfo(_remoteCliqueId: CliqueId, brokerInfo: BrokerInfo): Unit = {
    if (_remoteCliqueId != remoteCliqueId ||
        remoteBrokerInfo.id != brokerInfo.id ||
        remoteBrokerInfo.groupNumPerBroker != brokerInfo.groupNumPerBroker) {
      context stop self
    }
  }
}
