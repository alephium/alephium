package org.alephium.flow.network.bootstrap

import akka.io.{IO, Tcp}
import akka.testkit.{SocketUtil, TestProbe}
import akka.util.ByteString

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.network.Bootstrapper
import org.alephium.util.ActorRefT

class BrokerSpec extends AlephiumFlowActorSpec("BrokerSpec") with InfoFixture {
  it should "follow this workflow" in {
    val connection    = TestProbe()
    val bootstrapper  = TestProbe()
    val masterAddress = SocketUtil.temporaryServerAddress()

    IO(Tcp) ! Tcp.Bind(connection.ref, masterAddress)
    expectMsgType[Tcp.Bound]

    val broker = system.actorOf(
      Broker.props(masterAddress,
                   config.brokerInfo,
                   config.retryTimeout,
                   ActorRefT[Bootstrapper.Command](bootstrapper.ref)))
    watch(broker)

    connection.expectMsgPF() {
      case Tcp.Connected(_, expectedMasterAddress) =>
        expectedMasterAddress is masterAddress
    }
    connection.reply(Tcp.Register(connection.ref))

    connection.expectMsgPF() {
      case Tcp.Received(data) =>
        PeerInfo._deserialize(data) isE (PeerInfo.self -> ByteString.empty)
    }

    val randomInfo = genIntraCliqueInfo
    val infoData   = BrokerConnector.envelop(randomInfo).data
    broker.tell(Tcp.Received(infoData), connection.ref)
    connection.expectMsgPF() {
      case Tcp.Received(data) =>
        BrokerConnector.deserializeTry[BrokerConnector.Ack](data) is Right(
          Some((BrokerConnector.Ack(config.brokerInfo.brokerId), ByteString.empty)))
    }

    val ready = BrokerConnector.envelop(CliqueCoordinator.Ready).data
    broker.tell(Tcp.Received(ready), connection.ref)
    connection.expectMsg(Tcp.PeerClosed)

    broker ! Tcp.ConfirmedClosed
    bootstrapper.expectMsg(Bootstrapper.SendIntraCliqueInfo(randomInfo))
    expectTerminated(broker)
  }
}
