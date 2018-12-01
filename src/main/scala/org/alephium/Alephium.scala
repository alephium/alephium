package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.io.Udp
import org.alephium.crypto.Hash.Sha256
import org.alephium.network.PeerHandler
import org.alephium.network.PeerHandler.Send
import org.alephium.primitive.BlockHeader

object Alephium extends App {

  val system = ActorSystem("Alephium")

  val port1 = 9080
  val port2 = 9081
  val peer1 = system.actorOf(PeerHandler.props(port1), "listener-1")
  val peer2 = system.actorOf(PeerHandler.props(port2), "listener-2")

  Thread.sleep(1000)

  val blockHeader1 = BlockHeader(Sha256.hash("block1"), 1)
  val blockHeader2 = BlockHeader(Sha256.hash("block2"), 2)
  peer1 ! Send(blockHeader1, new InetSocketAddress("localhost", port2))
  peer2 ! Send(blockHeader2, new InetSocketAddress("localhost", port1))

  Thread.sleep(1000)

  peer1 ! Udp.Unbind
  peer2 ! Udp.Unbind

  Thread.sleep(1000)

  system.terminate()
}
