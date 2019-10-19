package org.alephium.flow.network.clique

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.util.Random

import akka.actor.{ActorRef, Props, Timers}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.crypto.Keccak256
import org.alephium.flow.core.{AllHandlers, BlockChainHandler, FlowHandler, HeaderChainHandler}
import org.alephium.flow.model.DataOrigin.Remote
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model._
import org.alephium.serde.{SerdeError, SerdeResult}
import org.alephium.util.{AVector, BaseActor}

object BrokerHandler {
  sealed trait Command
  case object SendPing extends Command

  sealed trait Event
  object Timer  extends Event
  object TcpAck extends Event with Tcp.Event

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

trait BrokerHandler extends HandShake with Relay {
  implicit def config: PlatformProfile

  def selfCliqueInfo: CliqueInfo
  def remote: InetSocketAddress
  def remoteCliqueId: CliqueId
  def remoteBroker: BrokerInfo
  def connection: ActorRef
  def allHandlers: AllHandlers

  def cliqueManager: ActorRef = context.parent

  def handle: Receive = handleSocketData orElse handleWrite orElse handleRelayEvent

  def uponHandshaked(): Unit = {
    cliqueManager ! CliqueManager.Connected(remoteCliqueId, remoteBroker)
    startPingPong()
    setPayloadHandler(handleRelayPayload)
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

trait HandShake extends ConnectionReader with ConnectionWriter {
  def config: PlatformProfile
  def selfCliqueInfo: CliqueInfo

  def handshakeOut(): Unit = {
    sendPayload(Hello(selfCliqueInfo.id, config.brokerInfo))
    setPayloadHandler(awaitHelloAck)
  }

  def handshakeIn(): Unit = {
    setPayloadHandler(awaitHello)
  }

  def awaitHello(payload: Payload): Unit = payload match {
    case hello: Hello =>
      sendPayload(HelloAck(selfCliqueInfo.id, config.brokerInfo))
      handleBrokerInfo(hello.cliqueId, hello.brokerInfo)
      uponHandshaked()
    case err =>
      log.info(s"Got ${err.getClass.getSimpleName}, expect Hello")
      stop()
  }

  def awaitHelloAck(payload: Payload): Unit = payload match {
    case helloAck: HelloAck =>
      handleBrokerInfo(helloAck.cliqueId, helloAck.brokerInfo)
      uponHandshaked()
    case err =>
      log.info(s"Got ${err.getClass.getSimpleName}, expect HelloAck")
      stop()
  }

  def handleBrokerInfo(remoteCliqueId: CliqueId, remoteBrokerInfo: BrokerInfo): Unit

  def uponHandshaked(): Unit
}

trait PingPong extends ConnectionWriter with ConnectionUtil with Timers {
  def config: PlatformProfile

  def connection: ActorRef

  private var pingNonce: Int = 0

  def handlePong(nonce: Int): Unit = {
    if (nonce == pingNonce) {
      log.debug("Pong received")
      pingNonce = 0
    } else {
      log.debug(s"Pong received with wrong nonce: expect $pingNonce, got $nonce")
      stop()
    }
  }

  def handlePing(nonce: Int, timestamp: Long): Unit = {
    // TODO: refuse ping if it's too frequent
    val delay = System.currentTimeMillis() - timestamp
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

trait MessageHandler extends BaseActor {
  implicit def config: PlatformProfile
  def allHandlers: AllHandlers
  def remote: InetSocketAddress
  def remoteCliqueId: CliqueId
  def remoteBroker: BrokerInfo

  def handleSendBlocks(blocks: AVector[Block]): Unit = {
    log.debug(s"Received #${blocks.length} blocks")
    blocks.foreach(handleNewBlock)
  }

  private def handleNewBlock(block: Block): Unit = {
    val chainIndex = block.chainIndex
    if (chainIndex.relateTo(config.brokerInfo)) {
      val handler = allHandlers.getBlockHandler(chainIndex)
      handler ! BlockChainHandler.AddBlock(block, Remote(remoteCliqueId, remoteBroker))
    } else {
      log.warning(s"Received block for wrong chain $chainIndex from $remote")
    }
  }

  def handleGetBlocks(locators: AVector[Keccak256]): Unit = {
    log.debug(s"GetBlocks received: #${locators.length}")
    allHandlers.flowHandler ! FlowHandler.GetBlocks(locators)
  }

  def handleSendHeaders(headers: AVector[BlockHeader]): Unit = {
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
  }

  def handleGetHeaders(locators: AVector[Keccak256]): Unit = {
    log.debug(s"GetHeaders received: ${locators.length}")
    allHandlers.flowHandler ! FlowHandler.GetHeaders(locators)
  }
}

trait P2PStage extends ConnectionReader with ConnectionWriter with PingPong with MessageHandler

trait Sync extends P2PStage {
  def remoteCliqueId: CliqueId
  def remoteBroker: BrokerInfo
  def flowHandler: ActorRef

  private var selfSynced       = false
  private var remoteSynced     = false
  private val numOfBlocksLimit = 128 // Each message can include upto this number of blocks

  def startSync(): Unit = {
    assert(!selfSynced)
    flowHandler ! FlowHandler.GetTips
    setPayloadHandler(handleSyncPayload)
  }

  def uponSynced(): Unit

  private def checkRemoteSynced(numNewBlocks: Int): Unit = {
    if (numNewBlocks < numOfBlocksLimit) {
      remoteSynced = true
    }
    if (selfSynced) {
      uponSynced()
    }
  }

  private def checkSelfSynced(numNewBlocks: Int): Unit = {
    if (numNewBlocks < numOfBlocksLimit) {
      selfSynced = true
    }
    if (remoteSynced) {
      uponSynced()
    }
  }

  def handleSyncEvents: Receive = {
    case FlowHandler.CurrentTips(tips) => sendPayload(GetBlocks(tips))
    case FlowHandler.BlocksLocated(blocks) =>
      sendPayload(SendBlocks(blocks))
      checkRemoteSynced(blocks.length)
    case BrokerHandler.SendPing      => sendPing()
    case event: Tcp.ConnectionClosed => stopDueto(event)
  }

  def handleSyncPayload(payload: Payload): Unit = payload match {
    case SendBlocks(blocks)     => handleSendBlocks(blocks); checkSelfSynced(blocks.length)
    case GetBlocks(locators)    => handleGetBlocks(locators)
    case SendHeaders(headers)   => handleSendHeaders(headers)
    case GetHeaders(locators)   => handleGetHeaders(locators)
    case Ping(nonce, timestamp) => handlePing(nonce, timestamp)
    case Pong(nonce)            => handlePong(nonce)
    case x                      =>
      // TODO: take care of misbehavior
      log.debug(s"Got unexpected payload type ${x.getClass.getSimpleName}")
  }
}

trait Relay extends P2PStage {
  def handleRelayEvent: Receive = {
    case BrokerHandler.SendPing      => sendPing()
    case event: Tcp.ConnectionClosed => stopDueto(event)
  }

  def handleRelayPayload(payload: Payload): Unit = payload match {
    case SendBlocks(blocks)     => handleSendBlocks(blocks)
    case GetBlocks(locators)    => handleGetBlocks(locators)
    case SendHeaders(headers)   => handleSendHeaders(headers)
    case GetHeaders(locators)   => handleGetHeaders(locators)
    case Ping(nonce, timestamp) => handlePing(nonce, timestamp)
    case Pong(nonce)            => handlePong(nonce)
    case x                      =>
      // TODO: take care of misbehavior
      log.debug(s"Got unexpected payload type ${x.getClass.getSimpleName}")
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

  def stopDueto(event: Tcp.ConnectionClosed): Unit = {
    if (event.isErrorClosed) {
      log.debug(s"Connection closed with error: ${event.getErrorCause}")
    } else {
      log.debug(s"Connection closed normally: $event")
    }
    context stop self
  }
}
