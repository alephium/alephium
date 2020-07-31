package org.alephium.flow.network.bootstrap

import akka.testkit.{SocketUtil, TestProbe}

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.network.Bootstrapper
import org.alephium.util.ActorRefT

class CliqueCoordinatorSpec extends AlephiumFlowActorSpec("CliqueCoordinatorSpec") {

  it should "await all the brokers" in {
    val bootstrapper = TestProbe()
    val coordinator  = system.actorOf(CliqueCoordinator.props(ActorRefT(bootstrapper.ref)))

    val probs = (0 until brokerConfig.brokerNum)
      .filter(_ != brokerConfig.brokerId)
      .map { i =>
        val probe   = TestProbe()
        val address = SocketUtil.temporaryServerAddress()
        val peerInfo = PeerInfo.unsafe(i,
                                       brokerConfig.groupNumPerBroker,
                                       address.getAddress,
                                       address.getPort,
                                       None,
                                       None)
        coordinator.tell(peerInfo, probe.ref)
        (i, probe)
      }
      .toMap

    probs.values.foreach(_.expectMsgType[Bootstrapper.SendIntraCliqueInfo])

    bootstrapper.expectMsg(Bootstrapper.ForwardConnection)

    probs.foreach {
      case (id, p) =>
        coordinator.tell(BrokerConnector.Ack(id), p.ref)
    }
    probs.values.foreach(_.expectMsgType[CliqueCoordinator.Ready.type])

    watch(coordinator)
    probs.values.foreach(p => system.stop(p.ref))

    bootstrapper.expectMsgType[Bootstrapper.SendIntraCliqueInfo]

    expectTerminated(coordinator)
  }
}
