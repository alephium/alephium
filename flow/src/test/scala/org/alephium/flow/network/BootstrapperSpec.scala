package org.alephium.flow.network

import akka.io.Tcp
import akka.testkit.TestProbe

import org.alephium.flow._
import org.alephium.flow.network.bootstrap.InfoFixture
import org.alephium.protocol.model.NoIndexModelGeneratorsLike
import org.alephium.util.ActorRefT

class BootstrapperSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  it should "bootstrap a master" in new MasterFixture("BootstrapperSpec-master") {
    val bootstrapper = system.actorOf(
      Bootstrapper.props(
        ActorRefT(serverProbe.ref),
        ActorRefT(discoveryServerProbe.ref),
        ActorRefT(cliqueManagerProbe.ref),
        (_, _) => cliqueCoordinatorProbe.ref,
        (_, _) => TestProbe().ref
      ))

    serverProbe.expectMsg(TcpServer.Start(bootstrapper))

    //Peer connects
    bootstrapper ! connected
    cliqueCoordinatorProbe.expectMsg(connected)

    //Broker info is full,
    cliqueManagerProbe.send(bootstrapper, Bootstrapper.ForwardConnection)
    serverProbe.expectMsg(TcpServer.WorkFor(cliqueManagerProbe.ref))

    //CliqueManager is now responsible for new connection
    bootstrapper ! connected
    cliqueManagerProbe.expectMsg(connected)

    //Receiving IntraCliqueInfo
    bootstrapper ! Bootstrapper.SendIntraCliqueInfo(intraCliqueInfo)
    cliqueManagerProbe.expectMsg(CliqueManager.Start(cliqueInfo))
    discoveryServerProbe.expectMsg(DiscoveryServer.SendCliqueInfo(cliqueInfo))

    //Answering IntraCliqueInfo request
    bootstrapper ! Bootstrapper.GetIntraCliqueInfo
    expectMsg(intraCliqueInfo)
  }

  it should "bootstrap a peer" in new AlephiumFlowActorSpec("BootstrapperSpec") {
    val serverProbe          = TestProbe()
    val discoveryServerProbe = TestProbe()
    val cliqueManagerProbe   = TestProbe()

    val bootstrapper = system.actorOf(
      Bootstrapper.props(ActorRefT(serverProbe.ref),
                         ActorRefT(discoveryServerProbe.ref),
                         ActorRefT(cliqueManagerProbe.ref)))
    serverProbe.expectMsg(TcpServer.Start(bootstrapper))
  }

  class MasterFixture(name: String) extends AlephiumFlowActorSpec(name) with InfoFixture {
    override val configValues = Map(
      ("alephium.network.master-address", s"localhost:9972"),
      ("alephium.network.public-address", s"localhost:9972")
    )

    val connected =
      Tcp.Connected(socketAddressGen.sample.get, socketAddressGen.sample.get)
    val intraCliqueInfo = genIntraCliqueInfo
    val cliqueInfo      = intraCliqueInfo.cliqueInfo

    val serverProbe            = TestProbe()
    val discoveryServerProbe   = TestProbe()
    val cliqueManagerProbe     = TestProbe()
    val cliqueCoordinatorProbe = TestProbe()
  }
}
