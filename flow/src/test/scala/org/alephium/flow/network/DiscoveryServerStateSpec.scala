package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.event.LoggingAdapter
import akka.io.Udp
import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.model.{ModelGen, PeerInfo}
import org.alephium.util.{AVector, AlephiumActorSpec}
import org.scalacheck.Gen
import org.scalatest.Assertion

import scala.concurrent.duration._
import scala.reflect.ClassTag

class DiscoveryServerStateSpec extends AlephiumActorSpec("DiscoveryServer") {
  import DiscoveryServerSpec._
  import DiscoveryMessage._

  trait Fixture { self =>
    def groupSize: Int                = 3
    val udpPort: Int                  = SocketUtil.temporaryLocalPort(udp = true)
    def peersPerGroup: Int            = 1
    def scanFrequency: FiniteDuration = 500.millis
    val socketProbe                   = TestProbe()

    implicit val config: DiscoveryConfig =
      createConfig(groupSize, 0, udpPort, peersPerGroup, scanFrequency)

    val state = new DiscoveryServerState {
      implicit def config: DiscoveryConfig = self.config
      def log: LoggingAdapter              = system.log

      def bootstrap: AVector[InetSocketAddress] = AVector.empty

      setSocket(socketProbe.ref)
    }
    val peer = ModelGen.peerInfo.sample.get

    def expectPayload[T <: DiscoveryMessage.Payload: ClassTag]: Assertion = {
      val peerConfig =
        createConfig(groupSize, 0, udpPort, peersPerGroup, scanFrequency)
      socketProbe.expectMsgPF() {
        case send: Udp.Send =>
          val message = DiscoveryMessage.deserialize(send.payload)(peerConfig).get
          message.payload is a[T]
      }
    }

    def addToTable(peer: PeerInfo): Assertion = {
      state.tryPing(peer)
      state.isPending(peer.id) is true
      state.handlePong(peer.id)
      state.isInTable(peer.id) is true
    }
  }

  it should "add peer into pending list when just pinged the peer" in new Fixture {
    state.getActivePeers.sumBy(_.length) is 0
    state.isUnknown(peer.id) is true
    state.isPending(peer.id) is false
    state.isPendingAvailable is true
    state.tryPing(peer)
    expectPayload[Ping]
    state.isUnknown(peer.id) is false
    state.isPending(peer.id) is true
  }

  trait PingedFixture extends Fixture {
    state.tryPing(peer)
    expectPayload[Ping]
    state.isInTable(peer.id) is false
  }

  it should "remove peer from pending list when received pong back" in new PingedFixture {
    state.handlePong(peer.id)
    expectPayload[FindNode]
    state.isUnknown(peer.id) is false
    state.isPending(peer.id) is false
    state.isInTable(peer.id) is true
  }

  it should "clean up everything if timeout is negative" in new Fixture {
    override def scanFrequency: FiniteDuration = (-1).millis

    addToTable(peer)
    val peer0 = ModelGen.peerInfo.sample.get
    state.tryPing(peer0)
    state.isPending(peer0.id) is true
    state.cleanup()
    state.isInTable(peer.id) is false
    state.isPending(peer0.id) is false
  }

  // TODO: use scalacheck
  it should "sort neighbors with respect to target" in new Fixture {
    override def peersPerGroup: Int = 4

    state.getActivePeers.sumBy(_.length) is 0
    val groupIndex = config.nodeInfo.group
    val toAdds     = Gen.listOfN(peersPerGroup - 1, ModelGen.peerInfo(groupIndex)).sample.get
    toAdds.foreach(addToTable)

    val peers0 = state.getNeighbors(peer.id)
    peers0.sumBy(_.length) is peersPerGroup
    val bucket0 = peers0(groupIndex.value).map(p => peer.id.hammingDist(p.id)).toIterable.toList
    bucket0 is bucket0.sorted

    val peers1 = state.getNeighbors(config.peerId)
    peers1.sumBy(_.length) is peersPerGroup - 1
    val bucket1 =
      peers1(groupIndex.value).map(p => config.peerId.hammingDist(p.id)).toIterable.toList
    bucket1 is bucket1.sorted
  }
}
