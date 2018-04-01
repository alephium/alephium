package org.alephium.network

import akka.actor.ActorRef
import akka.io.Tcp
import org.alephium.network.message.{NetworkMessage, Ping, Pong}
import org.alephium.util.BaseActor

import scala.util.Random

trait MessageHandler extends BaseActor {

  def envelope(message: NetworkMessage): Tcp.Write =
    Tcp.Write(NetworkMessage.serializer.serialize(message))

  def handleMessage(message: NetworkMessage): Unit = {
    val payload = message.payload
    payload match {
      case Ping(nonce) =>
        logger.debug("Ping received, response with pong")
        self ! NetworkMessage(Pong(nonce))
      case Pong(nonce) =>
        if (nonce == pingNonce) {
          logger.debug("Pong received, no response")
        } else {
          logger.debug(s"Pong received with wrong nonce: expect $pingNonce, got $nonce")
          context stop self
        }
    }
  }

  // nonce for ping/pong message
  private var pingNonce: Int = 0

  def startPing(connection: ActorRef): Unit = {
    pingNonce = Random.nextInt()
    connection ! envelope(NetworkMessage(Ping(pingNonce)))
  }
}
