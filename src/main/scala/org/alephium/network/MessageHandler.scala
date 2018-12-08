package org.alephium.network

import akka.actor.{ActorRef, Props, Timers}
import org.alephium.constant.Network
import org.alephium.protocol.message._
import org.alephium.storage.BlockHandler
import org.alephium.util.BaseActor

import scala.util.Random

object MessageHandler {
  def props(connection: ActorRef, blockHandler: ActorRef): Props =
    Props(new MessageHandler(connection, blockHandler))

  sealed trait Command
  case object SendPing extends Command
}

class MessageHandler(connection: ActorRef, blockHandler: ActorRef) extends BaseActor with Timers {
  val tcpHandler = context.parent

  override def receive: Receive = handlePayload orElse awaitSendPing

  def handlePayload: Receive = {
    case Ping(nonce) =>
      // TODO: refuse ping if it's too frequent
      log.debug("Ping received, response with pong")
      connection ! TcpHandler.envelope(Message(Pong(nonce)))
    case Pong(nonce) =>
      if (nonce == pingNonce) {
        log.debug("Pong received, no response")
        pingNonce = 0
      } else {
        log.debug(s"Pong received with wrong nonce: expect $pingNonce, got $nonce")
        context stop self
      }
    case SendBlocks(blocks) =>
      log.debug(s"Received #${blocks.size} blocks")
      blockHandler.tell(BlockHandler.AddBlocks(blocks), tcpHandler)
    case GetBlocks(locators) =>
      log.debug(s"GetBlocks received: $locators")
      blockHandler.tell(BlockHandler.GetBlocksAfter(locators), tcpHandler)
  }
//
//  def handleInternal: Receive = {
//    case BlockHandler.SendBlocksAfter(_, blocks) =>
//      connection ! TcpHandler.envelope(Message(SendBlocks(blocks)))
//  }

  def awaitSendPing: Receive = {
    case MessageHandler.SendPing =>
      sendPing()
  }

  private var pingNonce: Int = 0

  def sendPing(): Unit = {
    if (pingNonce != 0) {
      log.debug("No pong message received in time")
      context stop self
    } else {
      pingNonce = Random.nextInt()
      connection ! TcpHandler.envelope(Message(Ping(pingNonce)))
      timers.startSingleTimer(MessageHandler, MessageHandler.SendPing, Network.pingFrequency)
    }
  }
}
