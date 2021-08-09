// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.actor.{Cancellable, Terminated}
import akka.util.ByteString

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler._
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.flow.validation.Validation
import org.alephium.io.IOResult
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model.{BrokerInfo, FlowData}
import org.alephium.util._

object BrokerHandler {
  sealed trait Command
  case object HandShakeTimeout                                       extends Command
  final case class Send(data: ByteString)                            extends Command
  final case class Received(payload: Payload)                        extends Command
  case object SendPing                                               extends Command
  final case class SyncLocators(hashes: AVector[AVector[BlockHash]]) extends Command
  final case class DownloadHeaders(fromHashes: AVector[BlockHash])   extends Command
  final case class DownloadBlocks(hashes: AVector[BlockHash])        extends Command

  final case class ConnectionInfo(remoteAddress: InetSocketAddress, lcoalAddress: InetSocketAddress)
}

trait BrokerHandler extends FlowDataHandler {
  import BrokerHandler._

  def connectionType: ConnectionType

  implicit def brokerConfig: BrokerConfig
  implicit def networkSetting: NetworkSetting

  def remoteAddress: InetSocketAddress
  def brokerAlias: String = remoteAddress.toString

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

    def stop(misbehavior: MisbehaviorManager.Misbehavior): Unit = {
      handshakeTimeoutTick.cancel()
      publishEvent(misbehavior)
      context.stop(self)
    }

