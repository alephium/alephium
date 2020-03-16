package org.alephium.flow.network

import akka.testkit.TestProbe

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.util.ActorRefT

class BootstrapperSpec extends AlephiumFlowActorSpec("BootstrapperSpec") {
  it should "bootstrap all actors" in {
    val serverProbe          = TestProbe()
    val discoveryServerProbe = TestProbe()
    val cliqueManagerProbe   = TestProbe()

    val bootstrapper = system.actorOf(
      Bootstrapper.props(ActorRefT(serverProbe.ref),
                         ActorRefT(discoveryServerProbe.ref),
                         ActorRefT(cliqueManagerProbe.ref)))
    serverProbe.expectMsg(TcpServer.Start(bootstrapper))
  }
}
