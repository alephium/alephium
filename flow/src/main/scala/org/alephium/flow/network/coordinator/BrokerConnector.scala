package org.alephium.flow.network.coordinator

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.util.ByteString
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BrokerId, CliqueInfo}
import org.alephium.serde._
import org.alephium.util.BaseActor

object BrokerConnector {
  def props(connection: ActorRef): Props = Props(new BrokerConnector(connection))

  sealed trait Command
  //TODO: move this to Coordinator
  case object Ready extends Command {
    implicit val serde: Serde[Ready.type] = intSerde.xfmap[Ready.type](
      raw => if (raw == 0) Right(Ready) else Left(SerdeError.wrongFormat(s"Expecting 0 got $raw")),
      _   => 0)
  }

  sealed trait Event
  case class BrokerInfo(id: BrokerId, address: InetSocketAddress) extends Event // TODO: verify id
  case class Ack(id: Int)                                         extends Event

  object BrokerInfo {
    implicit val serde: Serde[BrokerInfo] = Serde
      .tuple2[Int, InetSocketAddress]
      .xmap(
        { case (id, address) => BrokerInfo(BrokerId.unsafe(id), address) },
        info => (info.id.value, info.address)
      )
  }

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

  def deserializeCliqueInfo(input: ByteString)(
      implicit config: GroupConfig): SerdeResult[Option[(CliqueInfo, ByteString)]] = {
    CliqueInfo.Unsafe.serde._deserialize(input) match {
      case Right((t, rest)) =>
        t.validate.fold(e => Left(SerdeError.validation(e)), i => Right(Some((i, rest))))
      case Left(_: SerdeError.NotEnoughBytes) => Right(None)
      case Left(e)                            => Left(e)
    }
  }

  def envolop[T](input: T)(implicit serializer: Serializer[T]): Tcp.Write = {
    Tcp.Write(serializer.serialize(input))
  }
}

class BrokerConnector(connection: ActorRef) extends BaseActor {
  import BrokerConnector._

  connection ! Tcp.Register(self)

  override def receive: Receive =
    await[BrokerInfo](ByteString.empty, context become forwardCliqueInfo)

  def forwardCliqueInfo: Receive = {
    case cliqueInfo: CliqueInfo =>
      connection ! envolop(cliqueInfo)
      context become await[Ack](ByteString.empty, context become forwardReady)
  }

  def forwardReady: Receive = {
    case ready: Ready.type =>
      connection ! envolop(ready)
    case Tcp.PeerClosed =>
      context stop self
  }

  def await[E: Serde](unaligned: ByteString, next: => Unit): Receive = {
    case Tcp.Received(data) =>
      deserializeTry[E](unaligned ++ data) match {
        case Right(Some((event, _))) =>
          context.parent ! event
          next
        case Right(None) =>
          context become await[E](unaligned ++ data, next)
        case Left(e) =>
          log.info(s"Received corrupted data; error: ${e.toString}; Closing connection")
          context stop self
      }
  }
}
