package org.alephium.flow.network.bootstrap

import java.net.InetSocketAddress

import akka.actor.{Props, Terminated}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import org.alephium.flow.FlowMonitor
import org.alephium.flow.network.Bootstrapper
import org.alephium.flow.network.broker.{BrokerManager, ConnectionHandler}
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.serde.SerdeResult
import org.alephium.util.{ActorRefT, BaseActor, Duration, TimeStamp}

object Broker {
  def props(bootstrapper: ActorRefT[Bootstrapper.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting
  ): Props = Props(new Broker(bootstrapper))

  sealed trait Command
  case object Retry                           extends Command
  final case class Received(message: Message) extends Command

  def connectionProps(remoteAddress: InetSocketAddress, connection: ActorRefT[Tcp.Command])(
      implicit groupConfig: GroupConfig): Props =
    Props(new MyConnectionHandler(remoteAddress, connection))

  class MyConnectionHandler(
      val remoteAddress: InetSocketAddress,
      val connection: ActorRefT[Tcp.Command])(implicit groupConfig: GroupConfig)
      extends ConnectionHandler[Message] {
    override def tryDeserialize(data: ByteString): SerdeResult[Option[(Message, ByteString)]] = {
      Message.tryDeserialize(data)
    }

    override def handleNewMessage(message: Message): Unit = {
      context.parent ! Received(message)
    }

    override def handleInvalidMessage(message: BrokerManager.InvalidMessage): Unit = {
      log.debug("Malicious behavior detected in bootstrap, shutdown the system")
      publishEvent(FlowMonitor.Shutdown)
    }
  }
}

class Broker(bootstrapper: ActorRefT[Bootstrapper.Command])(implicit brokerConfig: BrokerConfig,
                                                            networkSetting: NetworkSetting)
    extends BaseActor
    with SerdeUtils {
  val until: TimeStamp = TimeStamp.now() + networkSetting.retryTimeout

  def remoteAddress: InetSocketAddress = networkSetting.masterAddress

  IO(Tcp)(context.system) ! Tcp.Connect(remoteAddress, pullMode = true)

  override def receive: Receive = awaitMaster(until)

  def awaitMaster(until: TimeStamp): Receive = {
    case Broker.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(remoteAddress, pullMode = true)

    case _: Tcp.Connected =>
      log.debug(s"Connected to master: $remoteAddress")
      val connection        = sender()
      val connectionHandler = context.actorOf(Broker.connectionProps(remoteAddress, connection))
      context watch connectionHandler.ref

      val message = Message.serialize(Message.Peer(PeerInfo.self))
      connectionHandler ! ConnectionHandler.Send(message)
      context become awaitCliqueInfo(connectionHandler)

    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val current = TimeStamp.now()
      if (current isBefore until) {
        scheduleOnce(self, Broker.Retry, Duration.ofSecondsUnsafe(1))
        ()
      } else {
        log.info(s"Cannot connect to ${c.remoteAddress}, shutdown the system")
        publishEvent(FlowMonitor.Shutdown)
      }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def awaitCliqueInfo(connectionHandler: ActorRefT[ConnectionHandler.Command]): Receive = {
    case Broker.Received(clique: Message.Clique) =>
      log.debug(s"Received clique info from master")
      val message = Message.serialize(Message.Ack(brokerConfig.brokerId))
      connectionHandler ! ConnectionHandler.Send(message)
      context become awaitReady(connectionHandler, clique.info)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def awaitReady(connection: ActorRefT[ConnectionHandler.Command],
                 cliqueInfo: IntraCliqueInfo): Receive = {
    case Broker.Received(Message.Ready) =>
      log.debug(s"Clique is ready")
      connection ! ConnectionHandler.CloseConnection
      context become awaitClose(cliqueInfo)
  }

  def awaitClose(cliqueInfo: IntraCliqueInfo): Receive = {
    case Terminated(_) =>
      log.debug(s"Connection to master ${networkSetting.masterAddress} is closed")
      bootstrapper ! Bootstrapper.SendIntraCliqueInfo(cliqueInfo)
      context.stop(self)
  }

  override def unhandled(message: Any): Unit = {
    super.unhandled(message)
    log.debug(s"Unexpected message, shutdown the system")
    publishEvent(FlowMonitor.Shutdown)
  }
}
