package org.alephium.flow.network.bootstrap

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo, UnsafeModel}
import org.alephium.serde._
import org.alephium.util.BaseActor

object BrokerConnector {
  def props(connection: ActorRef, cliqueCoordinator: ActorRef)(
      implicit config: GroupConfig): Props =
    Props(new BrokerConnector(connection, cliqueCoordinator))

  sealed trait Event
  final case class Ack(id: Int) extends Event

  object Ack {
    implicit val serde: Serde[Ack] = Serde.forProduct1(new Ack(_), _.id)
  }

  def deserializeTry[T](input: ByteString)(
      implicit serde: Serde[T]): SerdeResult[Option[(T, ByteString)]] = {
    serde._deserialize(input) match {
      case Right((t, rest))                   => Right(Some((t, rest)))
      case Left(_: SerdeError.NotEnoughBytes) => Right(None)
      case Left(e)                            => Left(e)
    }
  }

  def deserializeTryWithValidation[T, U <: UnsafeModel[T]](input: ByteString)(
      implicit serde: Serde[U],
      config: GroupConfig): SerdeResult[Option[(T, ByteString)]] = {
    serde._deserialize(input) match {
      case Right((t, rest)) =>
        t.validate.fold(e => Left(SerdeError.validation(e)), i => Right(Some((i, rest))))
      case Left(_: SerdeError.NotEnoughBytes) => Right(None)
      case Left(e)                            => Left(e)
    }
  }

  def envelop[T](input: T)(implicit serializer: Serializer[T]): Tcp.Write = {
    Tcp.Write(serializer.serialize(input))
  }
}

class BrokerConnector(connection: ActorRef, cliqueCoordinator: ActorRef)(
    implicit config: GroupConfig)
    extends BaseActor {
  import BrokerConnector._

  override def preStart(): Unit = connection ! Tcp.Register(self)

  override def receive: Receive =
    await[BrokerInfo](ByteString.empty,
                      context become forwardCliqueInfo,
                      deserializeTryWithValidation[BrokerInfo, BrokerInfo.Unsafe](_))

  def forwardCliqueInfo: Receive = {
    case cliqueInfo: CliqueInfo =>
      connection ! envelop(cliqueInfo)
      context become await[Ack](ByteString.empty, context become forwardReady, deserializeTry(_))
  }

  def forwardReady: Receive = {
    case ready: CliqueCoordinator.Ready.type =>
      connection ! envelop(ready)
    case Tcp.PeerClosed =>
      context stop self
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def await[E](unaligned: ByteString,
               next: => Unit,
               deserialize: ByteString => SerdeResult[Option[(E, ByteString)]]): Receive = {
    case Tcp.Received(data) =>
      deserialize(data) match {
        case Right(Some((event, _))) =>
          cliqueCoordinator ! event
          next
        case Right(None) =>
          context become await[E](unaligned ++ data, next, deserialize)
        case Left(e) =>
          log.info(s"Received corrupted data; error: ${e.toString}; Closing connection")
          context stop self
      }
  }
}
