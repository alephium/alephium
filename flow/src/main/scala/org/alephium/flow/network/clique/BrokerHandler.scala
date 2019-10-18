package org.alephium.flow.network.clique

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.util.Random

import akka.actor.{ActorRef, Props, Timers}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.flow.core.{AllHandlers, BlockChainHandler, FlowHandler, HeaderChainHandler}
import org.alephium.flow.model.DataOrigin.Remote
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}
import org.alephium.serde.{SerdeError, SerdeResult}
import org.alephium.util.{AVector, BaseActor}

object BrokerHandler {
  object Timer
  object TcpAck extends Tcp.Event

  sealed trait Command
  case object SendPing extends Command

  def envelope(payload: Payload): Tcp.Write =
    envelope(Message(payload))

  def envelope(message: Message): Tcp.Write =
    Tcp.Write(Message.serialize(message))

  def deserialize(data: ByteString)(
      implicit config: GroupConfig): SerdeResult[(AVector[Message], ByteString)] = {
    @tailrec
    def iter(rest: ByteString,
             acc: AVector[Message]): SerdeResult[(AVector[Message], ByteString)] = {
      Message._deserialize(rest) match {
        case Right((message, newRest)) =>
          iter(newRest, acc :+ message)
        case Left(_: SerdeError.NotEnoughBytes) =>
          Right((acc, rest))
        case Left(e) =>
          Left(e)
      }
    }
    iter(data, AVector.empty)
  }

  trait Builder {
    def createInboundBrokerHandler(
        selfCliqueInfo: CliqueInfo,
        remote: InetSocketAddress,
        connection: ActorRef,
        blockHandlers: AllHandlers)(implicit config: PlatformProfile): Props =
      Props(new InboundBrokerHandler(selfCliqueInfo, remote, connection, blockHandlers))

    def createOutboundBrokerHandler(
        selfCliqueInfo: CliqueInfo,
        remoteCliqueId: CliqueId,
        remoteBroker: BrokerInfo,
        blockHandlers: AllHandlers)(implicit config: PlatformProfile): Props =
      Props(new OutboundBrokerHandler(selfCliqueInfo, remoteCliqueId, remoteBroker, blockHandlers))
  }
}

trait BrokerHandler extends ConnectionReader with ConnectionWriter with PingPong with Timers {

  implicit def config: PlatformProfile

  def selfCliqueInfo: CliqueInfo
  def remote: InetSocketAddress
  def remoteCliqueId: CliqueId
  def remoteBroker: BrokerInfo
  def connection: ActorRef
  def allHandlers: AllHandlers

  def cliqueManager: ActorRef = context.parent

  def handle: Receive = handleSocketData orElse handleWrite orElse handleEvent

  def handshakeOut(): Unit = {
    sendPayload(Hello(selfCliqueInfo.id, config.brokerInfo))
    setPayloadHandler(awaitHelloAck)
  }

  def handshakeIn(): Unit = {
    setPayloadHandler(awaitHello)
  }

  def awaitHello(payload: Payload): Unit = payload match {
    case hello: Hello =>
      connection ! BrokerHandler.envelope(HelloAck(selfCliqueInfo.id, config.brokerInfo))
      handleBrokerInfo(hello.cliqueId, hello.brokerInfo)
      afterHandShake()
    case err =>
      log.info(s"Got ${err.getClass.getSimpleName}, expect Hello")
      stop()
  }

  def awaitHelloAck(payload: Payload): Unit = payload match {
    case helloAck: HelloAck =>
      handleBrokerInfo(helloAck.cliqueId, helloAck.brokerInfo)
      afterHandShake()
    case err =>
      log.info(s"Got ${err.getClass.getSimpleName}, expect HelloAck")
      stop()
  }

  def handleBrokerInfo(remoteCliqueId: CliqueId, remoteBrokerInfo: BrokerInfo): Unit

  def afterHandShake(): Unit = {
    cliqueManager ! CliqueManager.Connected(remoteCliqueId, remoteBroker)
    startPingPong()
    setPayloadHandler(handlePayload)
  }

  def handleEvent: Receive = {
    case BrokerHandler.SendPing => sendPing()
    case event: Tcp.ConnectionClosed =>
      if (event.isErrorClosed) {
        log.debug(s"Connection closed with error: ${event.getErrorCause}")
      } else {
        log.debug(s"Connection closed normally: $event")
      }
      context stop self
  }

