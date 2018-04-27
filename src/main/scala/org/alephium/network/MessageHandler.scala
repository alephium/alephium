package org.alephium.network

import akka.actor.{ActorRef, Timers}
import akka.io.Tcp
import org.alephium.constant.Network
import org.alephium.protocol.message._
import org.alephium.storage.BlockPool
import org.alephium.util.BaseActor

import scala.util.Random

object MessageHandler {
  sealed trait Command
  case object SendPing extends Command
}

trait MessageHandler extends BaseActor with Timers {

  def blockPool: ActorRef

  def envelope(message: Message): Tcp.Write =
    Tcp.Write(Message.serializer.serialize(message))

  def forMessageHandler(connection: ActorRef): Receive = {
    case MessageHandler.SendPing =>
      sendPing(connection)
  }

  def handleMessage(message: Message, connection: ActorRef): Unit = {
    val payload = message.payload
    payload match {
      case Ping(nonce) =>
        logger.debug("Ping received, response with pong")
        connection ! envelope(Message(Pong(nonce)))
      case Pong(nonce) =>
        if (nonce == pingNonce) {
          logger.debug("Pong received, no response")
          pingNonce = 0
        } else {
          logger.debug(s"Pong received with wrong nonce: expect $pingNonce, got $nonce")
          context stop self
        }
      case SendBlocks(blocks) =>
        logger.debug(s"Blocks received: $blocks")
        blockPool ! BlockPool.AddBlocks(blocks)
      case GetBlocks(locators) =>
        logger.debug(s"GetBlocks received: $locators")
        blockPool ! BlockPool.GetBlocks(locators)
        context become (awaitBlocks(connection) orElse forMessageHandler(connection))
    }
  }

  def awaitBlocks(connection: ActorRef): Receive = {
    case BlockPool.SendBlocks(blocks) =>
      connection ! envelope(Message(SendBlocks(blocks)))
      context.unbecome()
  }

  private var pingNonce: Int = 0

  def sendPing(connection: ActorRef): Unit = {
    if (pingNonce != 0) {
      logger.debug("No pong message received in time")
      context stop self
    } else {
      pingNonce = Random.nextInt()
      connection ! envelope(Message(Ping(pingNonce)))
      timers.startSingleTimer(MessageHandler, MessageHandler.SendPing, Network.pingFrequency)
    }
  }
}
