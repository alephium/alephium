package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.actor.{Cancellable, Props}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, BlockChainHandler}
import org.alephium.flow.model.DataOrigin
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.message._
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}
import org.alephium.util._

object BrokerHandler {
  def props(cliqueInfo: CliqueInfo, connection: ActorRefT[Tcp.Command])(
      implicit brokerConfig: BrokerConfig): Props =
    Props(new Impl(cliqueInfo, connection))

  sealed trait Command
  case object HandShakeTimeout                                extends Command
  final case class Send(data: ByteString)                     extends Command
  final case class Received(payload: Payload)                 extends Command
  case object SendPing                                        extends Command
  final case class Sync(locators: AVector[AVector[Hash]])     extends Command
  final case class DownloadHeaders(fromHashes: AVector[Hash]) extends Command
  case object HeaderDownloadDone                              extends Command
  final case class DownloadBlocks(hashes: AVector[Hash])      extends Command
  case object BlockDownloadDone                               extends Command

  final case class ConnectionInfo(remoteAddress: InetSocketAddress, lcoalAddress: InetSocketAddress)

  class Impl(cliqueInfo: CliqueInfo, connection: ActorRefT[Tcp.Command])(
      implicit brokerConfig: BrokerConfig)
      extends BrokerHandler {
    override def brokerAlias: String = "brokerAlias"

    override def handShakeDuration: Duration = Duration.ofMinutesUnsafe(1)

    override val brokerConnectionHandler: ActorRefT[BrokerConnectionHandler.Command] = {
      val actor = context.actorOf(BrokerConnectionHandler.props(connection))
      ActorRefT(actor)
    }

    override def handShakeMessage: Payload = Hello.unsafe(cliqueInfo.id, cliqueInfo.selfBrokerInfo)

    override def pingFrequency: Duration = ???

    override def connectionInfo: ConnectionInfo = ???

    override def blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command] = ???

    override def misBehavingHandler: ActorRefT[MisBehavingHandler.Command] = ???

    override def blockflow: BlockFlow = ???

    override implicit def groupConfig: GroupConfig = ???

    override def allHandlers: AllHandlers = ???
  }
}

trait BrokerHandler extends BaseActor {
  import BrokerHandler._

  implicit def groupConfig: GroupConfig

  def connectionInfo: ConnectionInfo

  var remoteBrokerInfo: BrokerInfo = _
  def brokerAlias: String

  def handShakeDuration: Duration

  override def preStart(): Unit = {
    super.preStart()
  }

  def blockflow: BlockFlow
  def allHandlers: AllHandlers

  def brokerConnectionHandler: ActorRefT[BrokerConnectionHandler.Command]
  def blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command]
  def misBehavingHandler: ActorRefT[MisBehavingHandler.Command]

  override def receive: Receive = handShaking

  def handShakeMessage: Payload

  def handShaking: Receive = {
    send(handShakeMessage)
    val handshakeTimeoutTick = scheduleCancellableOnce(self, HandShakeTimeout, handShakeDuration)

    val receive: Receive = {
      case Received(hello: Hello) =>
        log.debug(s"Hello message received: $hello")
        handshakeTimeoutTick.cancel()
        remoteBrokerInfo = hello.brokerInfo

        pingPongTickOpt = Some(scheduleCancellable(self, SendPing, pingFrequency))
        blockFlowSynchronizer ! BlockFlowSynchronizer.HandShaked(hello.brokerInfo)
        context become (exchanging orElse pingPong)
      case HandShakeTimeout =>
        log.debug(s"HandShake timeout when connecting to $brokerAlias, closing the connection")
        misBehavingHandler ! MisBehavingHandler.RequestTimeout(connectionInfo)
      case Received(_) =>
        misBehavingHandler ! MisBehavingHandler.Spamming(connectionInfo)
    }
    receive
  }

  @inline def escapeIOError[T](f: => IOResult[T], action: String)(g: T => Unit): Unit = f match {
    case Right(t) => g(t)
    case Left(error) =>
      log.error(s"IO error in $action: $error")
  }

  def exchanging: Receive = {
    case Sync(locators) =>
      send(SyncRequest0(locators))
    case Received(SyncRequest0(locators)) =>
      val inventories = blockflow.getSyncDataUnsafe(locators)
      send(SyncResponse0(inventories))
    case Received(SyncResponse0(hashes)) =>
      blockFlowSynchronizer ! BlockFlowSynchronizer.SyncData(hashes)
    case DownloadBlocks(hashes) =>
      send(GetBlocks(hashes))
    case Received(SendBlocks(blocks)) =>
      log.debug(s"Received blocks from ${remoteBrokerInfo.address}")
      blocks.foreach { block =>
        val message = BlockChainHandler.addOneBlock(
          block,
          DataOrigin.InterClique(CliqueId.generate, remoteBrokerInfo, false))
        allHandlers.getBlockHandler(block.chainIndex) ! message
      }
    case Received(GetBlocks(hashes)) =>
      val blocks = hashes.map(hash => Utils.unsafe(blockflow.getBlock(hash)))
      send(SendBlocks(blocks))
    case Send(data) =>
      brokerConnectionHandler ! BrokerConnectionHandler.Send(data)
  }

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
      misBehavingHandler ! MisBehavingHandler.RequestTimeout(connectionInfo)
      context stop self // stop it manually
    } else {
      pingNonce = Random.nextNonZeroInt()
      send(Ping(pingNonce, System.currentTimeMillis()))
    }
  }

  def handlePing(nonce: Int, timestamp: Long): Unit = {
    if (nonce == 0) {
      misBehavingHandler ! MisBehavingHandler.InvalidPingPong(connectionInfo)
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
      misBehavingHandler ! MisBehavingHandler.InvalidPingPong(connectionInfo)
    }
  }

  def send(payload: Payload): Unit = {
    brokerConnectionHandler ! BrokerConnectionHandler.Send(Message.serialize(payload))
  }

  def handleMisbehavior(behavior: MisBehavingHandler.MisBehavior): Unit = {
    misBehavingHandler ! behavior
  }

  def stopPeer(): Unit = {
    pingPongTickOpt.foreach(_.cancel())
    brokerConnectionHandler ! BrokerConnectionHandler.CloseConnection
  }
}
