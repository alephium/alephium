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

import akka.testkit.{TestActorRef, TestProbe}
import akka.util.Timeout
import org.scalacheck.Gen
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Seconds, Span}

import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.network.udp.UdpServer
import org.alephium.protocol._
import org.alephium.protocol.config._
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.model._
import org.alephium.util._

class DiscoveryServerSpec
    extends RefinedAlephiumActorSpec
    with ScalaFutures
    with Eventually
    with SocketUtil {
  import DiscoveryServerSpec._

  trait SimulationFixture { fixture =>
    implicit val patienceConfig =
      PatienceConfig(timeout = Span(60, Seconds), interval = Span(2, Seconds))

    def groups: Int

    def generateClique(): (CliqueInfo, AVector[(BrokerInfo, BrokerConfig with DiscoveryConfig)]) = {
      val brokerNum = {
        val candidates = (1 to groups).filter(groups % _ equals 0)
        candidates(Random.nextInt(candidates.size))
      }
      val groupNumPerBroker                           = groups / brokerNum
      val addresses                                   = AVector.fill(brokerNum)(new InetSocketAddress("127.0.0.1", generatePort()))
      val (discoveryPrivateKey0, discoveryPublicKey0) = SignatureSchema.generatePriPub()
      val clique =
        CliqueInfo.unsafe(
          CliqueId(discoveryPublicKey0),
          addresses.map(Some(_)),
          addresses,
          groupNumPerBroker,
          discoveryPrivateKey0
        )

      val brokers = clique.interBrokers.get
      val infos = brokers.map { brokerInfo =>
        val config: BrokerConfig with DiscoveryConfig = new BrokerConfig with DiscoveryConfig {
          val brokerId: Int  = brokerInfo.brokerId
          val brokerNum: Int = clique.brokerNum
          val groups: Int    = fixture.groups

          val peersPerGroup: Int          = 3
          val scanMaxPerGroup: Int        = 3
          val scanFrequency: Duration     = Duration.ofMillisUnsafe(2000)
          val scanFastFrequency: Duration = Duration.ofMillisUnsafe(2000)
          val neighborsPerGroup: Int      = 3
        }
        (brokerInfo, config)
      }

      (clique, infos)
    }
  }

  it should "simulate large network" in new ActorFixture with SimulationFixture { self =>
    val groups = 4

    val networkConfig = new NetworkConfig { val networkType = NetworkType.Testnet }

    val cliqueNum = 8
    val cliques   = AVector.fill(cliqueNum)(generateClique())

    val servers = cliques.flatMapWithIndex { case ((clique, infos), index) =>
      infos.map { case (brokerInfo, config) =>
        val misbehaviorManager: ActorRefT[MisbehaviorManager.Command] =
          ActorRefT.build(
            system,
            MisbehaviorManager.props(ALF.BanDuration, ALF.PenaltyForgivness, ALF.PenaltyFrequency)
          )
        val server = {
          if (index equals 0) {
            TestActorRef[DiscoveryServer](
              DiscoveryServer
                .props(brokerInfo.address, misbehaviorManager)(config, config, networkConfig)
            )
          } else {
            val bootstrapAddress = cliques(index / 2)._2.sample()._1.address
            TestActorRef[DiscoveryServer](
              DiscoveryServer
                .props(brokerInfo.address, misbehaviorManager, bootstrapAddress)(
                  config,
                  config,
                  networkConfig
                )
            )
          }
        }
        server ! DiscoveryServer.SendCliqueInfo(clique)

        server
      }
    }

    eventually {
      servers.foreach { server =>
        val probe = TestProbe()
        server.tell(DiscoveryServer.GetNeighborPeers, probe.ref)

        probe.expectMsgPF() { case DiscoveryServer.NeighborPeers(peers) =>
          (peers.sumBy(_.groupNumPerBroker) >= 3 * groups) is true
        }
      }
    }
  }

  def generateCliqueInfo(master: InetSocketAddress, groupConfig: GroupConfig): CliqueInfo = {
    val (priKey, pubKey) = SignatureSchema.secureGeneratePriPub()
    val newInfo = CliqueInfo.unsafe(
      CliqueId(pubKey),
      AVector.tabulate(groupConfig.groups)(i =>
        Option(new InetSocketAddress(master.getAddress, master.getPort + i))
      ),
      AVector.tabulate(groupConfig.groups)(i =>
        new InetSocketAddress(master.getAddress, master.getPort + i)
      ),
      1,
      priKey
    )
    CliqueInfo.validate(newInfo)(groupConfig).isRight is true
    newInfo.coordinatorAddress is master
    newInfo
  }

  it should "discovery each other for two cliques" in new Fixture {

    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)
    server1 ! DiscoveryServer.SendCliqueInfo(cliqueInfo1)

    eventually {
      val probe0 = TestProbe()
      server0.tell(DiscoveryServer.GetNeighborPeers, probe0.ref)
      val probe1 = TestProbe()
      server1.tell(DiscoveryServer.GetNeighborPeers, probe1.ref)

      probe0.expectMsgPF(probeTimeout) { case DiscoveryServer.NeighborPeers(peers) =>
        peers.length is groups + 1 // self clique peers + server1
        peers.filter(_.cliqueId equals cliqueInfo0.id).toSet is cliqueInfo0.interBrokers.get.toSet
        peers.filterNot(_.cliqueId equals cliqueInfo0.id) is AVector(
          cliqueInfo1.interBrokers.get.head
        )
      }
      probe1.expectMsgPF(probeTimeout) { case DiscoveryServer.NeighborPeers(peers) =>
        peers.length is groups + 1 // 3 self clique peers + server0
        peers.filter(_.cliqueId equals cliqueInfo1.id).toSet is cliqueInfo1.interBrokers.get.toSet
        peers.filterNot(_.cliqueId equals cliqueInfo1.id) is AVector(
          cliqueInfo0.interBrokers.get.head
        )
      }
    }
  }

  it should "refuse to discover a banned clique" in new Fixture {

    misbehaviorManager0.ref ! MisbehaviorManager.InvalidMessage(address1)

    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)
    server1 ! DiscoveryServer.SendCliqueInfo(cliqueInfo1)

    eventually {
      val probe0 = TestProbe()
      server0.tell(DiscoveryServer.GetNeighborPeers, probe0.ref)
      val probe1 = TestProbe()
      server1.tell(DiscoveryServer.GetNeighborPeers, probe1.ref)

      probe0.expectMsgPF(probeTimeout) { case DiscoveryServer.NeighborPeers(peers) =>
        peers.length is groups // self clique peers
      }
      probe1.expectMsgPF(probeTimeout) { case DiscoveryServer.NeighborPeers(peers) =>
        peers.length is groups + 1 // self clique peers + server0
        peers.filter(_.cliqueId equals cliqueInfo1.id).toSet is cliqueInfo1.interBrokers.get.toSet
        peers.filterNot(_.cliqueId equals cliqueInfo1.id) is AVector(
          cliqueInfo0.interBrokers.get.head
        )
      }
    }
  }

  it should "refuse to ping a peer with unmatched peer info" in new Fixture {
    implicit val askTimeout: Timeout = Timeout(Duration.ofSecondsUnsafe(10).asScala)

    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)

    misbehaviorManager0
      .ask(MisbehaviorManager.GetPeers)
      .mapTo[MisbehaviorManager.Peers]
      .futureValue is MisbehaviorManager.Peers(AVector.empty)

    val message = DiscoveryMessage.from(
      cliqueInfo1.id,
      DiscoveryMessage.Ping(
        Some(
          BrokerInfo.from(Generators.socketAddressGen.sample.get, cliqueInfo1.selfInterBrokerInfo)
        )
      )
    )
    server0 ! UdpServer.Received(
      DiscoveryMessage.serialize(message, networkConfig.networkType, discoveryPrivateKey),
      address1
    )

    eventually {
      misbehaviorManager0
        .ask(MisbehaviorManager.GetPeers)
        .mapTo[MisbehaviorManager.Peers]
        .futureValue
        .peers
        .head
        .peer is address1.getAddress
    }
  }

  trait Fixture extends BrokerConfigFixture.Default with ActorFixture {
    implicit val patienceConfig =
      PatienceConfig(timeout = Span(20, Seconds), interval = Span(1, Seconds))

    override val groups = Gen.choose(2, 10).sample.get

    val (discoveryPrivateKey, discoveryPublicKey) = SignatureSchema.generatePriPub()

    val probeTimeout = Duration.ofSecondsUnsafe(5).asScala

    val port0               = generatePort()
    val (address0, config0) = createConfig(groups, port0, 2)
    val cliqueInfo0         = generateCliqueInfo(address0, config0)
    val port1               = generatePort()
    val (address1, config1) = createConfig(groups, port1, 2)
    val cliqueInfo1         = generateCliqueInfo(address1, config1)
    val networkConfig       = new NetworkConfig { val networkType = NetworkType.Testnet }
    val misbehaviorManager0: ActorRefT[MisbehaviorManager.Command] =
      ActorRefT.build(
        system,
        MisbehaviorManager.props(ALF.BanDuration, ALF.PenaltyForgivness, ALF.PenaltyFrequency)
      )
    val misbehaviorManager1: ActorRefT[MisbehaviorManager.Command] =
      ActorRefT.build(
        system,
        MisbehaviorManager.props(ALF.BanDuration, ALF.PenaltyForgivness, ALF.PenaltyFrequency)
      )

    val server0 =
      system.actorOf(
        DiscoveryServer.props(address0, misbehaviorManager0)(brokerConfig, config0, networkConfig)
      )
    val server1 =
      system.actorOf(
        DiscoveryServer
          .props(address1, misbehaviorManager1, address0)(brokerConfig, config1, networkConfig)
      )

    val misbehaviorProbe = TestProbe()
    system.eventStream.subscribe(misbehaviorProbe.ref, classOf[MisbehaviorManager.InvalidMessage])
  }
}

object DiscoveryServerSpec {

  def createConfig(
      groupSize: Int,
      port: Int,
      _peersPerGroup: Int,
      _scanFrequency: Duration = Duration.unsafe(200),
      _expireDuration: Duration = Duration.ofHoursUnsafe(1)
  ): (InetSocketAddress, DiscoveryConfig with BrokerConfig) = {
    val publicAddress: InetSocketAddress = new InetSocketAddress("127.0.0.1", port)
    val discoveryConfig = new DiscoveryConfig with BrokerConfig {

      val peersPerGroup: Int          = _peersPerGroup
      val scanMaxPerGroup: Int        = _peersPerGroup
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
