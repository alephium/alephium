package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.actor.{Cancellable, Terminated}
import akka.util.ByteString

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, BlockChainHandler, HeaderChainHandler, TxHandler}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model.{BrokerInfo, CliqueId}
import org.alephium.util._

object BrokerHandler {
  sealed trait Command
  case object HandShakeTimeout                                  extends Command
  final case class Send(data: ByteString)                       extends Command
  final case class Received(payload: Payload)                   extends Command
  case object SendPing                                          extends Command
  final case class SyncLocators(hashes: AVector[AVector[Hash]]) extends Command
  final case class DownloadHeaders(fromHashes: AVector[Hash])   extends Command
  final case class DownloadBlocks(hashes: AVector[Hash])        extends Command

  final case class ConnectionInfo(remoteAddress: InetSocketAddress, lcoalAddress: InetSocketAddress)
}

trait BrokerHandler extends BaseActor {
  import BrokerHandler._

  implicit def brokerConfig: BrokerConfig

  def remoteAddress: InetSocketAddress
  def brokerAlias: String = remoteAddress.toString

  var remoteCliqueId: CliqueId     = _
  var remoteBrokerInfo: BrokerInfo = _

  def handShakeDuration: Duration

  def blockflow: BlockFlow
  def allHandlers: AllHandlers

  def brokerConnectionHandler: ActorRefT[ConnectionHandler.Command]
  def blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command]

  override def receive: Receive = handShaking

  def handShakeMessage: Payload

  def handShaking: Receive = {
    send(handShakeMessage)
    val handshakeTimeoutTick = scheduleCancellableOnce(self, HandShakeTimeout, handShakeDuration)

    val receive: Receive = {
      case Received(hello: Hello) =>
        log.debug(s"Hello message received: $hello")
        handshakeTimeoutTick.cancel()
        handleHandshakeInfo(hello.cliqueId, hello.brokerInfo)

        pingPongTickOpt = Some(scheduleCancellable(self, SendPing, pingFrequency))
        context become (exchanging orElse pingPong)
      case HandShakeTimeout =>
        log.debug(s"HandShake timeout when connecting to $brokerAlias, closing the connection")
        publishEvent(BrokerManager.RequestTimeout(remoteAddress))
      case Received(_) =>
        publishEvent(BrokerManager.Spamming(remoteAddress))
    }
    receive
  }

  def handleHandshakeInfo(_remoteCliqueId: CliqueId, _remoteBrokerInfo: BrokerInfo): Unit = {
    remoteCliqueId   = _remoteCliqueId
    remoteBrokerInfo = _remoteBrokerInfo
  }

  @inline def escapeIOError[T](f: => IOResult[T], action: String)(g: T => Unit): Unit = f match {
    case Right(t) => g(t)
    case Left(error) =>
      log.error(s"IO error in $action: $error")
  }

  def exchanging: Receive

  def exchangingCommon: Receive = {
    case DownloadBlocks(hashes) =>
      send(GetBlocks(hashes))
    case Received(SendBlocks(blocks)) =>
      log.debug(s"Received blocks from ${remoteBrokerInfo.address}")
      blocks.foreach { block =>
        val message = BlockChainHandler.addOneBlock(block, dataOrigin)
        allHandlers.getBlockHandler(block.chainIndex) ! message
      }
      blockFlowSynchronizer ! BlockFlowSynchronizer.Downloaded(blocks.map(_.hash))
    case Received(GetBlocks(hashes)) =>
      escapeIOError(hashes.mapE(blockflow.getBlock), "load blocks") { blocks =>
        send(SendBlocks(blocks))
      }
    case Received(SendHeaders(headers)) =>
      log.debug(s"Received blocks from ${remoteBrokerInfo.address}")
      headers.foreach { header =>
        val message = HeaderChainHandler.addOneHeader(header, dataOrigin)
        allHandlers.getHeaderHandler(header.chainIndex) ! message
      }
    case Received(GetHeaders(hashes)) =>
      escapeIOError(hashes.mapE(blockflow.getBlockHeader), "load headers") { headers =>
        send(SendHeaders(headers))
      }
    case Send(data) =>
      brokerConnectionHandler ! ConnectionHandler.Send(data)
  }

  def flowEvents: Receive = {
    case BlockChainHandler.BlocksAdded(chainIndex) =>
      log.debug(s"All the blocks sent for $chainIndex are added")
    case HeaderChainHandler.HeadersAdded(chainIndex) =>
      log.debug(s"All the headers sent for $chainIndex are added")
    case TxHandler.AddSucceeded(hash) =>
      log.debug(s"Tx ${hash.shortHex} was added successfully")
    case TxHandler.AddFailed(hash) =>
      log.debug(s"Tx ${hash.shortHex} failed in adding")
  }

  def dataOrigin: DataOrigin

  def pingPong: Receive = {
    case SendPing             => sendPing()
    case Received(ping: Ping) => handlePing(ping.nonce, ping.timestamp)
    case Received(pong: Pong) => handlePong(pong.nonce)
  }

  final var pingPongTickOpt: Option[Cancellable] = None
  final var pingNonce: Int                       = 0

  def pingFrequency: Duration

  def sendPing(): Unit = {
    if (pingNonce != 0) {
      log.debug("No Pong message received in time")
      publishEvent(BrokerManager.RequestTimeout(remoteAddress))
      context stop self // stop it manually
    } else {
      pingNonce = Random.nextNonZeroInt()
      send(Ping(pingNonce, System.currentTimeMillis()))
    }
  }

  def handlePing(nonce: Int, timestamp: Long): Unit = {
    if (nonce == 0) {
      publishEvent(BrokerManager.InvalidPingPong(remoteAddress))
    } else {
      val delay = System.currentTimeMillis() - timestamp
      log.info(s"Ping received with ${delay}ms delay; Replying with Pong")
      send(Pong(nonce))
    }
  }

  def handlePong(nonce: Int): Unit = {
    if (nonce == pingNonce) {
      log.debug(s"Pong received from broker $brokerAlias")
      pingNonce = 0
    } else {
      log.debug(
        s"Pong received from broker $brokerAlias wrong nonce: expect $pingNonce, got $nonce")
      publishEvent(BrokerManager.InvalidPingPong(remoteAddress))
    }
  }

  def send(payload: Payload): Unit = {
    brokerConnectionHandler ! ConnectionHandler.Send(Message.serialize(payload))
  }

  def stop(): Unit = {
    pingPongTickOpt.foreach(_.cancel())
    brokerConnectionHandler ! ConnectionHandler.CloseConnection
  }

  override def unhandled(message: Any): Unit = message match {
    case Terminated(_) =>
      context stop self
    case _ => super.unhandled(message)
  }
}
