package org.alephium.flow.network

import java.net.InetSocketAddress
import java.time.Instant

import akka.actor.{ActorRef, Props, Timers}
import akka.io.{IO, Tcp}
import akka.util.ByteString
import org.alephium.flow.constant.Network
import org.alephium.flow.model.ChainIndex
import org.alephium.protocol.message._
import org.alephium.serde.NotEnoughBytesException
import org.alephium.flow.storage.ChainHandler.BlockOrigin.Remote
import org.alephium.flow.storage.{AddBlockResult, BlockHandlers, ChainHandler, FlowHandler}
import org.alephium.util.BaseActor

import scala.annotation.tailrec
import scala.util.{Failure, Random, Success, Try}
import scala.concurrent.duration._

object TcpHandler {

  object Timer

  sealed trait Command
  case class Set(connection: ActorRef) extends Command
  case class Connect(until: Instant)   extends Command
  case object Retry                    extends Command
  case object SendPing                 extends Command

  def envelope(message: Message): Tcp.Write =
    Tcp.Write(Message.serializer.serialize(message))

  def deserialize(data: ByteString): Try[(Seq[Message], ByteString)] = {
    @tailrec
    def iter(rest: ByteString, acc: Seq[Message]): Try[(Seq[Message], ByteString)] = {
      Message.deserializer._deserialize(rest) match {
        case Success((message, newRest)) =>
          iter(newRest, acc :+ message)
        case Failure(_: NotEnoughBytesException) =>
          Success((acc, rest))
        case Failure(e) =>
          Failure(e)
      }
    }
    iter(data, Seq.empty)
  }

  trait Builder {
    def createTcpHandler(remote: InetSocketAddress, blockHandlers: BlockHandlers): Props =
      Props(new TcpHandler(remote, blockHandlers))
  }
}

class TcpHandler(remote: InetSocketAddress, blockHandlers: BlockHandlers)
    extends BaseActor
    with Timers {

  // Initialized once; use var for performance reason
  var connection: ActorRef = _

  override def receive: Receive = awaitInit

  def awaitInit: Receive = {
    case TcpHandler.Set(_connection) =>
      startWith(_connection)

    case TcpHandler.Connect(until: Instant) =>
      IO(Tcp)(context.system) ! Tcp.Connect(remote)
      context.become(connecting(until))
  }

  def connecting(until: Instant): Receive = {
    case TcpHandler.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(remote)

    case _: Tcp.Connected =>
      val _connection = sender()
      startWith(_connection)
      context.parent ! PeerManager.Connected(remote, self)

    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val current = Instant.now()
      if (current isBefore until) {
        timers.startSingleTimer(TcpHandler.Timer, TcpHandler.Retry, 1.second)
      } else {
        log.info(s"Cannot connect to ${c.remoteAddress}")
        context.stop(self)
      }
  }

  def startWith(_connection: ActorRef): Unit = {
    connection = _connection
    connection ! Tcp.Register(self)
    sendPing()
    context.become(handleWith(ByteString.empty))
  }

  def handleWith(unaligned: ByteString): Receive = {
    handleEvent(unaligned) orElse handleOutMessage orElse handleInternal
  }

  def handleEvent(unaligned: ByteString): Receive = {
    case Tcp.Received(data) =>
      TcpHandler.deserialize(unaligned ++ data) match {
        case Success((messages, rest)) =>
          messages.foreach { message =>
            log.debug(s"Received message of cmd@${message.header.cmdCode} from $remote")
            handlePayload(message.payload)
          }
          context.become(handleWith(rest))
        case Failure(_) =>
          log.info(s"Received corrupted data from $remote with serde exception; Close connection")
          context stop self
      }
    case TcpHandler.SendPing => sendPing()
    case event: Tcp.ConnectionClosed =>
      if (event.isErrorClosed) {
        log.debug(s"Connection closed with error: ${event.getErrorCause}")
      } else {
        log.debug(s"Connection closed normally: $event")
      }
      context stop self
  }

  def handleOutMessage: Receive = {
    case message: Message =>
      connection ! TcpHandler.envelope(message)
    case write: Tcp.Write =>
      connection ! write
  }

  def handleInternal: Receive = {
    case _: AddBlockResult =>
      () // TODO: handle error
    //    case BlockHandler.SendBlocksAfter(_, blocks) =>
    //      connection ! TcpHandler.envelope(Message(SendBlocks(blocks)))
  }

  def handlePayload(payload: Payload): Unit = payload match {
    case Ping(nonce, timestamp) =>
      val delay = System.currentTimeMillis() - timestamp
      handlePing(nonce, delay)
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

      handler ! ChainHandler.AddBlocks(blocks, Remote(remote))
    case GetBlocks(locators) =>
      log.debug(s"GetBlocks received: $locators")
      blockHandlers.flowHandler ! FlowHandler.GetBlocksAfter(locators)
  }

  private var pingNonce: Int = 0

  def handlePing(nonce: Int, delay: Long): Unit = {
    // TODO: refuse ping if it's too frequent
    log.info(s"Ping received with ${delay}ms delay, response with pong")
    connection ! TcpHandler.envelope(Message(Pong(nonce)))
  }

  def sendPing(): Unit = {
    if (pingNonce != 0) {
      log.debug("No pong message received in time")
      context stop self
    } else {
      pingNonce = Random.nextInt()
      val timestamp = System.currentTimeMillis()
      connection ! TcpHandler.envelope(Message(Ping(pingNonce, timestamp)))
      timers.startSingleTimer(TcpHandler.Timer, TcpHandler.SendPing, Network.pingFrequency)
    }
  }
}
