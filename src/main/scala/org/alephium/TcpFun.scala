package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import org.alephium.client.Client
import org.alephium.constant.{Network, Protocol}
import org.alephium.crypto.ED25519
import org.alephium.network.{BlockHandler, TcpClient, TcpServer}
import org.alephium.storage.BlockPool

object TcpFun extends App {
  // scalastyle:off magic.number
  val system = ActorSystem("TcpFun")

  val blockPool    = BlockPool()
  val blockHandler = system.actorOf(BlockHandler.props(blockPool), "block-handler")
  val server       = system.actorOf(TcpServer.props(Network.port, blockHandler), "server")
  val tcpHandler = system.actorOf(
    TcpClient.props(new InetSocketAddress("localhost", Network.port), blockHandler),
    "client"
  )

  val client =
    Client(Protocol.Genesis.privateKey, Protocol.Genesis.publicKey, BlockPool(), tcpHandler)

  Thread.sleep(1000)
  val (_, pk) = ED25519.generateKeyPair()
  0 to 10 foreach { i =>
    Thread.sleep(200)
    client.transfer(pk, i * 10)
  }
}
