package org.alephium.flow.network

import java.net.InetSocketAddress
import java.time.Instant

import akka.actor.{ActorRef, Props, Timers}
import akka.io.{IO, Tcp}
import akka.util.ByteString
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.DataOrigin.Remote
import org.alephium.flow.network.PeerManager.PeerInfo
import org.alephium.flow.storage._
import org.alephium.protocol.message._
import org.alephium.protocol.model.{GroupIndex, PeerId}
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

  def envelope(payload: Payload): Tcp.Write =
    envelope(Message(payload))

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

class TcpHandler(remote: InetSocketAddress, allHandlers: AllHandlers)(
    implicit config: PlatformConfig)
    extends BaseActor
    with Timers {

  // Initialized once; use var for performance reason
  var connection: ActorRef = _
  var peerInfo: PeerInfo   = _

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
    connection ! TcpHandler.envelope(Hello(config.peerId))
    context become handleWith(ByteString.empty, awaitHelloAck, handlePayload)
  }

  def handshakeIn(): Unit = {
    context become handleWith(ByteString.empty, awaitHello, handlePayload)
  }

  def awaitHello(payload: Payload): Unit = payload match {
    case hello: Hello =>
      if (hello.validate) {
        connection ! TcpHandler.envelope(HelloAck(config.peerId))
        afterHandShake(hello.peerId)
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
      if (helloAck.validate) {
        afterHandShake(helloAck.peerId)
      } else {
        log.info("HelloAck is invalid, closing connection")
        stop()
      }
    case err =>
      log.info(s"Got ${err.getClass.getSimpleName}, expect HelloAck")
      stop()
  }

  def afterHandShake(peerId: PeerId): Unit = {
    peerInfo = PeerInfo(peerId, remote, self)
    context.parent ! PeerManager.Connected(peerId, peerInfo)
    startPingPong()
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

  // TODO: make this safe by using types
  def handleOutMessage: Receive = {
    case message: Message =>
      connection ! TcpHandler.envelope(message)
    case write: Tcp.Write =>
      connection ! write
  }

  def handleInternal: Receive = {
    case _: AddBlockResult =>
      () // TODO: handle error
    case _: AddBlockHeaderResult =>
      () // TODO: handle error
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
        stop()
      }
    case SendBlocks(blocks) =>
      log.debug(s"Received #${blocks.length} blocks")
      // TODO: support many blocks
      val block      = blocks.head
      val chainIndex = block.chainIndex
      val handler    = allHandlers.getBlockHandler(chainIndex)
      handler ! BlockChainHandler.AddBlocks(blocks, Remote(peerInfo.id))
    case GetBlocks(locators) =>
      log.debug(s"GetBlocks received: #${locators.length}")
      allHandlers.flowHandler ! FlowHandler.GetBlocksAfter(locators)
    case SendHeaders(headers) =>
      log.debug(s"Received #${headers.length} block headers")
      // TODO: support many headers
      val header     = headers.head
      val chainIndex = header.chainIndex
      if (chainIndex.from != config.mainGroup.value) {
        if (chainIndex.to == config.mainGroup.value) {
          val message = TcpHandler.envelope(GetBlocks(AVector(header.hash)))
          val group   = GroupIndex(chainIndex.from)
          context.parent ! PeerManager.Send(message, group)
        } else {
          val handler = allHandlers.getHeaderHandler(chainIndex)
          handler ! HeaderChainHandler.AddHeaders(headers, Remote(peerInfo.id))
        }
      } // else ignore since the header comes from this node
    case GetHeaders(locators) =>
      log.debug(s"GetHeaders received: ${locators.length}")
      allHandlers.flowHandler ! FlowHandler.GetHeadersAfter(locators)
    case _ =>
      log.warning(s"Got unexpected payload type")
  }

  private var pingNonce: Int = 0

  def handlePing(nonce: Int, delay: Long): Unit = {
    // TODO: refuse ping if it's too frequent
    log.info(s"Ping received with ${delay}ms delay, response with pong")
    connection ! TcpHandler.envelope(Pong(nonce))
  }

  def sendPing(): Unit = {
    if (pingNonce != 0) {
      log.debug("No pong message received in time")
      stop()
    } else {
      pingNonce = Random.nextInt()
      val timestamp = System.currentTimeMillis()
      connection ! TcpHandler.envelope(Ping(pingNonce, timestamp))
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
