package org.alephium.flow.network.coordinator

import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{BrokerId, CliqueInfo}
import org.alephium.util.AlephiumActorSpec

class CliqueCoordinatorSpec extends AlephiumActorSpec("CliqueCoordinatorSpec") {

  it should "await all the brokers" in new PlatformConfig.Default {
    val coordinator = system.actorOf(CliqueCoordinator.props())

    val probs = (0 until config.brokerNum)
      .filter(_ != config.brokerId.value)
      .map { i =>
        val probe   = TestProbe()
        val address = SocketUtil.temporaryServerAddress()
        coordinator.tell(BrokerConnector.BrokerInfo(BrokerId(i), address), probe.ref)
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
