package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import org.alephium.client.Client
import org.alephium.constant.Network
import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey}
import org.alephium.network.{BlockHandler, TcpClient, TcpServer}
import org.alephium.storage.BlockPool
import org.alephium.util.{Hex, UInt}

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

  val privateKey: ED25519PrivateKey = ED25519PrivateKey.unsafeFrom(
    Hex("604b105965f2bb262d5bede6f9790c7ba9ca08c0f31627ec24f52b67b59dfa65"))
  val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
    Hex("2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1c"))
  val client =
    Client(privateKey, publicKey, BlockPool(), tcpHandler)

  Thread.sleep(1000)
  val (_, pk) = ED25519.generateKeyPair()
  0 to 10 foreach { i =>
    client.transfer(pk, UInt(i * 10))
  }
}
