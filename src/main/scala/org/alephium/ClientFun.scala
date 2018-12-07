package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import org.alephium.constant.Network
import org.alephium.network.PeerManager
import org.alephium.storage.BlockPool

// scalastyle:off magic.number
object ClientFun extends App {
  val system      = ActorSystem("ClientFun")
  val blockPool   = system.actorOf(BlockPool.props())
  val peerManager = system.actorOf(PeerManager.props(blockPool))

  val remote = new InetSocketAddress(Network.port)
  peerManager ! PeerManager.Connect(remote)

  Thread.sleep(1000)
  0 to 10 foreach { _ =>
    Thread.sleep(30 * 1000)
    blockPool.tell(BlockPool.PrepareSync(remote), peerManager)
  }
}
