package org.alephium

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props}
import org.alephium.client.Client
import org.alephium.constant.Network
import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey}
import org.alephium.network.{PeerManager, TcpServer}
import org.alephium.protocol.Genesis
import org.alephium.protocol.message.{GetBlocks, Message}
import org.alephium.storage.BlockPool
import org.alephium.util.BaseActor
import org.alephium.util.Hex._

// scalastyle:off magic.number
class TcpFun extends BaseActor {
  private val blockPool   = context.actorOf(BlockPool.props())
  private val peerManager = context.actorOf(PeerManager.props(blockPool))
  val server: ActorRef    = context.actorOf(TcpServer.props(Network.port, peerManager), "server")

  private val privateKey: ED25519PrivateKey = ED25519PrivateKey.unsafeFrom(
    hex"604b105965f2bb262d5bede6f9790c7ba9ca08c0f31627ec24f52b67b59dfa65")
  private val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
    hex"2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1c")

  private val serverAddress = new InetSocketAddress("localhost", Network.port)

  override def preStart(): Unit = {
    peerManager ! PeerManager.Connect(serverAddress)
    Thread.sleep(1000)
    peerManager ! PeerManager.GetPeers
  }

  override def receive: Receive = tryTransfer

  def tryTransfer: Receive = {
    case PeerManager.Peers(peers) =>
      val tcpHandler = peers(serverAddress)
      val client     = context.actorOf(Client.props(privateKey, publicKey, blockPool, tcpHandler))

      Thread.sleep(1000)
      val (_, pk) = ED25519.generateKeyPair()
      0l to 10l foreach { i =>
        client ! Client.Transfer(pk, BigInt(i * 10))
        Thread.sleep(1000)
      }

      preStart()
      context.become(trySync)
  }

  def trySync: Receive = {
    case PeerManager.Peers(peers) =>
      val tcpHandler = peers(serverAddress)
      Thread.sleep(1000)
      tcpHandler ! Message(GetBlocks(Seq(Genesis.block.hash)))
      context.become(end)
  }

  def end: Receive = {
    case x => log.info(s"Deadletter $x")
  }
}

object TcpFun extends App {
  val system = ActorSystem("TcpFun")
  system.actorOf(Props(new TcpFun))
}
