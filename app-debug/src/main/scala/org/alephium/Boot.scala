package org.alephium

import java.net.InetSocketAddress
import akka.actor.{ActorRef, Props}
import com.codahale.metrics.MetricRegistry

import org.alephium.network.{MessageHandler, TcpHandler}
import org.alephium.storage.BlockHandlers

object Boot extends Platform {
  override val mode = new Mode.Local {
    override def builders: TcpHandler.Builder with MessageHandler.Builder =
      new TcpHandler.Builder with MessageHandler.Builder {
        override def createMessageHandler(remote: InetSocketAddress,
                                          connection: ActorRef,
                                          blockHandlers: BlockHandlers): Props = {
          Props(new MessageHandler(remote, connection, blockHandlers) {

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
