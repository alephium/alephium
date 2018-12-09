package org.alephium

import java.net.InetSocketAddress

import org.alephium.client.{Miner, Node}
import org.alephium.constant.Network
import org.alephium.crypto.ED25519PublicKey
import org.alephium.network.PeerManager
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.util.Hex._

// scalastyle:off magic.number
object AlephiumFun extends App {
  val node = Node("ClientFun", Network.port)
  import node.{peerManager, system}

  Thread.sleep(1000 * 20)

  (9973 to 9976).filter(_ != Network.port).foreach { peerPort =>
    val remote = new InetSocketAddress("localhost", peerPort)
    peerManager ! PeerManager.Connect(remote)
  }

  Thread.sleep(1000 * 20)

  val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
    hex"2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1d")

  val index = Network.port - 9973
  val miner = system.actorOf(Miner.props(publicKey, node, ChainIndex(index / 2, index % 2)))
  miner ! Miner.Start
}
