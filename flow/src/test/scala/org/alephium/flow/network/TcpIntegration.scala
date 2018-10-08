package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.io.{IO, Tcp}
import akka.testkit.TestProbe
import org.alephium.util.AlephiumActorSpec
trait TcpIntegration { _: AlephiumActorSpec =>

  def bindServer(server: InetSocketAddress): Tcp.Bound = {
    val commander   = TestProbe()
    val bindHandler = TestProbe()
    commander.send(IO(Tcp), Tcp.Bind(bindHandler.ref, server))
    commander.expectMsg(Tcp.Bound(server))
  }
}
