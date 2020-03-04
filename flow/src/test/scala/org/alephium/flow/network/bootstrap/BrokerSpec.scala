package org.alephium.flow.network.bootstrap

import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.protocol.model.ModelGen

class BrokerSpec extends AlephiumFlowActorSpec("BrokerSpec") {
  it should "follow this workflow" in {
    val connection    = TestProbe()
    val bootstrapper  = TestProbe()
    val masterAddress = SocketUtil.temporaryServerAddress()

    IO(Tcp) ! Tcp.Bind(connection.ref, masterAddress)
    expectMsgType[Tcp.Bound]

    val broker = system.actorOf(
      Broker.props(masterAddress, config.brokerInfo, config.retryTimeout, bootstrapper.ref))
    watch(broker)

    broker.tell(Tcp.Connected(SocketUtil.temporaryServerAddress(), config.publicAddress),
                connection.ref)
    connection.expectMsgType[Tcp.Register]
    connection.expectMsgType[Tcp.Write]

    val randomInfo = ModelGen.cliqueInfo.sample.get
    val infoData   = BrokerConnector.envelop(randomInfo).data
    broker.tell(Tcp.Received(infoData), connection.ref)
    connection.expectMsgType[Tcp.Write]

    val ready = BrokerConnector.envelop(CliqueCoordinator.Ready).data
    broker.tell(Tcp.Received(ready), connection.ref)
    connection.expectMsg(Tcp.ConfirmedClose)

    broker ! Tcp.ConfirmedClosed
    bootstrapper.expectMsg(randomInfo)
    expectTerminated(broker)
  }
}
