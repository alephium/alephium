package org.alephium.flow.network

import akka.testkit.TestProbe
import org.alephium.flow.AlephiumFlowActorSpec

class BootstrapperSpec extends AlephiumFlowActorSpec("BootstrapperSpec") {
  it should "bootstrap all actors" in {
    val serverProb          = TestProbe()
    val discoveryServerProb = TestProbe()
    val cliqueManagerProb   = TestProbe()

    val bootstrapper = system.actorOf(
      Bootstrapper.props(serverProb.ref, discoveryServerProb.ref, cliqueManagerProb.ref))
    serverProb.expectMsg(TcpServer.Start(bootstrapper))
  }
}
