package org.alephium.flow.network.bootstrap

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import org.alephium.flow.network.Bootstrapper
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.util.{ActorRefT, BaseActor, Duration, TimeStamp}

object Broker {
  def props(bootstrapper: ActorRefT[Bootstrapper.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting
  ): Props = Props(new Broker(bootstrapper))

  sealed trait Command
  case object Retry extends Command
}

class Broker(bootstrapper: ActorRefT[Bootstrapper.Command])(implicit brokerConfig: BrokerConfig,
                                                            networkSetting: NetworkSetting)
    extends BaseActor
    with SerdeUtils {
  def until: TimeStamp = TimeStamp.now() + networkSetting.retryTimeout

  IO(Tcp)(context.system) ! Tcp.Connect(networkSetting.masterAddress)

  override def receive: Receive = awaitMaster(until)

  def awaitMaster(until: TimeStamp): Receive = {
    case Broker.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(networkSetting.masterAddress)

    case _: Tcp.Connected =>
      log.debug(s"Connected to master: ${networkSetting.masterAddress}")
      val connection = sender()
      connection ! Tcp.Register(self)
      connection ! BrokerConnector.envelop(PeerInfo.self)
      context become awaitCliqueInfo(connection, ByteString.empty)

    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val current = TimeStamp.now()
      if (current isBefore until) {
        scheduleOnce(self, Broker.Retry, Duration.ofSecondsUnsafe(1))
        ()
      } else {
        log.info(s"Cannot connect to ${c.remoteAddress}")
        context stop self
      }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def awaitCliqueInfo(connection: ActorRef, unaligned: ByteString): Receive = {
    case Tcp.Received(data) =>
      BrokerConnector.unwrap(IntraCliqueInfo._deserialize(unaligned ++ data)) match {
        case Right(Some((cliqueInfo, _))) =>
          val ack = BrokerConnector.Ack(brokerConfig.brokerId)
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
  def awaitReady(connection: ActorRef,
                 cliqueInfo: IntraCliqueInfo,
                 unaligned: ByteString): Receive = {
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

  def awaitClose(cliqueInfo: IntraCliqueInfo): Receive = {
    case Tcp.ConfirmedClosed =>
      log.debug("Close connection to master")
      bootstrapper ! Bootstrapper.SendIntraCliqueInfo(cliqueInfo)
      context.stop(self)
  }
}
