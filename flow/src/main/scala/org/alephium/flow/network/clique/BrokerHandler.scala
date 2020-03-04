package org.alephium.flow.network.clique

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Random

import akka.actor.{ActorRef, Props, Timers}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.crypto.Keccak256
import org.alephium.flow.Utils
import org.alephium.flow.core._
import org.alephium.flow.core.validation.Validation
import org.alephium.flow.model.DataOrigin._
import org.alephium.flow.network.{CliqueManager, InterCliqueManager}
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model._
import org.alephium.serde.{SerdeError, SerdeResult}
import org.alephium.util.{AVector, BaseActor, Forest}

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

trait BrokerHandler extends HandShake with Relay with Sync {
  def cliqueManager: ActorRef = context.parent

  def handleBrokerInfo(remoteCliqueId: CliqueId, remoteBrokerInfo: BrokerInfo): Unit

  override def uponHandshaked(remoteCliqueId: CliqueId, remoteBrokerInfo: BrokerInfo): Unit = {
    log.debug(s"Handshaked with remote $remote - $remoteCliqueId")
    handleBrokerInfo(remoteCliqueId, remoteBrokerInfo)
    cliqueManager ! CliqueManager.Connected(remoteCliqueId, remoteBrokerInfo)
    startPingPong()

    val isSameClique  = remoteCliqueId == selfCliqueInfo.id
    val isIntersected = remoteBrokerInfo.intersect(config.brokerInfo)
    if (!isSameClique && isIntersected) {
      log.info(s"Start syncing with ${remoteBrokerInfo.address}")
      startSync()
    } else if (isSameClique && !isIntersected) {
      log.debug(s"Start relaying with ${remoteBrokerInfo.address}")
      startRelay()
    } else {
      log.warning(s"Invalid connection from $remoteCliqueId - $remoteBrokerInfo")
      stop()
    }
  }

  override def uponSynced(): Unit = {
    log.debug(s"Synced with remote $remote - $remoteCliqueId, start relaying")
    startRelay()
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

  def trySend(message: ByteString): Unit = {
    if (isWaitingAck) {
      messagesToSent.enqueue(message)
    } else {
      send(message)
    }
  }

  def trySendBuffered(): Unit = {
    assert(isWaitingAck)
    isWaitingAck = false
    if (messagesToSent.nonEmpty) {
      send(messagesToSent.dequeue())
    }
  }

  def sendPayload(payload: Payload): Unit = {
    val message = Message.serialize(payload)
    trySend(message)
  }
}

trait ConnectionReader extends BaseActor with ConnectionUtil {
  implicit def config: GroupConfig

  def remote: InetSocketAddress

  private var unaligned: ByteString                 = ByteString.empty
  def setUnaligned(bs: ByteString): Unit            = unaligned = bs
  def useUnaligned(newData: ByteString): ByteString = unaligned ++ newData

  type PayloadHandler = Payload => Unit
  private var payloadHandler: PayloadHandler           = _
  def getPayloadHandler(): PayloadHandler              = payloadHandler
  def setPayloadHandler(handler: PayloadHandler): Unit = payloadHandler = handler
}

trait ConnectionReaderWriter extends ConnectionReader with ConnectionWriter {
  def handleReadWrite: Receive = {
    case Tcp.Received(data) =>
      BrokerHandler.deserialize(useUnaligned(data)) match {
        case Right((messages, rest)) =>
          messages.foreach { message =>
            val cmdName = message.payload.getClass.getSimpleName
            log.debug(s"Received message of cmd@$cmdName from $remote")
            getPayloadHandler()(message.payload)
          }
          setUnaligned(rest)
        case Left(e) =>
          log.info(
            s"Received corrupted data from $remote; error: ${e.toString}; Closing connection")
          stop()
      }

    // We use ByteString here to avoid duplicated serialization
    case message: ByteString         => trySend(message)
    case BrokerHandler.TcpAck        => trySendBuffered()
    case event: Tcp.ConnectionClosed => stopDueto(event)
  }
}

trait HandShake extends ConnectionReaderWriter {
  def config: PlatformProfile
  def selfCliqueInfo: CliqueInfo

  def handshakeOut(): Unit = {
    sendPayload(Hello(selfCliqueInfo.id, config.brokerInfo))
    setPayloadHandler(awaitHelloAck)
    context become handleReadWrite
  }

  def handshakeIn(): Unit = {
    setPayloadHandler(awaitHello)
    context become handleReadWrite
  }

