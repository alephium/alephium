package org.alephium.flow.network.coordinator

import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo}

class CliqueCoordinatorSpec extends AlephiumFlowActorSpec("CliqueCoordinatorSpec") {

  it should "await all the brokers" in {
    val coordinator = system.actorOf(CliqueCoordinator.props())

    val probs = (0 until config.brokerNum)
      .filter(_ != config.brokerInfo.id)
      .map { i =>
        val probe   = TestProbe()
        val address = SocketUtil.temporaryServerAddress()
        coordinator.tell(BrokerInfo(i, config.groupNumPerBroker, address), probe.ref)
        (i, probe)
      }
      .toMap

    probs.values.foreach(_.expectMsgType[CliqueInfo])

    probs.foreach {
      case (id, p) =>
        coordinator.tell(BrokerConnector.Ack(id), p.ref)
    }
    probs.values.foreach(_.expectMsgType[CliqueCoordinator.Ready.type])

    watch(coordinator)
    probs.values.foreach(p => system.stop(p.ref))
    expectTerminated(coordinator)
  }
}
