package org.alephium.mock

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import com.codahale.metrics.{Histogram, MetricRegistry}

import org.alephium.flow.PlatformProfile
import org.alephium.flow.network.clique.{BrokerHandler, InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.core.AllHandlers
import org.alephium.monitoring.Monitoring
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}

object MockBrokerHandler {
  trait Builder extends BrokerHandler.Builder {
    override def createInboundBrokerHandler(
        selfCliqueInfo: CliqueInfo,
        remote: InetSocketAddress,
        connection: ActorRef,
        blockHandlers: AllHandlers)(implicit config: PlatformProfile): Props =
      Props(new MockInboundBrokerHandler(selfCliqueInfo, remote, connection, blockHandlers))

    override def createOutboundBrokerHandler(
        selfCliqueInfo: CliqueInfo,
        remoteCliqueId: CliqueId,
        remoteBroker: BrokerInfo,
        blockHandlers: AllHandlers)(implicit config: PlatformProfile): Props =
      Props(
        new MockOutboundBrokerHandler(selfCliqueInfo, remoteCliqueId, remoteBroker, blockHandlers))

  }
}

class MockInboundBrokerHandler(selfCliqueInfo: CliqueInfo,
                               remote: InetSocketAddress,
                               connection: ActorRef,
                               allHandlers: AllHandlers)(implicit config: PlatformProfile)
    extends InboundBrokerHandler(selfCliqueInfo, remote, connection, allHandlers) {
  val delays: Histogram =
    Monitoring.metrics.histogram(MetricRegistry.name(remote.toString, "delay"))

  override def handlePing(nonce: Int, delay: Long): Unit = {
    super.handlePing(nonce, delay)
    delays.update(delay)
  }
}

class MockOutboundBrokerHandler(selfCliqueInfo: CliqueInfo,
                                remoteCliqueId: CliqueId,
                                remoteBroker: BrokerInfo,
                                allHandlers: AllHandlers)(implicit config: PlatformProfile)
    extends OutboundBrokerHandler(selfCliqueInfo, remoteCliqueId, remoteBroker, allHandlers) {
  val delays: Histogram =
    Monitoring.metrics.histogram(MetricRegistry.name(remoteBroker.address.toString, "delay"))

  override def handlePing(nonce: Int, delay: Long): Unit = {
    super.handlePing(nonce, delay)
    delays.update(delay)
  }
}
