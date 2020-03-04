package org.alephium.flow.network.bootstrap

import scala.util.Random

import akka.io.Tcp
import akka.testkit.TestProbe
import akka.util.ByteString

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo, ModelGen}

class BrokerConnectorSpec extends AlephiumFlowActorSpec("BrokerConnector") {
  it should "follow this workflow" in {
    val connection        = TestProbe()
    val cliqueCoordinator = TestProbe()
    val brokerConnector =
      system.actorOf(BrokerConnector.props(connection.ref, cliqueCoordinator.ref))
    val randomId      = Random.nextInt(config.brokerNum)
    val randomAddress = ModelGen.socketAddress.sample.get
    val randomInfo    = BrokerInfo(randomId, config.groupNumPerBroker, randomAddress)

    connection.expectMsgType[Tcp.Register]
    watch(brokerConnector)

    val infoData = BrokerConnector.envelop(randomInfo).data
    brokerConnector ! Tcp.Received(infoData)

    cliqueCoordinator.expectMsgType[BrokerInfo]

    val randomCliqueInfo = ModelGen.cliqueInfo.sample.get
    brokerConnector ! randomCliqueInfo
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        BrokerConnector.deserializeTryWithValidation[CliqueInfo, CliqueInfo.Unsafe](data) is Right(
          Some((randomCliqueInfo, ByteString.empty)))
    }

    val ackData = BrokerConnector.envelop(BrokerConnector.Ack(randomId)).data
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
