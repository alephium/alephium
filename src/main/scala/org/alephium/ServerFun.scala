package org.alephium

import akka.actor.ActorSystem
import org.alephium.client.Miner
import org.alephium.constant.Network
import org.alephium.crypto.ED25519PublicKey
import org.alephium.network.{PeerManager, TcpServer}
import org.alephium.storage.BlockPool
import org.alephium.util.Hex._

object ServerFun extends App {
  val system      = ActorSystem("ServerFun")
  val blockPool   = system.actorOf(BlockPool.props())
  val peerManager = system.actorOf(PeerManager.props(blockPool))
  val server      = system.actorOf(TcpServer.props(Network.port, peerManager), "server")

  val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
    hex"2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1c")
  val miner = system.actorOf(Miner.props(publicKey, blockPool, peerManager))

  miner ! Miner.Start
}
