package org.alephium.flow.network.bootstrap

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo}
import org.alephium.serde.Serde
import org.alephium.util.{BaseActor, Duration, TimeStamp}

object Broker {
  def props(masterAddress: InetSocketAddress,
            brokerInfo: BrokerInfo,
            retryTimeout: Duration,
            bootstrapper: ActorRef)(
      implicit config: PlatformConfig
  ): Props =
    Props(new Broker(masterAddress, brokerInfo, retryTimeout, bootstrapper))

  sealed trait Command
  case object Retry extends Command
}

class Broker(masterAddress: InetSocketAddress,
             brokerInfo: BrokerInfo,
             retryTimeout: Duration,
             bootstrapper: ActorRef)(implicit config: PlatformConfig)
    extends BaseActor {
  implicit val peerInfoSerde: Serde[PeerInfo] = PeerInfo.serde

  def until: TimeStamp = TimeStamp.now() + retryTimeout

  override def preStart(): Unit =
    IO(Tcp)(context.system) ! Tcp.Connect(masterAddress)

  override def receive: Receive = awaitMaster(until)

  def awaitMaster(until: TimeStamp): Receive = {
    case Broker.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(masterAddress)

    case _: Tcp.Connected =>
      log.debug(s"Connected to master: $masterAddress")
      val connection = sender()
      connection ! Tcp.Register(self)

      connection ! BrokerConnector.envelop(PeerInfo.self)
      context become awaitCliqueInfo(connection, ByteString.empty)

    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val current = TimeStamp.now()
      if (current isBefore until) {
        scheduleOnce(self, Broker.Retry, Duration.ofSecondsUnsafe(1))
      } else {
        log.info(s"Cannot connect to ${c.remoteAddress}")
        context stop self
      }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def awaitCliqueInfo(connection: ActorRef, unaligned: ByteString): Receive = {
    case Tcp.Received(data) =>
      BrokerConnector.deserializeTryWithValidation[CliqueInfo, CliqueInfo.Unsafe](unaligned ++ data) match {
        case Right(Some((cliqueInfo, _))) =>
          val ack = BrokerConnector.Ack(brokerInfo.id)
          connection ! BrokerConnector.envelop(ack)
          context become awaitReady(connection, cliqueInfo, ByteString.empty)
        case Right(None) =>
          context become awaitCliqueInfo(connection, unaligned ++ data)
        case Left(e) =>
          log.info(s"Received corrupted data; error: ${e.toString}; Closing connection")
          context stop self
      }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def awaitReady(connection: ActorRef, cliqueInfo: CliqueInfo, unaligned: ByteString): Receive = {
    case Tcp.Received(data) =>
      BrokerConnector.deserializeTry[CliqueCoordinator.Ready.type](unaligned ++ data) match {
        case Right(Some((_, _))) =>
          log.debug("Received ready message from master")
          connection ! Tcp.ConfirmedClose
          context become awaitClose(cliqueInfo)
        case Right(None) =>
          context become awaitReady(connection, cliqueInfo, unaligned ++ data)
        case Left(e) =>
          log.info(s"Received corrupted data; error: ${e.toString}; Closing connection")
          context stop self
      }
  }

  def awaitClose(cliqueInfo: CliqueInfo): Receive = {
    case Tcp.ConfirmedClosed =>
      log.debug("Close connection to master")
      bootstrapper ! cliqueInfo
      context.stop(self)
  }
}
