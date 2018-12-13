package org.alephium

import java.net.InetSocketAddress
import akka.actor.Props
import com.codahale.metrics.MetricRegistry

import org.alephium.network.TcpHandler
import org.alephium.storage.BlockHandlers

object Boot extends Platform {
  override val mode = new Mode.Local(args(0).toInt) {
    override def builders: TcpHandler.Builder =
      new TcpHandler.Builder {
        override def createTcpHandler(remote: InetSocketAddress,
                                      blockHandlers: BlockHandlers): Props = {
          Props(new TcpHandler(remote, blockHandlers) {

            val delays = Monitoring.metrics.histogram(MetricRegistry.name(remote.toString, "delay"))

            override def handlePing(nonce: Int, delay: Long): Unit = {
              super.handlePing(nonce, delay)
              delays.update(delay)
            }
          })
        }
      }
  }

  init()
}
