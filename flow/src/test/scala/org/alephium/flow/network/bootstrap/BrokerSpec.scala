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
      Broker.props(ActorRefT[Bootstrapper.Command](bootstrapper.ref))(
        brokerConfig,
        networkSetting.copy(masterAddress = masterAddress)))
    watch(broker)

    connection.expectMsgPF() {
      case Tcp.Connected(_, expectedMasterAddress) =>
        expectedMasterAddress is masterAddress
    }
    connection.reply(Tcp.Register(connection.ref))

    connection.expectMsgPF() {
      case Tcp.Received(data) =>
        Message.deserialize(data) isE (Message.Peer(PeerInfo.self) -> ByteString.empty)
    }

    val randomInfo = genIntraCliqueInfo
    broker.tell(Broker.Received(Message.Clique(randomInfo)), connection.ref)
    connection.expectMsgPF() {
      case Tcp.Received(data) =>
        Message.deserialize(data) isE (Message.Ack(brokerConfig.brokerId) -> ByteString.empty)
    }

    broker.tell(Broker.Received(Message.Ready), connection.ref)
    connection.expectMsg(Tcp.PeerClosed)

    bootstrapper.expectMsg(Bootstrapper.SendIntraCliqueInfo(randomInfo))
    expectTerminated(broker)
  }
}
