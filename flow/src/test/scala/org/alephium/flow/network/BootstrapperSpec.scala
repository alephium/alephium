package org.alephium.flow.network

import akka.testkit.TestProbe

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.util.{ActorRefT, AlephiumSpec}

class BootstrapperSpec extends AlephiumSpec {

  it should "bootstrap a master node" in new MasterFixture("BootstrapperSpec-master") {
    val serverProbe          = TestProbe()
    val discoveryServerProbe = TestProbe()
    val cliqueManagerProbe   = TestProbe()

    val bootstrapper = system.actorOf(
      Bootstrapper.props(ActorRefT(serverProbe.ref),
                         ActorRefT(discoveryServerProbe.ref),
                         ActorRefT(cliqueManagerProbe.ref)))
    serverProbe.expectMsg(TcpServer.Start(bootstrapper))
  }
  it should "bootstraall a peer node" in new AlephiumFlowActorSpec("BootstrapperSpec") {
    val serverProbe          = TestProbe()
    val discoveryServerProbe = TestProbe()
    val cliqueManagerProbe   = TestProbe()

    val bootstrapper = system.actorOf(
      Bootstrapper.props(ActorRefT(serverProbe.ref),
                         ActorRefT(discoveryServerProbe.ref),
                         ActorRefT(cliqueManagerProbe.ref)))
    serverProbe.expectMsg(TcpServer.Start(bootstrapper))
  }

  class MasterFixture(name: String) extends AlephiumFlowActorSpec(name) {
    override val configValues = Map(
      ("alephium.network.masterAddress", s"localhost:9972"),
      ("alephium.network.publicAddress", s"localhost:9972")
    )
  }
}
