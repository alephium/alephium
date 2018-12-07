package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import org.alephium.client.Miner
import org.alephium.constant.Network
import org.alephium.crypto.ED25519PublicKey
import org.alephium.network.PeerManager
import org.alephium.storage.BlockPoolHandler
import org.alephium.util.Hex._

// scalastyle:off magic.number
object ClientFun extends App {
  val system      = ActorSystem("ClientFun")
  val blockPool   = system.actorOf(BlockPoolHandler.props())
  val peerManager = system.actorOf(PeerManager.props(10303, blockPool))

  val remote = new InetSocketAddress(Network.port)
  peerManager ! PeerManager.Connect(remote)

  Thread.sleep(1000)
  0 to 10 foreach { _ =>
    Thread.sleep(30 * 1000)
    blockPool.tell(BlockPoolHandler.PrepareSync(remote), peerManager)
  }

  val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
    hex"2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1d")
  val miner = system.actorOf(Miner.props(publicKey, blockPool, peerManager))

  miner ! Miner.Start
}
