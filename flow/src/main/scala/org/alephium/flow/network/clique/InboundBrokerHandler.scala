package org.alephium.flow.network.clique

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.util.ByteString
import org.alephium.flow.PlatformConfig
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.message.Hello
import org.alephium.protocol.model.CliqueInfo

object InboundBrokerHandler {
  def props(selfCliqueInfo: CliqueInfo, connection: ActorRef, allHandlers: AllHandlers)(
      implicit config: PlatformConfig): Props =
    Props(new InboundBrokerHandler(selfCliqueInfo, connection, allHandlers))
}

class InboundBrokerHandler(val selfCliqueInfo: CliqueInfo,
                           val connection: ActorRef,
                           val allHandlers: AllHandlers)(implicit val config: PlatformConfig)
    extends BrokerHandler {

  var cliqueInfo: CliqueInfo    = _
  var remoteIndex: Int          = _
  var remote: InetSocketAddress = _

  connection ! Tcp.Register(self)
  connection ! BrokerHandler.envelope(Hello(selfCliqueInfo, config.brokerId.value))

  override def receive: Receive = handleWith(ByteString.empty, awaitHelloAck, handlePayload)

  def handle(_cliqueInfo: CliqueInfo, _remoteIndex: Int): Unit = {
    cliqueInfo  = _cliqueInfo
    remoteIndex = _remoteIndex
    remote      = cliqueInfo.peers(_remoteIndex)
  }
}
