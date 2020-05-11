package org.alephium.appserver

import java.net.InetSocketAddress

import akka.actor.Terminated
import akka.io.{IO, Tcp}
import akka.testkit.TestProbe

import org.alephium.util._

class ShutdownTest extends AlephiumSpec {
  it should "shutdown the node when Tcp port is used" in new TestFixture("1-node") {
    val connection = TestProbe()
    IO(Tcp) ! Tcp.Bind(connection.ref, new InetSocketAddress(defaultMasterPort))

    val server = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server.system.whenTerminated.futureValue is a[Terminated]
  }

  it should "shutdown the clique when one node of the clique is down" in new TestFixture("2-nodes") {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    val server1 = bootNode(publicPort = generatePort, brokerId      = 1)
    Seq(server0.start(), server1.start()).foreach(_.futureValue is (()))

    server0.stop().futureValue is (())
    server1.system.whenTerminated.futureValue is a[Terminated]
  }
}
