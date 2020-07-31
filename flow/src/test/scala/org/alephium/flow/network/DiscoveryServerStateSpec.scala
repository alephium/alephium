package org.alephium.flow.network

import java.net.InetSocketAddress

import scala.reflect.ClassTag

import akka.event.LoggingAdapter
import akka.io.Udp
import akka.testkit.{SocketUtil, TestProbe}
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.protocol.config.{CliqueConfig, DiscoveryConfig, GroupConfig}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.model.{CliqueId, CliqueInfo, NoIndexModelGenerators}
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AVector, Duration}

class DiscoveryServerStateSpec
    extends AlephiumActorSpec("DiscoveryServer")
    with NoIndexModelGenerators {
  import DiscoveryMessage._
  import DiscoveryServerSpec._

  trait Fixture { self =>
    def groupSize: Int          = 3
    val udpPort: Int            = SocketUtil.temporaryLocalPort(udp = true)
    def peersPerGroup: Int      = 1
    def scanFrequency: Duration = Duration.unsafe(500)
    val socketProbe             = TestProbe()

    implicit val config: DiscoveryConfig with CliqueConfig =
      createConfig(groupSize, udpPort, peersPerGroup, scanFrequency)._2

    val state = new DiscoveryServerState {
      implicit def groupConfig: GroupConfig         = self.config
      implicit def discoveryConfig: DiscoveryConfig = self.config
      def log: LoggingAdapter                       = system.log

      def bootstrap: AVector[InetSocketAddress] = AVector.empty

      def selfCliqueInfo: CliqueInfo =
        CliqueInfo.unsafe(CliqueId.generate,
                          AVector.tabulate(config.brokerNum)(_ => socketAddressGen.sample.get),
                          config.groupNumPerBroker)

      setSocket(ActorRefT[Udp.Command](socketProbe.ref))
    }
    val peerClique: CliqueInfo = cliqueInfoGen.sample.get

    def expectPayload[T <: DiscoveryMessage.Payload: ClassTag]: Assertion = {
      val peerConfig =
        createConfig(groupSize, udpPort, peersPerGroup, scanFrequency)._2
      socketProbe.expectMsgPF() {
        case send: Udp.Send =>
          val message =
            DiscoveryMessage
              .deserialize(CliqueId.generate, send.payload)(peerConfig, peerConfig)
              .toOption
              .get
          message.payload is a[T]
      }
    }

    def addToTable(cliqueInfo: CliqueInfo): Assertion = {
      state.tryPing(cliqueInfo)
      state.isPending(cliqueInfo.id) is true
      state.handlePong(cliqueInfo)
      state.isInTable(cliqueInfo.id) is true
    }
  }

  it should "add peer into pending list when just pinged the peer" in new Fixture {
    state.getActivePeers.length is 0
    state.isUnknown(peerClique.id) is true
    state.isPending(peerClique.id) is false
    state.isPendingAvailable is true
    state.tryPing(peerClique)
    expectPayload[Ping]
    state.isUnknown(peerClique.id) is false
    state.isPending(peerClique.id) is true
  }

  trait PingedFixture extends Fixture {
    state.tryPing(peerClique)
    expectPayload[Ping]
    state.isInTable(peerClique.id) is false
  }

  it should "remove peer from pending list when received pong back" in new PingedFixture {
    state.handlePong(peerClique)
    expectPayload[FindNode]
    state.isUnknown(peerClique.id) is false
    state.isPending(peerClique.id) is false
    state.isInTable(peerClique.id) is true
  }

  it should "clean up everything if timeout is zero" in new Fixture {
    override def scanFrequency: Duration = Duration.unsafe(0)

    addToTable(peerClique)
    val peer0 = cliqueInfoGen.sample.get
    state.tryPing(peer0)
    state.isPending(peer0.id) is true
    state.cleanup()
    state.isInTable(peerClique.id) is false
    state.isPending(peer0.id) is false
  }

  // TODO: use scalacheck
  it should "sort neighbors with respect to target" in new Fixture {
    override def peersPerGroup: Int = 4

    state.getActivePeers.length is 0
    val toAdds = Gen.listOfN(peersPerGroup - 1, cliqueInfoGen).sample.get
    toAdds.foreach(addToTable)

    val peers0 = state.getNeighbors(peerClique.id)
    peers0.length is peersPerGroup
    val bucket0 =
      peers0.map(p => peerClique.id.hammingDist(p.id)).toIterable.toList
    bucket0 is bucket0.sorted
  }
}
