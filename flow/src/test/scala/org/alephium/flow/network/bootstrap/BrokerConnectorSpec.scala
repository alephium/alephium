package org.alephium.flow.network.bootstrap

import scala.util.Random

import akka.io.Tcp
import akka.testkit.TestProbe

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.protocol.model.{BrokerInfo, ModelGen}

class BrokerConnectorSpec extends AlephiumFlowActorSpec("BrokerConnector") {
  it should "follow this workflow" in {
    val connection      = TestProbe()
    val brokerConnector = system.actorOf(BrokerConnector.props(connection.ref))
    val randomId        = Random.nextInt(config.brokerNum)
    val randomAddress   = ModelGen.socketAddress.sample.get
    val randomInfo      = BrokerInfo(randomId, config.groupNumPerBroker, randomAddress)

    connection.expectMsgType[Tcp.Register]
    watch(brokerConnector)

    val infoData = BrokerConnector.envelop(randomInfo).data
    brokerConnector.tell(Tcp.Received(infoData), connection.ref)

    brokerConnector ! ModelGen.cliqueInfo.sample.get
    connection.expectMsgType[Tcp.Write]

    val ackData = BrokerConnector.envelop(BrokerConnector.Ack(randomId)).data
    brokerConnector.tell(Tcp.Received(ackData), connection.ref)

    brokerConnector ! CliqueCoordinator.Ready
    brokerConnector.tell(Tcp.Closed, connection.ref)
    connection.expectMsgType[Tcp.Write]
    brokerConnector ! Tcp.PeerClosed
    expectTerminated(brokerConnector)
  }
}
