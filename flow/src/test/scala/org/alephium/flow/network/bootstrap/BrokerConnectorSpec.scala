package org.alephium.flow.network.bootstrap

import akka.io.Tcp
import akka.testkit.TestProbe
import akka.util.ByteString

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.network.Bootstrapper
import org.alephium.protocol.model.ModelGenerators
import org.alephium.util.Random

class BrokerConnectorSpec
    extends AlephiumFlowActorSpec("BrokerConnector")
    with InfoFixture
    with ModelGenerators {
  it should "follow this workflow" in {
    val connection        = TestProbe()
    val cliqueCoordinator = TestProbe()
    val brokerConnector =
      system.actorOf(
        BrokerConnector.props(socketAddressGen.sample.get, connection.ref, cliqueCoordinator.ref))

    val randomId      = Random.source.nextInt(brokerConfig.brokerNum)
    val randomAddress = socketAddressGen.sample.get
    val randomInfo = PeerInfo.unsafe(randomId,
                                     brokerConfig.groupNumPerBroker,
                                     randomAddress.getAddress,
                                     randomAddress.getPort,
                                     None,
                                     None)

    connection.expectMsgType[Tcp.Register]
    watch(brokerConnector)

    val infoData = Message.serialize(Message.Peer(randomInfo))
    brokerConnector ! Tcp.Received(infoData)

    cliqueCoordinator.expectMsgType[PeerInfo]

    val randomCliqueInfo = genIntraCliqueInfo
    brokerConnector ! Bootstrapper.SendIntraCliqueInfo(randomCliqueInfo)
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        SerdeUtils.unwrap(IntraCliqueInfo._deserialize(data)) is Right(
          Some((randomCliqueInfo, ByteString.empty)))
    }

    val ackData = Message.serialize(Message.Ack(randomId))
    brokerConnector ! Tcp.Received(ackData)

    brokerConnector ! CliqueCoordinator.Ready
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        BrokerConnector.deserializeTry[CliqueCoordinator.Ready.type](data) is Right(
          Some((CliqueCoordinator.Ready, ByteString.empty)))
    }

    brokerConnector ! Tcp.PeerClosed

    expectTerminated(brokerConnector)
  }
}
