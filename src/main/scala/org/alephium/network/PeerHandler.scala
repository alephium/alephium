package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Udp}
import akka.util.ByteString
import org.alephium.util.BaseActor

object PeerHandler {
  def props(port: Int): Props = Props(new PeerHandler(port))

  sealed trait Event
  case class Ready(port: Int, listener: ActorRef)

  sealed trait Command
  case class Send(message: String, remote: InetSocketAddress) extends Command
}

class PeerHandler(port: Int) extends BaseActor {
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
      logger.debug(s"Received `${data.decodeString("UTF-8")}` from " + remote.toString)
    case PeerHandler.Send(message: String, remote: InetSocketAddress) =>
      socket ! Udp.Send(ByteString(message), remote)
      logger.debug(s"Send message `$message` to " + remote.toString)
    case Udp.Unbind =>
      socket ! Udp.Unbind
      logger.debug(s"Unbind $port")
    case Udp.Unbound => context.stop(self)
  }
}
