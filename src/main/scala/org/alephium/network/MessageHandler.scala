package org.alephium.network

import akka.actor.{ActorRef, Timers}
import akka.io.Tcp
import org.alephium.network.message.{NetworkMessage, Ping, Pong}
import org.alephium.util.BaseActor

import scala.util.Random
import scala.concurrent.duration._

object MessageHandler {
  sealed trait Command
  case object SendPing extends Command
}

trait MessageHandler extends BaseActor with Timers {

  def envelope(message: NetworkMessage): Tcp.Write =
    Tcp.Write(NetworkMessage.serializer.serialize(message))

  def forMessageHandler(connection: ActorRef): Receive = {
    case MessageHandler.SendPing =>
      sendPing(connection)
  }

  def handleMessage(message: NetworkMessage, connection: ActorRef): Unit = {
    val payload = message.payload
    payload match {
      case Ping(nonce) =>
        logger.debug("Ping received, response with pong")
        connection ! envelope(NetworkMessage(Pong(nonce)))
      case Pong(nonce) =>
        if (nonce == pingNonce) {
          logger.debug("Pong received, no response")
          pingNonce = 0
        } else {
          logger.debug(s"Pong received with wrong nonce: expect $pingNonce, got $nonce")
          context stop self
        }
    }
  }

  // nonce for ping/pong message
  private var pingNonce: Int = 0

  def sendPing(connection: ActorRef): Unit = {
    if (pingNonce != 0) {
      logger.debug("No pong message received in time")
      context stop self
    } else {
      pingNonce = Random.nextInt()
      connection ! envelope(NetworkMessage(Ping(pingNonce)))
      timers.startSingleTimer(MessageHandler, MessageHandler.SendPing, 1 minute)
    }
  }
}
