package org.alephium.flow.network.coordinator

import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{BrokerId, CliqueInfo}
import org.alephium.util.{AVector, AlephiumActorSpec}

class CliqueCoordinatorSpec extends AlephiumActorSpec("CliqueCoordinatorSpec") {

  it should "await all the brokers" in new PlatformConfig.Default {
    val coordinator = system.actorOf(CliqueCoordinator.props())

    val probs = AVector.tabulate(config.brokerNum) { i =>
      val probe   = TestProbe()
      val address = SocketUtil.temporaryServerAddress()
      coordinator.tell(BrokerConnector.BrokerInfo(BrokerId(i), address), probe.ref)
      probe
    }
    probs.foreach(_.expectMsgType[CliqueInfo])

    probs.foreachWithIndex((p, id) => coordinator.tell(BrokerConnector.Ack(id), p.ref))
    probs.foreach(_.expectMsgType[BrokerConnector.Ready.type])

    watch(coordinator)
    probs.foreach(p => system.stop(p.ref))
    expectTerminated(coordinator)
  }
}
