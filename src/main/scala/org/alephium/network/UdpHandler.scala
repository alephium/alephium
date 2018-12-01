package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Udp}
import org.alephium.primitive.BlockHeader
import org.alephium.util.BaseActor
import org.alephium.serde._

object UdpHandler {
  def props(port: Int): Props = Props(new UdpHandler(port))

  sealed trait Event
  case class Ready(port: Int, listener: ActorRef) extends Event

  sealed trait Command
  case class Send(blockHeader: BlockHeader, remote: InetSocketAddress) extends Command
}

class UdpHandler(port: Int) extends BaseActor {
  import context.system

  val address = new InetSocketAddress(port)
  IO(Udp) ! Udp.Bind(self, address)

  override def receive: Receive = {
    case Udp.Bound(localAddress) =>
      context.become(ready(sender()))
      logger.debug(s"Listen at ${localAddress.toString}, expect: $port")
  }

  def ready(socket: ActorRef): Receive = {
    case Udp.Received(data, remote) =>
      logger.debug(s"Received `${deserialize[BlockHeader](data)}` from " + remote.toString)
    case UdpHandler.Send(blockHeader: BlockHeader, remote: InetSocketAddress) =>
      socket ! Udp.Send(serialize(blockHeader), remote)
      logger.debug(s"Send message `$blockHeader` to " + remote.toString)
    case Udp.Unbind =>
      socket ! Udp.Unbind
      logger.debug(s"Unbind $port")
    case Udp.Unbound => context.stop(self)
  }
}