  def handlePayload(payload: Payload): Unit = payload match {
    case Ping(nonce, timestamp) =>
      val delay = System.currentTimeMillis() - timestamp
      handlePing(nonce, delay)
    case Pong(nonce) =>
      uponNewNonce(nonce)
    case SendBlocks(blocks) =>
      log.debug(s"Received #${blocks.length} blocks")
      // TODO: support many blocks
      val block      = blocks.head
      val chainIndex = block.chainIndex
      if (chainIndex.relateTo(config.brokerInfo)) {
        val handler = allHandlers.getBlockHandler(chainIndex)
        handler ! BlockChainHandler.AddBlocks(blocks, Remote(remoteCliqueId, remoteBroker))
      } else {
        log.warning(s"Received blocks for wrong chain $chainIndex from $remote")
      }
    case GetBlocks(locators) =>
      log.debug(s"GetBlocks received: #${locators.length}")
      allHandlers.flowHandler ! FlowHandler.GetBlocks(locators)
    case SendHeaders(headers) =>
      log.debug(s"Received #${headers.length} block headers")
      // TODO: support many headers
      val header     = headers.head
      val chainIndex = header.chainIndex
      if (!chainIndex.relateTo(config.brokerInfo)) {
        val handler = allHandlers.getHeaderHandler(chainIndex)
        handler ! HeaderChainHandler.AddHeaders(headers, Remote(remoteCliqueId, remoteBroker))
      } else {
        log.warning(s"Received headers for wrong chain from $remote")
      }
    case GetHeaders(locators) =>
      log.debug(s"GetHeaders received: ${locators.length}")
      allHandlers.flowHandler ! FlowHandler.GetHeaders(locators)
    case _ =>
      log.warning(s"Got unexpected payload type")
  }
}

trait ConnectionWriter extends BaseActor {
  def connection: ActorRef

  private var isWaitingAck   = false
  private val messagesToSent = collection.mutable.Queue.empty[ByteString]

  private def send(message: ByteString): Unit = {
    assert(!isWaitingAck)
    isWaitingAck = true
    connection ! Tcp.Write(message, BrokerHandler.TcpAck)
  }

  private def trySend(message: ByteString): Unit = {
    if (isWaitingAck) {
      messagesToSent.enqueue(message)
    } else {
      send(message)
    }
  }

  def sendPayload(payload: Payload): Unit = {
    val message = Message.serialize(payload)
    trySend(message)
  }

  def handleWrite: Receive = {
    case message: ByteString => // We use ByteString here for efficiency reasons
      trySend(message)
    case BrokerHandler.TcpAck =>
      assert(isWaitingAck)
      isWaitingAck = false
      if (messagesToSent.nonEmpty) {
        send(messagesToSent.dequeue())
      }
  }
}

trait ConnectionReader extends BaseActor with ConnectionUtil {
  implicit def config: GroupConfig

  def remote: InetSocketAddress

  private var unaligned: ByteString = ByteString.empty
  private var payloadHandler: Payload => Unit = (_ => ())

  def getPayloadHandler(): Payload => Unit = payloadHandler

  def setPayloadHandler(handler: Payload => Unit): Unit = payloadHandler = handler

  def handleSocketData: Receive = {
    case Tcp.Received(data) =>
      BrokerHandler.deserialize(unaligned ++ data) match {
        case Right((messages, rest)) =>
          messages.foreach { message =>
            val cmdName = message.payload.getClass.getSimpleName
            log.debug(s"Received message of cmd@$cmdName from $remote")
            getPayloadHandler()(message.payload)
          }
          unaligned = rest
        case Left(e) =>
          log.info(
            s"Received corrupted data from $remote; error: ${e.toString}; Closing connection")
          stop()
      }
  }
}

trait PingPong extends ConnectionWriter with ConnectionUtil with Timers {
  def config: PlatformProfile

  def connection: ActorRef

  private var pingNonce: Int = 0

  def uponNewNonce(nonce: Int): Unit = {
    if (nonce == pingNonce) {
      log.debug("Pong received")
      pingNonce = 0
    } else {
      log.debug(s"Pong received with wrong nonce: expect $pingNonce, got $nonce")
      stop()
    }
  }

  def handlePing(nonce: Int, delay: Long): Unit = {
    // TODO: refuse ping if it's too frequent
    log.info(s"Ping received with ${delay}ms delay; Replying with Pong")
    connection ! BrokerHandler.envelope(Pong(nonce))
  }

  def sendPing(): Unit = {
    if (pingNonce != 0) {
      log.debug("No Pong message received in time")
      stop()
    } else {
      pingNonce = Random.nextInt()
      val timestamp = System.currentTimeMillis()
      sendPayload(Ping(pingNonce, timestamp))
    }
  }

  def startPingPong(): Unit = {
    timers.startPeriodicTimer(BrokerHandler.Timer, BrokerHandler.SendPing, config.pingFrequency)
  }
}

trait ConnectionUtil extends BaseActor {
  def connection: ActorRef

  def stop(): Unit = {
    if (connection != null) {
      connection ! Tcp.Close
    }
    context stop self
  }
}
