package org.alephium

import org.alephium.client.{Miner, Node}
import org.alephium.constant.Network
import org.alephium.crypto.ED25519PublicKey
import org.alephium.network.PeerManager
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.storage.BlockHandler
import org.alephium.util.Hex._

// scalastyle:off magic.number
object AlephiumFun extends App {
  val node = Node("ClientFun", Network.port)
  import node.{blockHandler, peerManager, system}

  Network.peers.foreach { remote =>
    peerManager ! PeerManager.Connect(remote)
  }

  val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
    hex"2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1d")

  for {
    i <- 0 until Network.groups
    j <- 0 until Network.groups
  } {
    val miner = system.actorOf(Miner.props(publicKey, node, ChainIndex(i, j)))
    miner ! Miner.Start
  }

  if (Network.peers.nonEmpty) {
    Thread.sleep(1000)
    0 to 5 foreach { _ =>
      Thread.sleep(3 * 1000)
      blockHandler.tell(BlockHandler.PrepareSync(Network.peers.head), peerManager)
    }
  }
}
