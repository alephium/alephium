package org.alephium.flow.network.bootstrap

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.flow.FlowMonitor
import org.alephium.flow.network.broker.BrokerConnectionHandler
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._
import org.alephium.util.{ActorRefT, BaseActor}

object BrokerConnector {
  def props(connection: ActorRefT[Tcp.Command], cliqueCoordinator: ActorRef)(
      implicit groupConfig: GroupConfig): Props =
    Props(new BrokerConnector(connection, cliqueCoordinator))

  sealed trait Command
  final case class Received(message: Message)             extends Command
  final case class Send(intraCliqueInfo: IntraCliqueInfo) extends Command

  sealed trait Event
  final case class Ack(id: Int) extends Event

  object Ack {
    implicit val serde: Serde[Ack] = Serde.forProduct1(new Ack(_), _.id)
  }

  def deserializeTry[T](input: ByteString)(
      implicit serde: Serde[T]): SerdeResult[Option[(T, ByteString)]] = {
    SerdeUtils.unwrap(serde._deserialize(input))
  }

  def envelop[T](input: T)(implicit serializer: Serializer[T]): Tcp.Write = {
    Tcp.Write(serializer.serialize(input))
  }

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

class BrokerConnector(connection: ActorRefT[Tcp.Command], cliqueCoordinator: ActorRef)(
    implicit val groupConfig: GroupConfig)
    extends BaseActor
    with SerdeUtils {
  import BrokerConnector._

  val connectionHandler: ActorRefT[BrokerConnectionHandler.Command] =
    context.actorOf(connectionProps(connection))

  override def receive: Receive = {
    case Received(peer: Message.Peer) =>
      cliqueCoordinator ! peer.info
      context become forwardCliqueInfo
  }

  def forwardCliqueInfo: Receive = {
    case Send(cliqueInfo) =>
      val data = Message.serialize(Message.Clique(cliqueInfo))
      connectionHandler ! BrokerConnectionHandler.Send(data)
      context become awaitAck
  }

  def awaitAck: Receive = {
    case Received(ack) =>
      cliqueCoordinator ! ack
      context become forwardReady
  }

  def forwardReady: Receive = {
    case CliqueCoordinator.Ready =>
      val data = Message.serialize(Message.Ready)
      connectionHandler ! BrokerConnectionHandler.Send(data)
    case Tcp.PeerClosed =>
      context stop self
  }

  override def unhandled(message: Any): Unit = {
    super.unhandled(message)
    context.system.eventStream.publish(FlowMonitor.Shutdown)
  }
}
