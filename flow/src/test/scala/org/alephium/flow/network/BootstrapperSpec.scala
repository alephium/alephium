package org.alephium.flow.network

import akka.testkit.TestProbe
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.protocol.model.ModelGen
import org.alephium.util.AlephiumActorSpec

class BootstrapperSpec extends AlephiumActorSpec("BootstrapperSpec") {
  it should "bootstrap all actors" in new PlatformConfig.Default {
    val serverProb          = TestProbe()
    val discoveryServerProb = TestProbe()
    val cliqueManagerProb   = TestProbe()

    val bootstrapper = system.actorOf(
      Bootstrapper.props(new BrokerHandler.Builder {},
                         serverProb.ref,
                         discoveryServerProb.ref,
                         cliqueManagerProb.ref))

    val cliqueInfo = ModelGen.cliqueInfo.sample.get
    bootstrapper ! cliqueInfo

    cliqueManagerProb.expectMsgType[CliqueManager.Start]
    serverProb.expectMsg(cliqueManagerProb.ref)
    discoveryServerProb.expectMsg(cliqueInfo)
  }
}
