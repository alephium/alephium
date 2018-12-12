package org.alephium

import java.net.InetSocketAddress
import akka.actor.{ActorRef, Props}

import org.alephium.network.{TcpHandler, MessageHandler}
import org.alephium.storage.BlockHandlers

object Boot extends Platform {
  override val mode = new Mode.Local {
    override def builders: TcpHandler.Builder with MessageHandler.Builder =
      new TcpHandler.Builder with MessageHandler.Builder {
        override def MessageHandler(remote: InetSocketAddress, connection: ActorRef, blockHandlers: BlockHandlers): Props =
          Props(new MessageHandler(remote, connection, blockHandlers) {
            override def handlePing(nonce: Int, timestamp: Long): Unit = {
              super.handlePing(nonce, timestamp)
              // TODO Add metrics monitoring logic
            }
          })
      }
  }
}
