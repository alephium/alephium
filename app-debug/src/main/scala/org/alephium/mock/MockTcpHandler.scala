package org.alephium.mock
import java.net.InetSocketAddress

import akka.actor.Props
import com.codahale.metrics.{Histogram, MetricRegistry}
import org.alephium.flow.network.TcpHandler
import org.alephium.flow.storage.BlockHandlers
import org.alephium.monitoring.Monitoring

object MockTcpHandler {

  trait Builder extends TcpHandler.Builder {

    override def createTcpHandler(remote: InetSocketAddress, blockHandlers: BlockHandlers): Props =
      Props(new MockTcpHandler(remote, blockHandlers))
  }
}

class MockTcpHandler(remote: InetSocketAddress, blockHandlers: BlockHandlers)
    extends TcpHandler(remote, blockHandlers) {

  val delays: Histogram =
    Monitoring.metrics.histogram(MetricRegistry.name(remote.toString, "delay"))

  override def handlePing(nonce: Int, delay: Long): Unit = {
    super.handlePing(nonce, delay)
    delays.update(delay)
  }
}
