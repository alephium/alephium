package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import org.alephium.network.message.{NetworkMessage, Ping, Pong}
import org.alephium.network.{TcpClient, TcpServer}

object TcpFun extends App {
  val system = ActorSystem("TcpFun")

  val port   = 9000
  val server = system.actorOf(TcpServer.props(port))
  Thread.sleep(100)

  val client1 = system.actorOf(TcpClient.props(new InetSocketAddress(port)))
  val client2 = system.actorOf(TcpClient.props(new InetSocketAddress(port)))
  Thread.sleep(100)

  val msg1 = NetworkMessage(0, Ping(0))
  val msg2 = NetworkMessage(1, Pong(1))
  client1 ! msg1
  client2 ! msg2

  Thread.sleep(100)
  system.terminate()
}