  def awaitHello(payload: Payload): Unit = payload match {
    case hello: Hello =>
      sendPayload(HelloAck(selfCliqueInfo.id, config.brokerInfo))
      uponHandshaked(hello.cliqueId, hello.brokerInfo)
    case err =>
      log.info(s"Got ${err.getClass.getSimpleName}, expect Hello")
      stop()
  }

  def awaitHelloAck(payload: Payload): Unit = payload match {
    case helloAck: HelloAck =>
      uponHandshaked(helloAck.cliqueId, helloAck.brokerInfo)
    case err =>
      log.info(s"Got ${err.getClass.getSimpleName}, expect HelloAck")
      stop()
  }

  def uponHandshaked(remoteCliqueId: CliqueId, remoteBrokerInfo: BrokerInfo): Unit
}

trait PingPong extends ConnectionReaderWriter with ConnectionUtil with Timers {
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
    timers.startPeriodicTimer(BrokerHandler.Timer,
                              BrokerHandler.SendPing,
                              config.pingFrequency.asScala)
  }
}

trait MessageHandler extends BaseActor {
  implicit def config: PlatformProfile
  def allHandlers: AllHandlers
  def remote: InetSocketAddress
  def remoteCliqueId: CliqueId
  def remoteBrokerInfo: BrokerInfo
  def selfCliqueInfo: CliqueInfo

  def origin: FromClique = {
    InterClique(remoteCliqueId, remoteBrokerInfo, isSyncing)
  }

