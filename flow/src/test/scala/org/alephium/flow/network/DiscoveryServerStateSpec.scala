package org.alephium.flow.network

import akka.event.LoggingAdapter
import akka.io.Udp
import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.flow.network.AnotherDiscoveryServer.FindNodeReceived
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.model.{ModelGen, PeerInfo}
import org.alephium.util.AlephiumActorSpec
import org.scalacheck.Gen
import org.scalatest.Assertion

import scala.concurrent.duration._
import scala.reflect.ClassTag

class DiscoveryServerStateSpec extends AlephiumActorSpec("DiscoveryServer") {
  import AnotherDiscoveryServerSpec._
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

      setSocket(socketProbe.ref)
    }
    val peer = ModelGen.peerInfo.sample.get

    def expectPayload[T <: DiscoveryMessage: ClassTag]: Assertion = {
      val peerConfig =
        createConfig(groupSize, 0, udpPort, peersPerGroup, scanFrequency)
      socketProbe.expectMsgPF() {
        case send: Udp.Send =>
          val message = DiscoveryMessage.deserialize(send.payload)(peerConfig).get
          message is a[T]
      }
    }

    def addToTable(peer: PeerInfo): Assertion = {
      state.tryPing(peer)
      state.isPending(peer.id) is true
      state.handlePong(peer)
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
    state.handlePong(peer)
    expectPayload[FindNode]
    state.isUnknown(peer.id) is false
    state.isPending(peer.id) is false
    state.isInTable(peer.id) is true
  }

  it should "pending find_node when received find_node before pong" in new PingedFixture {
    state.pendingFindNode(FindNode(CallId.generate, peer.id, peer.id))
    state.isPending(peer.id) is true
    state.getPending(peer.id).get is a[FindNodeReceived]
    state.handlePong(peer)
    expectPayload[Neighbors]
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

  it should "sort peers in table" in new Fixture {
    override def peersPerGroup: Int = 4

    val groupIndex = peer.group
    val toAdds     = Gen.listOfN(peersPerGroup, ModelGen.peerInfo(groupIndex)).sample.get
    toAdds.foreach(addToTable)
    val peers  = state.getActivePeers
    val bucket = peers(groupIndex.value).map(p => config.peerId.hammingDist(p.id)).toIterable.toList
    bucket is bucket.sorted
  }

  it should "sort neighbors with respect to target" in new Fixture {
    override def peersPerGroup: Int = 4

    val groupIndex = peer.group
    val toAdds     = Gen.listOfN(peersPerGroup, ModelGen.peerInfo(groupIndex)).sample.get
    toAdds.foreach(addToTable)
    val peers  = state.getNeighbors(peer.id)
    val bucket = peers(groupIndex.value).map(p => peer.id.hammingDist(p.id)).toIterable.toList
    bucket is bucket.sorted
  }
}
