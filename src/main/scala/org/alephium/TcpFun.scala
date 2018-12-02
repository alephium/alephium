package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import org.alephium.constant.Network
import org.alephium.network.{TcpClient, TcpServer}

object TcpFun extends App {
  val system = ActorSystem("TcpFun")

  val server  = system.actorOf(TcpServer.props(Network.port), "server")
  val client1 = system.actorOf(TcpClient.props(new InetSocketAddress(Network.port)), "client1")
  val client2 = system.actorOf(TcpClient.props(new InetSocketAddress(Network.port)), "client2")
}
