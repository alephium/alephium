package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import org.alephium.crypto.Hash.Sha256
import org.alephium.network.{TcpClient, TcpServer}
import org.alephium.primitive.BlockHeader

object TcpFun extends App {
  val system = ActorSystem("TcpFun")

  val port   = 9000
  val server = system.actorOf(TcpServer.props(port))
  Thread.sleep(100)

  val client1 = system.actorOf(TcpClient.props(new InetSocketAddress(port)))
  val client2 = system.actorOf(TcpClient.props(new InetSocketAddress(port)))
  Thread.sleep(100)

  val bh1 = BlockHeader(Sha256.hash("bh1"), 1)
  val bh2 = BlockHeader(Sha256.hash("bh2"), 2)
  client1 ! bh1
  client2 ! bh2
}
