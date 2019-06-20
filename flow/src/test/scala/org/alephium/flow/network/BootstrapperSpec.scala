package org.alephium.flow.network

import akka.testkit.TestProbe
import org.alephium.flow.PlatformConfig
import org.alephium.util.AlephiumActorSpec

class BootstrapperSpec extends AlephiumActorSpec("BootstrapperSpec") {
  it should "bootstrap all actors" in new PlatformConfig.Default {
    val serverProb          = TestProbe()
    val discoveryServerProb = TestProbe()
    val cliqueManagerProb   = TestProbe()

    val bootstrapper = system.actorOf(
      Bootstrapper.props(serverProb.ref, discoveryServerProb.ref, cliqueManagerProb.ref))
    serverProb.expectMsg(TcpServer.Start(bootstrapper))
  }
}
