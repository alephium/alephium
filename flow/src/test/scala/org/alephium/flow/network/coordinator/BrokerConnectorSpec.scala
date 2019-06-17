package org.alephium.flow.network.coordinator

import akka.io.Tcp
import akka.testkit.TestProbe
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{BrokerId, ModelGen}
import org.alephium.util.AlephiumActorSpec

import scala.util.Random

class BrokerConnectorSpec extends AlephiumActorSpec("BrokerConnector") {
  it should "follow this workflow" in new PlatformConfig.Default {
    val connection      = TestProbe()
    val brokerConnector = system.actorOf(BrokerConnector.props(connection.ref))
    val randomId        = BrokerId(Random.nextInt(config.brokerNum))
    val randomAddress   = ModelGen.socketAddress.sample.get
    val randomInfo      = BrokerConnector.BrokerInfo(randomId, randomAddress)

    connection.expectMsgType[Tcp.Register]
    watch(brokerConnector)

    val infoData = BrokerConnector.envolop(randomInfo).data
    brokerConnector.tell(Tcp.Received(infoData), connection.ref)

    brokerConnector ! ModelGen.cliqueInfo.sample.get
    connection.expectMsgType[Tcp.Write]

    val ackData = BrokerConnector.envolop(BrokerConnector.Ack(randomId.value)).data
    brokerConnector.tell(Tcp.Received(ackData), connection.ref)

    brokerConnector ! CliqueCoordinator.Ready
    brokerConnector.tell(Tcp.Closed, connection.ref)
    connection.expectMsgType[Tcp.Write]
    brokerConnector ! Tcp.PeerClosed
    expectTerminated(brokerConnector)
  }
}
