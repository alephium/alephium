package org.alephium.flow.network.bootstrap

import akka.testkit.{SocketUtil, TestProbe}

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.network.Bootstrapper
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo}

class CliqueCoordinatorSpec extends AlephiumFlowActorSpec("CliqueCoordinatorSpec") {

  it should "await all the brokers" in {
    val bootstrapper = TestProbe()
    val coordinator  = system.actorOf(CliqueCoordinator.props(bootstrapper.ref))

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

    bootstrapper.expectMsg(Bootstrapper.ForwardConnection)

    probs.foreach {
      case (id, p) =>
        coordinator.tell(BrokerConnector.Ack(id), p.ref)
    }
    probs.values.foreach(_.expectMsgType[CliqueCoordinator.Ready.type])

    watch(coordinator)
    probs.values.foreach(p => system.stop(p.ref))

    bootstrapper.expectMsgType[CliqueInfo]

    expectTerminated(coordinator)
  }
}
