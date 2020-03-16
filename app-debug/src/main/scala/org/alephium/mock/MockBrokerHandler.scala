package org.alephium.mock

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.Tcp
import com.codahale.metrics.{Histogram, MetricRegistry}

import org.alephium.flow.core.AllHandlers
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.clique.{BrokerHandler, InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.monitoring.Monitoring
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}
import org.alephium.util.ActorRefT

object MockBrokerHandler {
  trait Builder extends BrokerHandler.Builder {
    override def createInboundBrokerHandler(
        selfCliqueInfo: CliqueInfo,
        remote: InetSocketAddress,
        connection: ActorRefT[Tcp.Command],
        blockHandlers: AllHandlers,
        cliqueManager: ActorRefT[CliqueManager.Command]
    )(implicit config: PlatformConfig): Props =
      Props(
        new MockInboundBrokerHandler(selfCliqueInfo,
                                     remote,
                                     connection,
                                     blockHandlers,
                                     cliqueManager))

    override def createOutboundBrokerHandler(
        selfCliqueInfo: CliqueInfo,
        remoteCliqueId: CliqueId,
        remoteBroker: BrokerInfo,
        blockHandlers: AllHandlers,
        cliqueManager: ActorRefT[CliqueManager.Command]
    )(implicit config: PlatformConfig): Props =
      Props(
        new MockOutboundBrokerHandler(selfCliqueInfo,
                                      remoteCliqueId,
                                      remoteBroker,
                                      blockHandlers,
                                      cliqueManager))

  }
}

class MockInboundBrokerHandler(
    selfCliqueInfo: CliqueInfo,
    remote: InetSocketAddress,
    connection: ActorRefT[Tcp.Command],
    allHandlers: AllHandlers,
    cliqueManager: ActorRefT[CliqueManager.Command])(implicit config: PlatformConfig)
    extends InboundBrokerHandler(selfCliqueInfo, remote, connection, allHandlers, cliqueManager) {
  val delays: Histogram =
    Monitoring.metrics.histogram(MetricRegistry.name(remote.toString, "delay"))

  override def handlePing(nonce: Int, timestamp: Long): Unit = {
    super.handlePing(nonce, timestamp)
    val delay = System.currentTimeMillis() - timestamp
    delays.update(delay)
  }
}

class MockOutboundBrokerHandler(
    selfCliqueInfo: CliqueInfo,
    remoteCliqueId: CliqueId,
    remoteBroker: BrokerInfo,
    allHandlers: AllHandlers,
    cliqueManager: ActorRefT[CliqueManager.Command])(implicit config: PlatformConfig)
    extends OutboundBrokerHandler(selfCliqueInfo,
                                  remoteCliqueId,
                                  remoteBroker,
                                  allHandlers,
                                  cliqueManager) {
  val delays: Histogram =
    Monitoring.metrics.histogram(MetricRegistry.name(remoteBroker.address.toString, "delay"))

  override def handlePing(nonce: Int, delay: Long): Unit = {
    super.handlePing(nonce, delay)
    delays.update(delay)
  }
}
