package org.alephium.flow.network.clique

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.flow.core.AllHandlers
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.message.Hello
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}

object InboundBrokerHandler {
  def props(selfCliqueInfo: CliqueInfo,
            remote: InetSocketAddress,
            connection: ActorRef,
            allHandlers: AllHandlers)(implicit config: PlatformProfile): Props =
    Props(new InboundBrokerHandler(selfCliqueInfo, remote, connection, allHandlers))
}

class InboundBrokerHandler(val selfCliqueInfo: CliqueInfo,
                           val remote: InetSocketAddress,
                           val connection: ActorRef,
                           val allHandlers: AllHandlers)(implicit val config: PlatformProfile)
    extends BrokerHandler {

  var remoteCliqueId: CliqueId = _
  var remoteBroker: BrokerInfo = _

  connection ! Tcp.Register(self, keepOpenOnPeerClosed = true)
  connection ! BrokerHandler.envelope(Hello(selfCliqueInfo.id, config.brokerInfo))

  override def receive: Receive = handleWith(ByteString.empty, awaitHelloAck, handlePayload)

  def handle(_remoteCliqueId: CliqueId, remoteBrokerInfo: BrokerInfo): Unit = {
    remoteCliqueId = _remoteCliqueId
    remoteBroker   = remoteBrokerInfo
  }
}
