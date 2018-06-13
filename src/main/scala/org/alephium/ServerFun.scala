package org.alephium

import org.alephium.client.{Miner, Node}
import org.alephium.constant.Network
import org.alephium.crypto.ED25519PublicKey
import org.alephium.util.Hex._

object ServerFun extends App {
  val node = Node("ServerFun", Network.port)
  import node.system

  val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
    hex"2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1c")
  val miner = system.actorOf(Miner.props(publicKey, node))

  miner ! Miner.Start
}
