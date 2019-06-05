package org.alephium.flow.network.clique

import java.net.InetSocketAddress
import java.time.Instant

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.model.{BrokerId, CliqueId, CliqueInfo}

import scala.concurrent.duration._

object OutboundBrokerHandler {
  def props(selfCliqueInfo: CliqueInfo,
            cliqueId: CliqueId,
            brokerId: BrokerId,
            remote: InetSocketAddress,
            allHandlers: AllHandlers)(implicit config: PlatformConfig): Props =
    Props(new OutboundBrokerHandler(selfCliqueInfo, cliqueId, brokerId, remote, allHandlers))

  sealed trait Command
  case object Retry extends Command

  sealed trait Event
}

class OutboundBrokerHandler(val selfCliqueInfo: CliqueInfo,
                            val cliqueId: CliqueId,
                            val brokerId: BrokerId,
                            val remote: InetSocketAddress,
                            val allHandlers: AllHandlers)(implicit val config: PlatformConfig)
    extends BrokerHandler {
  val until: Instant = Instant.now().plusMillis(config.retryTimeout.toMillis)

  var cliqueInfo: CliqueInfo = _

  IO(Tcp)(context.system) ! Tcp.Connect(remote)

  var connection: ActorRef = _

  override def receive: Receive = connecting

  def connecting: Receive = {
    case OutboundBrokerHandler.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(remote)

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

  def handle(_cliqueInfo: CliqueInfo, brokerIndex: Int): Unit = {
    cliqueInfo = _cliqueInfo
  }
}
