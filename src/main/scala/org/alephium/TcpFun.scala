package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import org.alephium.client.Client
import org.alephium.constant.Network
import org.alephium.crypto.ED25519
import org.alephium.network.{TcpClient, TcpServer}

object TcpFun extends App {
  val system = ActorSystem("TcpFun")

  val server                  = system.actorOf(TcpServer.props(Network.port), "server")
  val client1                 = system.actorOf(TcpClient.props(new InetSocketAddress(Network.port)), "client1")
  val client2                 = system.actorOf(TcpClient.props(new InetSocketAddress(Network.port)), "client2")
  val tcpHandler              = system.actorOf(TcpClient.props(new InetSocketAddress(Network.port)), "client1")
  val (privateKey, publicKey) = ED25519.generateKeyPair()
  val client                  = Client(privateKey, publicKey, ???, tcpHandler)
}
