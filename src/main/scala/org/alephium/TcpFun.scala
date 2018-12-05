package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import org.alephium.client.Client
import org.alephium.constant.{Network, Protocol}
import org.alephium.crypto.ED25519
import org.alephium.network.{TcpClient, TcpServer}
import org.alephium.storage.BlockPool

object TcpFun extends App {
  // scalastyle:off magic.number
  val system = ActorSystem("TcpFun")

  val server = system.actorOf(TcpServer.props(Network.port), "server")
  val tcpHandler =
    system.actorOf(TcpClient.props(new InetSocketAddress("localhost", Network.port)), "client")
  val blockPool = new BlockPool()
  val client =
    Client(Protocol.Genesis.privateKey, Protocol.Genesis.publicKey, blockPool, tcpHandler)

  val (_, pk) = ED25519.generateKeyPair()
  client.transfer(pk, 100)
}
