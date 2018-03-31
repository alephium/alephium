package org.alephium.network

import org.alephium.network.message.{NetworkMessage, Ping, Pong}
import org.alephium.util.BaseActor

trait MessageHandler extends BaseActor {

  def handleMessage(message: NetworkMessage): Unit = {
    val header  = message.header
    val payload = message.payload
    payload match {
      case Ping(nonce) =>
        logger.debug("Ping received, response with pong")
        sender() ! NetworkMessage(header.version, Pong(nonce + 1))
      case Pong(_) =>
        logger.debug("Pong received, no response")
    }
  }

}
