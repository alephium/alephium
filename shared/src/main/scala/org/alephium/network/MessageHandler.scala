package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props, Timers}
import org.alephium.constant.Network
import org.alephium.protocol.message._
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.storage.BlockHandler.BlockOrigin.Remote
import org.alephium.storage.{AddBlockResult, BlockHandler, BlockHandlers}
import org.alephium.util.BaseActor

import scala.util.Random

object MessageHandler {
  def props(remote: InetSocketAddress, connection: ActorRef, blockHandlers: BlockHandlers): Props =
    Props(new MessageHandler(remote, connection, blockHandlers))

  object Timer

  sealed trait Command
  case object SendPing extends Command
}

class MessageHandler(remote: InetSocketAddress, connection: ActorRef, blockHandlers: BlockHandlers)
    extends BaseActor
    with Timers {
  val tcpHandler = context.parent

  override def receive: Receive = handlePayload orElse handleInternal orElse awaitSendPing

  def handlePayload: Receive = {
    case Ping(nonce, timestamp) =>
      // TODO: refuse ping if it's too frequent
      val delay = System.currentTimeMillis() - timestamp
      log.info(s"Ping received with ${delay}ms delay, response with pong")
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
      val block      = blocks.head
      val chainIndex = ChainIndex.fromHash(block.hash)
      val handler    = blockHandlers.getHandler(chainIndex)

      handler ! BlockHandler.AddBlocks(blocks, Remote(remote))
    case GetBlocks(locators) =>
      log.debug(s"GetBlocks received: $locators")
      blockHandlers.globalHandler.tell(BlockHandler.GetBlocksAfter(locators), tcpHandler)
  }

  def handleInternal: Receive = {
    case _: AddBlockResult =>
      () // TODO: handle error
//    case BlockHandler.SendBlocksAfter(_, blocks) =>
//      connection ! TcpHandler.envelope(Message(SendBlocks(blocks)))
  }

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
      val timestamp = System.currentTimeMillis()
      connection ! TcpHandler.envelope(Message(Ping(pingNonce, timestamp)))
      timers.startSingleTimer(MessageHandler.Timer, MessageHandler.SendPing, Network.pingFrequency)
    }
  }
}
