package org.alephium.flow.network.bootstrap

import akka.actor.{Props, Terminated}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import org.alephium.flow.FlowMonitor
import org.alephium.flow.network.Bootstrapper
import org.alephium.flow.network.broker.BrokerConnectionHandler
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

  def connectionProps(connection: ActorRefT[Tcp.Command])(
      implicit groupConfig: GroupConfig): Props =
    Props(new ConnectionHandler(connection))

  class ConnectionHandler(val connection: ActorRefT[Tcp.Command])(implicit groupConfig: GroupConfig)
      extends BrokerConnectionHandler[Message] {
    override def tryDeserialize(data: ByteString): SerdeResult[Option[(Message, ByteString)]] = {
      Message.tryDeserialize(data)
    }

    override def handleNewMessage(message: Message): Unit = {
      context.parent ! Received(message)
    }

    override def stopMaliciousPeer(): Unit = {
      log.debug("Malicious behavior detected in bootstrap, shutdown the system")
      context.system.eventStream.publish(FlowMonitor.Shutdown)
    }
  }
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
      val connection        = sender()
      val connectionHandler = context.actorOf(Broker.connectionProps(connection))
      val message           = Message.serialize(Message.Peer(PeerInfo.self))
      connectionHandler ! BrokerConnectionHandler.Send(message)
      context become awaitCliqueInfo(connectionHandler)

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
  def awaitCliqueInfo(connectionHandler: ActorRefT[BrokerConnectionHandler.Command]): Receive = {
    case Broker.Received(clique: Message.Clique) =>
      val message = Message.serialize(Message.Ack(brokerConfig.brokerId))
      connectionHandler ! BrokerConnectionHandler.Send(message)
      context become awaitReady(connectionHandler, clique.info)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def awaitReady(connection: ActorRefT[BrokerConnectionHandler.Command],
                 cliqueInfo: IntraCliqueInfo): Receive = {
    case Broker.Received(Message.Ready) =>
      connection ! BrokerConnectionHandler.CloseConnection
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
    context.system.eventStream.publish(FlowMonitor.Shutdown)
  }
}
