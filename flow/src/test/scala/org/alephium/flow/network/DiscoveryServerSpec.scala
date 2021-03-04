// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.network

import java.net.InetSocketAddress

import scala.util.Random

import akka.io.Udp
import akka.testkit.{SocketUtil, TestProbe}
import akka.util.Timeout
import org.scalacheck.Gen
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.protocol.{ALF, SignatureSchema}
import org.alephium.protocol.config._
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AVector, Duration}

object DiscoveryServerSpec {

  def createConfig(groupSize: Int,
                   port: Int,
                   _peersPerGroup: Int,
                   _scanFrequency: Duration  = Duration.unsafe(200),
                   _expireDuration: Duration = Duration.ofHoursUnsafe(1))
    : (InetSocketAddress, DiscoveryConfig with BrokerConfig) = {
    val publicAddress: InetSocketAddress = new InetSocketAddress("localhost", port)
    val discoveryConfig = new DiscoveryConfig with BrokerConfig {
      val (discoveryPrivateKey, discoveryPublicKey) = SignatureSchema.generatePriPub()

      val peersPerGroup: Int          = _peersPerGroup
      val scanMaxPerGroup: Int        = 1
      val scanFrequency: Duration     = _scanFrequency
      val scanFastFrequency: Duration = _scanFrequency
      val neighborsPerGroup: Int      = _peersPerGroup

      override val expireDuration: Duration = _expireDuration

      val groups: Int    = groupSize
      val brokerNum: Int = groupSize
      val brokerId: Int  = Random.nextInt(brokerNum)
    }
    publicAddress -> discoveryConfig
  }
}

class DiscoveryServerSpec
    extends AlephiumActorSpec("DiscoveryServerSpec")
    with NoIndexModelGenerators
    with ScalaFutures {
  import DiscoveryServerSpec._

  def generateCliqueInfo(master: InetSocketAddress, groupConfig: GroupConfig): CliqueInfo = {
    val newInfo = CliqueInfo.unsafe(
      CliqueId.generate,
      AVector.tabulate(groupConfig.groups)(i =>
        Option(new InetSocketAddress(master.getAddress, master.getPort + i))),
      AVector.tabulate(groupConfig.groups)(i =>
        new InetSocketAddress(master.getAddress, master.getPort + i)),
      1
    )
    CliqueInfo.validate(newInfo)(groupConfig).isRight is true
    newInfo.coordinatorAddress is master
    newInfo
  }

  it should "discovery each other for two cliques" in new Fixture {

    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)
    server1 ! DiscoveryServer.SendCliqueInfo(cliqueInfo1)

    Thread.sleep(1000)

    val probe0 = TestProbe()
    server0.tell(DiscoveryServer.GetNeighborCliques, probe0.ref)
    val probe1 = TestProbe()
    server1.tell(DiscoveryServer.GetNeighborCliques, probe1.ref)

    probe0.expectMsgPF() {
      case DiscoveryServer.NeighborPeers(peers) =>
        peers.length is 1
        peers.head is cliqueInfo1.interBrokers.get.head
    }
    probe1.expectMsgPF() {
      case DiscoveryServer.NeighborPeers(peers) =>
        peers.length is 1
        peers.head is cliqueInfo0.interBrokers.get.head
    }
  }

  it should "refuse to discover a banned clique" in new Fixture {

    misbehaviorManager0.ref ! MisbehaviorManager.InvalidMessage(address1)

    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)
    server1 ! DiscoveryServer.SendCliqueInfo(cliqueInfo1)

    Thread.sleep(1000)

    val probe0 = TestProbe()
    server0.tell(DiscoveryServer.GetNeighborCliques, probe0.ref)
    val probe1 = TestProbe()
    server1.tell(DiscoveryServer.GetNeighborCliques, probe1.ref)

    probe0.expectMsgPF() {
      case DiscoveryServer.NeighborPeers(peers) =>
        peers.length is 0
    }
    probe1.expectMsgPF() {
      case DiscoveryServer.NeighborPeers(peers) =>
        peers.length is 1
        peers.head is cliqueInfo0.interBrokers.get.head
    }
  }

  it should "refuse to ping a peer with unmatching peer info" in new Fixture {
    implicit val askTimeout: Timeout = Timeout(Duration.ofSecondsUnsafe(10).asScala)

    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)

    misbehaviorManager0
      .ask(MisbehaviorManager.GetPeers)
      .mapTo[MisbehaviorManager.Peers]
      .futureValue is MisbehaviorManager.Peers(AVector.empty)

    val message = DiscoveryMessage.from(
      cliqueInfo1.id,
      DiscoveryMessage.Ping(
        Some(BrokerInfo.from(socketAddressGen.sample.get, cliqueInfo1.selfInterBrokerInfo))))(
      config1)
    server0 ! Udp.Received(DiscoveryMessage.serialize(message, networkConfig.networkType)(config1),
                           address1)

    eventually {
      misbehaviorManager0
        .ask(MisbehaviorManager.GetPeers)
        .mapTo[MisbehaviorManager.Peers]
        .futureValue
        .peers
        .head
        .peer is address1
    }
  }

  trait Fixture extends BrokerConfigFixture.Default {
    val groups              = Gen.choose(2, 10).sample.get
    val port0               = SocketUtil.temporaryLocalPort(udp = true)
    val (address0, config0) = createConfig(groups, port0, 1)
    val cliqueInfo0         = generateCliqueInfo(address0, groupConfig)
    val port1               = SocketUtil.temporaryLocalPort(udp = true)
    val (address1, config1) = createConfig(groups, port1, 1)
    val cliqueInfo1         = generateCliqueInfo(address1, groupConfig)
    val networkConfig       = new NetworkConfig { val networkType = NetworkType.Testnet }
    val misbehaviorManager0: ActorRefT[MisbehaviorManager.Command] =
      ActorRefT.build(system, MisbehaviorManager.props(ALF.BanDuration))
    val misbehaviorManager1: ActorRefT[MisbehaviorManager.Command] =
      ActorRefT.build(system, MisbehaviorManager.props(ALF.BanDuration))

    val server0 =
      system.actorOf(
        DiscoveryServer.props(address0, misbehaviorManager0)(brokerConfig, config0, networkConfig))
    val server1 =
      system.actorOf(
        DiscoveryServer
          .props(address1, misbehaviorManager1, address0)(brokerConfig, config1, networkConfig))

    val misbehaviorProbe = TestProbe()
    system.eventStream.subscribe(misbehaviorProbe.ref, classOf[MisbehaviorManager.InvalidMessage])
  }
}
