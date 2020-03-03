package org.alephium.flow.network.bootstrap

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.{BaseActor, Duration, TimeStamp}

object Broker {
  def props()(implicit config: PlatformProfile): Props = Props(new Broker())

  sealed trait Command
  case object Retry extends Command
}

class Broker()(implicit config: PlatformProfile) extends BaseActor {
  IO(Tcp)(context.system) ! Tcp.Connect(config.masterAddress)

  def until: TimeStamp = TimeStamp.now() + config.retryTimeout

  override def receive: Receive = awaitMaster(until)

  def awaitMaster(until: TimeStamp): Receive = {
    case Broker.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(config.masterAddress)

    case _: Tcp.Connected =>
      log.debug(s"Connected to master: ${config.masterAddress}")
      val connection = sender()
      connection ! Tcp.Register(self)
      connection ! BrokerConnector.envelop(config.brokerInfo)
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
          log.debug("Received clique info from master")
          val ack = BrokerConnector.Ack(config.brokerInfo.id)
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
      context.parent ! cliqueInfo
      context.stop(self)
  }
}