  private var _isSyncing: Boolean = false
  def isSyncing: Boolean          = _isSyncing
  def setSyncOn(): Unit           = _isSyncing = true
  def setSyncOff(): Unit          = _isSyncing = false

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def handleSendBlocks(blocks: AVector[Block],
                       notifyListOpt: Option[mutable.HashSet[ChainIndex]]): Unit = {
    assert(blocks.nonEmpty)
    log.debug(s"Received #${blocks.length} blocks ${Utils.showHashableV(blocks)}")
    Validation.validateFlowDAG(blocks) match {
      case Some(forests) =>
        notifyListOpt.foreach(_ ++= forests.map(_.roots.head.value.chainIndex).toIterable)
        forests.foreach(handleNewBlocks)
      case None =>
        log.warning(s"Received blocks that are not from subtrees of DAG")
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  private def handleNewBlocks(forest: Forest[Keccak256, Block]): Unit = {
    assert(forest.nonEmpty)
    val chainIndex = forest.roots.head.value.chainIndex
    if (chainIndex.relateTo(config.brokerInfo)) {
      val handler = allHandlers.getBlockHandler(chainIndex)
      handler ! BlockChainHandler.AddBlocks(forest, origin)
    } else {
      log.warning(s"Received block for wrong chain $chainIndex from $remote")
    }
  }

  def handleGetBlocks(locators: AVector[Keccak256]): Unit = {
    log.debug(s"GetBlocks received: #${Utils.show(locators)}")
    allHandlers.flowHandler ! FlowHandler.GetBlocks(locators)
  }

  def handleSendHeaders(headers: AVector[BlockHeader]): Unit = {
    log.debug(s"Received #${headers.length} block headers ${Utils.showHashableV(headers)}")
    headers.foreach(handleNewHeader)
  }

  private def handleNewHeader(header: BlockHeader): Unit = {
    val chainIndex = header.chainIndex
    if (!chainIndex.relateTo(config.brokerInfo)) {
      val handler = allHandlers.getHeaderHandler(chainIndex)
      handler ! HeaderChainHandler.addOneHeader(header, origin)
    } else {
      log.warning(s"Received header ${header.shortHex} for wrong chain from $remote")
    }
  }

  def handleGetHeaders(locators: AVector[Keccak256]): Unit = {
    log.debug(s"GetHeaders received: ${Utils.show(locators)}")
    allHandlers.flowHandler ! FlowHandler.GetHeaders(locators)
  }

  def handleSendTxs(txs: AVector[Transaction]): Unit = {
    log.debug(s"SendTxs received: ${Utils.show(txs.map(_.hash))}")
    txs.foreach(tx => allHandlers.txHandler ! TxHandler.AddTx(tx, origin))
  }
}

trait P2PStage extends ConnectionReaderWriter with PingPong with MessageHandler {
  def handleCommonEvents: Receive = {
    case BlockChainHandler.BlocksAddingFailed =>
      log.debug(s"stop broker handler due to failures in adding blocks")
      stop()
    case BlockChainHandler.InvalidBlocks =>
      log.debug(s"stop broker handler due to invalid blocks")
      stop()
    case HeaderChainHandler.HeadersAddingFailed =>
      log.debug(s"stop broker handler due to failures in adding headers")
    case HeaderChainHandler.InvalidHeaders =>
      log.debug(s"stop broker handler due to invalid headers")
    case BrokerHandler.SendPing =>
      sendPing()
  }
}

trait Sync extends P2PStage {
  def cliqueManager: ActorRef
  def flowHandler: ActorRef = allHandlers.flowHandler

  private var selfSynced   = false
  private var remoteSynced = false

  private val blockNotifyList = scala.collection.mutable.HashSet.empty[ChainIndex]

  def startSync(): Unit = {
    assert(!selfSynced)
    flowHandler ! FlowHandler.GetTips(remoteBrokerInfo)
    setPayloadHandler(handleSyncPayload)
    context become (handleReadWrite orElse handleSyncEvents orElse handleCommonEvents)
    setSyncOn()
    cliqueManager ! InterCliqueManager.Syncing(remoteCliqueId)
  }

  def uponSynced(): Unit

  private def checkRemoteSynced(numNewBlocks: Int): Unit = {
    assert(!remoteSynced)
    if (numNewBlocks == 0) {
      remoteSynced = true
    }
    checkSynced()
  }

  private def checkSelfSynced(numNewBlocks: Int): Unit = {
    assert(!selfSynced)
    if (numNewBlocks == 0) {
      selfSynced = true
    }
    checkSynced()
  }

  private def checkSynced(): Unit = {
    if (selfSynced && remoteSynced) {
      cliqueManager ! InterCliqueManager.Synced(remoteCliqueId)
      uponSynced()
    }
  }

  def handleSyncEvents: Receive = {
    case FlowHandler.CurrentTips(tips) =>
      log.debug(s"ask blocks from these tips ${Utils.show(tips)}")
      sendPayload(GetBlocks(tips))
    case FlowHandler.BlocksLocated(blocks) =>
      log.debug(s"send blocks after remote's tips ${Utils.showHashableV(blocks)}")
      sendPayload(SendBlocks(blocks))
      checkRemoteSynced(blocks.length)
    case BlockChainHandler.BlocksAdded(chainIndex) =>
      assert(blockNotifyList.contains(chainIndex))
      log.debug(s"all the blocks sent for $chainIndex are added")
      blockNotifyList -= chainIndex
      if (blockNotifyList.isEmpty) {
        allHandlers.flowHandler ! FlowHandler.GetTips(remoteBrokerInfo)
      }
    case HeaderChainHandler.HeadersAdded(chainIndex) =>
      log.debug(s"all the blocks sent for $chainIndex are added")
  }

  def handleSyncPayload(payload: Payload): Unit = payload match {
    case SendBlocks(blocks) =>
      if (blocks.nonEmpty) {
        handleSendBlocks(blocks, Some(blockNotifyList))
      }
      checkSelfSynced(blocks.length)
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
  def startRelay(): Unit = {
    setPayloadHandler(handleRelayPayload)
    context become (handleReadWrite orElse handleRelayEvent orElse handleCommonEvents)
    setSyncOff()
  }

  def handleRelayEvent: Receive = {
    case BlockChainHandler.BlocksAdded(chainIndex) =>
      log.debug(s"all the blocks sent for $chainIndex are added")
    case HeaderChainHandler.HeadersAdded(chainIndex) =>
      log.debug(s"all the headers sent for $chainIndex are added")
    case BlockChainHandler.FetchSince(tips) =>
      sendPayload(GetBlocks(tips))
  }

  def handleRelayPayload(payload: Payload): Unit = payload match {
    case SendBlocks(blocks)     => handleSendBlocks(blocks, notifyListOpt = None)
    case GetBlocks(locators)    => handleGetBlocks(locators)
    case SendHeaders(headers)   => handleSendHeaders(headers)
    case GetHeaders(locators)   => handleGetHeaders(locators)
    case SendTxs(txs)           => handleSendTxs(txs)
    case Ping(nonce, timestamp) => handlePing(nonce, timestamp)
    case Pong(nonce)            => handlePong(nonce)
    case x                      =>
      // TODO: take care of misbehavior
      log.debug(s"Got unexpected payload type ${x.getClass.getSimpleName}")
  }
}

trait ConnectionUtil extends BaseActor {
  def connection: ActorRef

  def remote: InetSocketAddress

  def stop(): Unit = {
    log.debug(s"stopping connection with $remote")
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