    val receive: Receive = {
      case Received(hello: Hello) =>
        log.debug(s"Hello message received: $hello")
        handshakeTimeoutTick.cancel()
        handleHandshakeInfo(BrokerInfo.from(remoteAddress, hello.brokerInfo))

        pingPongTickOpt = Some(scheduleCancellable(self, SendPing, pingFrequency))
        context become (exchanging orElse pingPong)
      case HandShakeTimeout =>
        log.warning(s"HandShake timeout when connecting to $brokerAlias, closing the connection")
        stop(MisbehaviorManager.RequestTimeout(remoteAddress))
      case Received(message) =>
        log.warning(s"Unexpected message from $brokerAlias, $message")
        stop(MisbehaviorManager.Spamming(remoteAddress))
    }
    receive
  }

  def handleHandshakeInfo(_remoteBrokerInfo: BrokerInfo): Unit = {
    remoteBrokerInfo = _remoteBrokerInfo
  }

  @inline def escapeIOError[T](f: => IOResult[T], action: String)(g: T => Unit): Unit =
    f match {
      case Right(t) => g(t)
      case Left(error) =>
        log.error(s"IO error in $action: $error")
    }

  def exchanging: Receive

  def exchangingCommon: Receive = {
    case DownloadBlocks(hashes) =>
      log.debug(
        s"Download #${hashes.length} blocks ${Utils.showDigest(hashes)} from $remoteAddress"
      )
      send(BlocksRequest(hashes))
    case Received(NewBlocks(blocks)) =>
      log.debug(
        s"Received #${blocks.length} blocks ${Utils.showDataDigest(blocks)} from $remoteAddress"
      )
      handleFlowData(blocks, dataOrigin, isBlock = true)
    case Received(BlocksResponse(requestId, blocks)) =>
      log.debug(
        s"Received #${blocks.length} blocks ${Utils.showDataDigest(blocks)} from $remoteAddress with $requestId"
      )
      handleFlowData(blocks, dataOrigin, isBlock = true)
    case Received(BlocksRequest(requestId, hashes)) =>
      escapeIOError(hashes.mapE(blockflow.getBlock), "load blocks") { blocks =>
        send(BlocksResponse(requestId, blocks))
      }
    case Received(NewHeaders(headers)) =>
      log.debug(
        s"Received #${headers.length} headers ${Utils.showDataDigest(headers)} from $remoteAddress"
      )
      handleFlowData(headers, dataOrigin, isBlock = false)
    case Received(HeadersResponse(requestId, headers)) =>
      log.debug(
        s"Received #${headers.length} headers ${Utils.showDataDigest(headers)} from $remoteAddress with $requestId"
      )
      handleFlowData(headers, dataOrigin, isBlock = false)
    case Received(HeadersRequest(requestId, hashes)) =>
      escapeIOError(hashes.mapE(blockflow.getBlockHeader), "load headers") { headers =>
        send(HeadersResponse(requestId, headers))
      }
    case Received(NewTxs(txs)) =>
      log.debug(s"SendTxs received: ${Utils.showDigest(txs.map(_.id))}")
      allHandlers.txHandler ! TxHandler.AddToSharedPool(txs, dataOrigin)
    case Send(data) =>
      brokerConnectionHandler ! ConnectionHandler.Send(data)
  }

  def flowEvents: Receive = {
    case BlockChainHandler.BlockAdded(hash) =>
      blockFlowSynchronizer ! BlockFlowSynchronizer.BlockFinalized(hash)
    case BlockChainHandler.BlockAddingFailed =>
      log.debug(s"Failed in adding new block")
    case BlockChainHandler.InvalidBlock(hash) =>
      blockFlowSynchronizer ! BlockFlowSynchronizer.BlockFinalized(hash)
      handleMisbehavior(MisbehaviorManager.InvalidMessage(remoteAddress))
    case HeaderChainHandler.HeaderAdded(_) =>
      ()
    case HeaderChainHandler.HeaderAddingFailed =>
      log.debug(s"Failed in adding new header")
    case HeaderChainHandler.InvalidHeader(hash) =>
      log.debug(s"Invalid header received ${hash.shortHex}")
      handleMisbehavior(MisbehaviorManager.InvalidMessage(remoteAddress))
    case TxHandler.AddSucceeded(hash) =>
      log.debug(s"Tx ${hash.shortHex} was added successfully")
    case TxHandler.AddFailed(hash) =>
      log.debug(s"Tx ${hash.shortHex} failed in adding")
  }

  def dataOrigin: DataOrigin

  def pingPong: Receive = {
    case SendPing             => sendPing()
    case Received(ping: Ping) => handlePing(ping.id, ping.timestamp)
    case Received(pong: Pong) => handlePong(pong.id)
  }

  final var pingPongTickOpt: Option[Cancellable] = None
  final var pingRequestId: RequestId             = RequestId.unsafe(0)

  def pingFrequency: Duration

  def sendPing(): Unit = {
    if (pingRequestId.value != U32.Zero) {
      log.info(s"No Pong message received in time from $remoteAddress")
      handleMisbehavior(MisbehaviorManager.RequestTimeout(remoteAddress))
    }

    pingRequestId = RequestId.random()
    send(Ping(pingRequestId, TimeStamp.now()))
  }

  def handlePing(requestId: RequestId, timestamp: TimeStamp): Unit = {
    if (requestId.value == U32.Zero) {
      handleMisbehavior(MisbehaviorManager.InvalidPingPongCritical(remoteAddress))
    } else {
      val delay = System.currentTimeMillis() - timestamp.millis
      log.debug(s"Ping received with ${delay}ms delay; Replying with Pong")
      send(Pong(requestId))
    }
  }

  def handlePong(requestId: RequestId): Unit = {
    if (requestId == pingRequestId) {
      log.debug(s"Pong received from broker $brokerAlias")
      pingRequestId = RequestId(U32.Zero)
    } else {
      log.debug(
        s"Pong received from broker $brokerAlias wrong requestId: expect $pingRequestId, got $requestId"
      )
      handleMisbehavior(MisbehaviorManager.InvalidPingPong(remoteAddress))
    }
  }

  def send(payload: Payload): Unit = {
    brokerConnectionHandler !
      ConnectionHandler.Send(Message.serialize(payload))
  }

  override def unhandled(message: Any): Unit =
    message match {
      case Terminated(_) =>
        log.info(
          s"Connection handler for $remoteAddress is terminated. Stopping the broker handler."
        )
        context stop self
      case _ => super.unhandled(message)
    }

  override def postStop(): Unit = {
    super.postStop()
    pingPongTickOpt.foreach(_.cancel())
  }
}

trait FlowDataHandler extends BaseHandler {
  implicit def brokerConfig: BrokerConfig
  def allHandlers: AllHandlers
  def remoteAddress: InetSocketAddress
  def blockflow: BlockFlow

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def handleFlowData[T <: FlowData](
      datas: AVector[T],
      dataOrigin: DataOrigin,
      isBlock: Boolean
  ): Unit = {
    if (!Validation.preValidate(datas)(blockflow.consensusConfig)) {
      log.warning(s"The data received does not contain minimal work")
      handleMisbehavior(MisbehaviorManager.InvalidPoW(remoteAddress))
    } else {
      val ok = datas.forall { data => data.chainIndex.relateTo(brokerConfig) == isBlock }
      if (ok) {
        val message = DependencyHandler.AddFlowData(datas, dataOrigin)
        allHandlers.dependencyHandler ! message
      } else {
        handleMisbehavior(MisbehaviorManager.InvalidFlowChainIndex(remoteAddress))
      }
    }
  }
}
