package org.alephium

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.io.Udp
import org.alephium.network.PeerHandler
import org.alephium.network.PeerHandler.Send

object Alephium extends App {
  val system = ActorSystem("Alephium")

  val port1 = 9080
  val port2 = 9081
  val peer1 = system.actorOf(PeerHandler.props(port1), "listener-1")
  val peer2 = system.actorOf(PeerHandler.props(port2), "listener-2")

  Thread.sleep(1000)

  peer1 ! Send("Hello from peer1", new InetSocketAddress("localhost", port2))
  peer2 ! Send("Hello from peer2", new InetSocketAddress("localhost", port1))

  Thread.sleep(1000)

  peer1 ! Udp.Unbind
  peer2 ! Udp.Unbind
}
