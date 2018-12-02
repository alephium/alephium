package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import org.alephium.network.{TcpClient, TcpServer}

object TcpFun extends App {
  val system = ActorSystem("TcpFun")

  val port   = 9000
  val server = system.actorOf(TcpServer.props(port), "server")
  Thread.sleep(500)

  val client1 = system.actorOf(TcpClient.props(new InetSocketAddress(port)), "client1")
  val client2 = system.actorOf(TcpClient.props(new InetSocketAddress(port)), "client2")
}
