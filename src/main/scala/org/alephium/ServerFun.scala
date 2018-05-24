package org.alephium

import akka.actor.ActorSystem
import org.alephium.client.Miner
import org.alephium.constant.Network
import org.alephium.crypto.ED25519PublicKey
import org.alephium.network.PeerManager
import org.alephium.storage.BlockHandler
import org.alephium.util.Hex._

object ServerFun extends App {
  val system       = ActorSystem("ServerFun")
  val blockHandler = system.actorOf(BlockHandler.props())
  val peerManager  = system.actorOf(PeerManager.props(Network.port, blockHandler))

  val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
    hex"2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1c")
  val miner = system.actorOf(Miner.props(publicKey, blockHandler, peerManager))

  miner ! Miner.Start
}
