package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}

import org.alephium.flow.FlowMonitor
import org.alephium.util.BaseActor

object TcpServer {
  def props(port: Int): Props = Props(new TcpServer(port))

  sealed trait Command
  final case class Start(bootstrapper: ActorRef) extends Command
  final case class WorkFor(another: ActorRef)    extends Command

  sealed trait Event
  case object Bound extends Event
}

class TcpServer(port: Int) extends BaseActor {
  import context.system

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case TcpServer.Start(bootstrapper) =>
      IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress(port), pullMode = true)
      context.become(binding(bootstrapper))
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def binding(bootstrapper: ActorRef): Receive = {
    case Tcp.Bound(localAddress) =>
      log.debug(s"Server bound to $localAddress")
      sender() ! Tcp.ResumeAccepting(batchSize = 10)
      context.become(workFor(bootstrapper))
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      log.error(s"Binding failed")
      context.system.eventStream.publish(FlowMonitor.Shutdown)
    case TcpServer.WorkFor(another) =>
      context become binding(another)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def workFor(actor: ActorRef): Receive = {
    case c: Tcp.Connected =>
      actor.forward(c)
      sender() ! Tcp.ResumeAccepting(batchSize = 10)
    case TcpServer.WorkFor(another) =>
      context become workFor(another)
  }
}
