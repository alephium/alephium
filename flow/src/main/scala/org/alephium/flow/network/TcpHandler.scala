package org.alephium.flow.network

import java.net.InetSocketAddress
import java.time.Instant

import akka.actor.{ActorRef, Props, Timers}
import akka.io.{IO, Tcp}
import akka.util.ByteString
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.DataOrigin.Remote
import org.alephium.flow.storage.{AddBlockResult, AllHandlers, BlockChainHandler, FlowHandler}
import org.alephium.protocol.message._
import org.alephium.protocol.model.PeerId
import org.alephium.serde.NotEnoughBytesException
import org.alephium.util.{AVector, BaseActor}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

object TcpHandler {

  object Timer

  sealed trait Command
  case class Set(connection: ActorRef) extends Command
  case class Connect(until: Instant)   extends Command
  case object Retry                    extends Command
  case object SendPing                 extends Command

  def envelope(message: Message): Tcp.Write =
    Tcp.Write(Message.serializer.serialize(message))

  def deserialize(data: ByteString): Try[(AVector[Message], ByteString)] = {
    @tailrec
    def iter(rest: ByteString, acc: AVector[Message]): Try[(AVector[Message], ByteString)] = {
      Message.deserializer._deserialize(rest) match {
        case Success((message, newRest)) =>
          iter(newRest, acc :+ message)
        case Failure(_: NotEnoughBytesException) =>
          Success((acc, rest))
        case Failure(e) =>
          Failure(e)
      }
    }
    iter(data, AVector.empty)
  }

  trait Builder {
    def createTcpHandler(remote: InetSocketAddress, blockHandlers: AllHandlers)(
        implicit config: PlatformConfig): Props =
      Props(new TcpHandler(remote, blockHandlers))
  }
}

class TcpHandler(remote: InetSocketAddress, blockHandlers: AllHandlers)(
    implicit config: PlatformConfig)
    extends BaseActor
    with Timers {

  // Initialized once; use var for performance reason
  var connection: ActorRef = _
  var peerId: PeerId       = _

  def register(_connection: ActorRef): Unit = {
    connection = _connection
    connection ! Tcp.Register(self)
  }

  override def receive: Receive = awaitInit

  def awaitInit: Receive = {
    case TcpHandler.Set(_connection) =>
      register(_connection)
      handshakeOut()

    case TcpHandler.Connect(until: Instant) =>
      IO(Tcp)(context.system) ! Tcp.Connect(remote)
      context.become(connecting(until))
  }

  def handshakeOut(): Unit = {
    connection ! TcpHandler.envelope(Message(Hello(config.peerId)))
    context become handleWith(ByteString.empty, awaitHelloAck, handlePayload)
  }

  def handshakeIn(): Unit = {
    context become handleWith(ByteString.empty, awaitHello, handlePayload)
  }

  def awaitHello(payload: Payload): Unit = payload match {
    case hello: Hello =>
      if (hello.validate) {
        connection ! TcpHandler.envelope(Message(HelloAck(config.peerId)))
        context.parent ! PeerManager.Connected(hello.peerId, self)
        startPingPong()
      } else {
        log.info("Hello is invalid, closing connection")
        stop()
      }
    case err =>
      log.info(s"Got ${err.getClass.getSimpleName}, expect Hello")
      stop()
  }

  def awaitHelloAck(payload: Payload): Unit = payload match {
    case helloAck: HelloAck =>
      if (!helloAck.validate) {
        log.info("HelloAck is invalid, closing connection")
        stop()
      } else {
        context.parent ! PeerManager.Connected(helloAck.peerId, self)
        startPingPong()
      }
    case err =>
      log.info(s"Got ${err.getClass.getSimpleName}, expect HelloAck")
      stop()
  }

  def connecting(until: Instant): Receive = {
    case TcpHandler.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(remote)

    case _: Tcp.Connected =>
      val _connection = sender()
      register(_connection)
      handshakeIn()

    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val current = Instant.now()
      if (current isBefore until) {
        timers.startSingleTimer(TcpHandler.Timer, TcpHandler.Retry, 1.second)
      } else {
        log.info(s"Cannot connect to ${c.remoteAddress}")
        stop()
      }
  }

  def handleWith(unaligned: ByteString,
                 current: Payload => Unit,
                 next: Payload    => Unit): Receive = {
    handleEvent(unaligned, current, next) orElse handleOutMessage orElse handleInternal
  }

  def handleWith(unaligned: ByteString, handle: Payload => Unit): Receive = {
    handleEvent(unaligned, handle, handle) orElse handleOutMessage orElse handleInternal
  }

  def handleEvent(unaligned: ByteString, handle: Payload => Unit, next: Payload => Unit): Receive = {
    case Tcp.Received(data) =>
      TcpHandler.deserialize(unaligned ++ data) match {
        case Success((messages, rest)) =>
          messages.foreach { message =>
            val cmdName = message.payload.getClass.getSimpleName
            log.debug(s"Received message of cmd@$cmdName from $remote")
            handle(message.payload)
          }
          context.become(handleWith(rest, next))
        case Failure(_) =>
          log.info(s"Received corrupted data from $remote with serde exception; Closing connection")
          stop()
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
  }

  def handlePayload(payload: Payload): Unit = payload match {
    case Hello(_, _, _) =>
      ???
    case HelloAck(_, _, _) =>
      ???
    case Ping(nonce, timestamp) =>
      val delay = System.currentTimeMillis() - timestamp
      handlePing(nonce, delay)
    case Pong(nonce) =>
      if (nonce == pingNonce) {
        log.debug("Pong received, no response")
        pingNonce = 0
      } else {
        log.debug(s"Pong received with wrong nonce: expect $pingNonce, got $nonce")
        stop()
      }
    case SendBlocks(blocks) =>
      log.debug(s"Received #${blocks.length} blocks")
      val block      = blocks.head
      val chainIndex = block.chainIndex
      val handler    = blockHandlers.getBlockHandler(chainIndex)

      handler ! BlockChainHandler.AddBlocks(blocks, Remote(remote))
    case GetBlocks(locators) =>
      log.debug(s"GetBlocks received: #${locators.length}")
      blockHandlers.flowHandler ! FlowHandler.GetBlocksAfter(locators)
    case SendHeaders(headers) =>
      log.debug(s"Received #${headers.length} block headers")
      ???
    case GetHeaders(locators) =>
      log.debug(s"GetHeaders received: ${locators.length}")
      ???
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
      stop()
    } else {
      pingNonce = Random.nextInt()
      val timestamp = System.currentTimeMillis()
      connection ! TcpHandler.envelope(Message(Ping(pingNonce, timestamp)))
    }
  }

  def startPingPong(): Unit = {
    timers.startSingleTimer(TcpHandler.Timer, TcpHandler.SendPing, config.pingFrequency)
  }

  def stop(): Unit = {
    if (connection != null) {
      connection ! Tcp.Close
    }
    context stop self
  }
}
