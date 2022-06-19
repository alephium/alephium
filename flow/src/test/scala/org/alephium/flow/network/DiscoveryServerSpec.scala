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

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe}
import org.scalacheck.Gen
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}

import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.network.DiscoveryServer.NeighborPeers
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.setting.Platform
import org.alephium.protocol._
import org.alephium.protocol.config._
import org.alephium.protocol.model._
import org.alephium.util._

class DiscoveryServerSpec
    extends AlephiumActorSpec
    with ScalaFutures
    with Eventually
    with SocketUtil
    with IntegrationPatience {
  import DiscoveryServerSpec._

  def buildMisbehaviorManager(system: ActorSystem): ActorRefT[MisbehaviorManager.Command] = {
    ActorRefT.build(
      system,
      MisbehaviorManager.props(
        Duration.ofDaysUnsafe(1),
        Duration.ofHoursUnsafe(1),
        Duration.ofMinutesUnsafe(1)
      )
    )
  }

  trait SimulationFixture { fixture =>

    def groups: Int

    def generateClique(): (CliqueInfo, AVector[(BrokerInfo, BrokerConfig with DiscoveryConfig)]) = {
      val brokerNum = {
        val candidates = (1 to groups).filter(groups % _ equals 0)
        candidates(Random.nextInt(candidates.size))
      }
      val groupNumPerBroker = groups / brokerNum
      val addresses = AVector.fill(brokerNum)(new InetSocketAddress("127.0.0.1", generatePort()))
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

          val scanFrequency: Duration          = Duration.ofMillisUnsafe(2000)
          val scanFastFrequency: Duration      = Duration.ofMillisUnsafe(2000)
          val fastScanPeriod: Duration         = Duration.ofMinutesUnsafe(1)
          val initialDiscoveryPeriod: Duration = Duration.ofSecondsUnsafe(2)
          val neighborsPerGroup: Int           = 20
          val maxCliqueFromSameIp: Int         = 100
        }
        (brokerInfo, config)
      }

      (clique, infos)
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

    withPeers(server0) { peers =>
      peers.length is groups + 1 // self clique peers + server1
      peers.filter(_.cliqueId equals cliqueInfo0.id).toSet is cliqueInfo0.interBrokers.get.toSet
      peers.filterNot(_.cliqueId equals cliqueInfo0.id) is AVector(
        cliqueInfo1.interBrokers.get.head
      )
    }
    withPeers(server1) { peers =>
      peers.length is groups + 1 // 3 self clique peers + server0
      peers.filter(_.cliqueId equals cliqueInfo1.id).toSet is cliqueInfo1.interBrokers.get.toSet
      peers.filterNot(_.cliqueId equals cliqueInfo1.id) is AVector(
        cliqueInfo0.interBrokers.get.head
      )
    }

    storages.brokerStorage.exists(cliqueInfo0.selfInterBrokerInfo.peerId) isE true
    storages.brokerStorage.exists(cliqueInfo1.selfInterBrokerInfo.peerId) isE true
  }

  it should "load brokers from storage at startup" in new Fixture {
    val peer = cliqueInfo1.selfBrokerInfo.get
    storages.brokerStorage.addBroker(peer) isE ()
    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)
    withPeers(server0) { peers =>
      peers.length is groups + 1
    }
  }

  it should "refuse to discover a banned clique" in new Fixture {

    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)
    server1 ! DiscoveryServer.SendCliqueInfo(cliqueInfo1)

    withPeers(server0) { peers =>
      peers.length is groups + 1 // self clique peers + server1
      peers.map(_.address).contains(address1) is true
    }
    withPeers(server1) { peers =>
      peers.length is groups + 1 // self clique peers + server0
      peers.map(_.address).contains(address0) is true
    }

    misbehaviorManager0.ref ! MisbehaviorManager.InvalidFlowData(address1)

    withPeers(server0) { peers =>
      // self clique peers might get removed as server0 and server1 have the same host address
      (peers.length <= groups) is true
    }
    withPeers(server1) { peers =>
      peers.length is groups + 1 // self clique peers + server0
      peers.filter(_.cliqueId equals cliqueInfo1.id).toSet is cliqueInfo1.interBrokers.get.toSet
      peers.filterNot(_.cliqueId equals cliqueInfo1.id) is AVector(
        cliqueInfo0.interBrokers.get.head
      )
    }
  }

  it should "stash messages before receiving cliqueInfo" in new Fixture {
    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)
    val misbehaviorManager = buildMisbehaviorManager(system)
    lazy val server2 =
      newTestActorRef[DiscoveryServer](
        DiscoveryServer
          .props(address1, misbehaviorManager, storages.brokerStorage)(
            brokerConfig,
            config1,
            networkConfig
          )
      )

    server2 ! DiscoveryServer.PeerConfirmed(cliqueInfo0.selfBrokerInfo.get)

    withPeers(server0) { peers =>
      peers.length is groups
      peers.map(_.address).contains(address1) is false
    }
    server2.underlyingActor.getPeersNum is 0

    server2 ! DiscoveryServer.SendCliqueInfo(cliqueInfo1)

    withPeers(server0) { peers =>
      peers.length is groups + 1 // self clique peers + server1
      peers.filter(_.cliqueId equals cliqueInfo0.id).toSet is cliqueInfo0.interBrokers.get.toSet
      peers.filterNot(_.cliqueId equals cliqueInfo0.id) is AVector(
        cliqueInfo1.interBrokers.get.head
      )
    }
    withPeers(server2) { peers =>
      peers.length is groups + 1 // 3 self clique peers + server0
      peers.filter(_.cliqueId equals cliqueInfo1.id).toSet is cliqueInfo1.interBrokers.get.toSet
      peers.filterNot(_.cliqueId equals cliqueInfo1.id) is AVector(
        cliqueInfo0.interBrokers.get.head
      )
    }
  }

  it should "publish neighbors" in new Fixture {
    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[NeighborPeers])
    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)
    probe.expectMsg(Duration.ofSecondsUnsafe(3).asScala, NeighborPeers(AVector.empty))
  }

  trait UnreachableFixture extends Fixture {
    val misbehaviorManager = buildMisbehaviorManager(system)
    lazy val server2 =
      newTestActorRef[DiscoveryServer](
        DiscoveryServer
          .props(address1, misbehaviorManager, storages.brokerStorage)(
            brokerConfig,
            config1,
            networkConfig
          )
      )
    server2 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)
  }

  it should "mark address as unreachable" in new UnreachableFixture {
    val remote = Generators.socketAddressGen.sample.get
    server2 ! DiscoveryServer.Unreachable(remote)
    eventually {
      server2.underlyingActor.mightReachable(remote) is false
    }
    server2 ! DiscoveryServer.Unban(AVector(remote.getAddress))
    eventually {
      server2.underlyingActor.mightReachable(remote) is true
    }
  }

  it should "ban addresses" in new UnreachableFixture {
    val remote = Generators.socketAddressGen.sample.get.getAddress
    eventually {
      system.eventStream.publish(MisbehaviorManager.PeerBanned(remote))
      val bannedUntil = server2.underlyingActor.unreachables.get(remote).get
      (bannedUntil > TimeStamp.now() + Duration.ofHoursUnsafe(12)) is true
    }
  }

  it should "reply to GetMorePeers only when initial discovery is done" in new Fixture {
    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)
    server0 ! DiscoveryServer.GetMorePeers(brokerConfig)
    expectNoMsg()
    server0.underlyingActor.postInitialDiscovery()
    server0 ! DiscoveryServer.GetMorePeers(brokerConfig)
    expectMsgType[DiscoveryServer.NeighborPeers]
  }

  it should "simulate large network" in new SimulationFixture with NetworkConfigFixture.Default {
    self =>
    val groups = 4

    val groupConfig = new GroupConfig {
      def groups: Int = self.groups
    }
    val cliqueNum = 16
    val cliques   = AVector.fill(cliqueNum)(generateClique())
    val servers = cliques.flatMapWithIndex { case ((clique, infos), index) =>
      infos.map { case (brokerInfo, config) =>
        val storages           = StoragesFixture.buildStorages(Platform.getRootPath())(groupConfig)
        val misbehaviorManager = buildMisbehaviorManager(system)
        val server = {
          if (index equals 0) {
            TestActorRef[DiscoveryServer](
              DiscoveryServer
                .props(brokerInfo.address, misbehaviorManager, storages.brokerStorage)(
                  config,
                  config,
                  networkConfig
                )
            )
          } else {
            val bootstrapAddress = cliques(index / 2)._2.sample()._1.address
            TestActorRef[DiscoveryServer](
              DiscoveryServer
                .props(
                  brokerInfo.address,
                  misbehaviorManager,
                  storages.brokerStorage,
                  bootstrapAddress
                )(
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
    servers.foreach { server =>
      withPeers(server) { peers =>
        (peers.sumBy(peer => groups / peer.brokerNum) >= 5 * groups) is true
      }
    }
  }

  trait Fixture extends BrokerConfigFixture.Default with NetworkConfigFixture.Default {

    override val groups = Gen.choose(2, 10).sample.get

    val port0               = generatePort()
    val storages            = StoragesFixture.buildStorages(Platform.getRootPath())
    val (address0, config0) = createConfig(groups, port0, 2)
    val cliqueInfo0         = generateCliqueInfo(address0, config0)
    val port1               = generatePort()
    val (address1, config1) = createConfig(groups, port1, 2)
    val cliqueInfo1         = generateCliqueInfo(address1, config1)
    val misbehaviorManager0 = buildMisbehaviorManager(system)
    val misbehaviorManager1 = buildMisbehaviorManager(system)

    lazy val server0 =
      TestActorRef[DiscoveryServer](
        DiscoveryServer.props(address0, misbehaviorManager0, storages.brokerStorage)(
          brokerConfig,
          config0,
          networkConfig
        )
      )(system)

    val system1 = createSystem()
    lazy val server1 =
      TestActorRef[DiscoveryServer](
        DiscoveryServer
          .props(address1, misbehaviorManager1, storages.brokerStorage, address0)(
            brokerConfig,
            config1,
            networkConfig
          )
      )(system1)

    val misbehaviorProbe = TestProbe()
    system.eventStream.subscribe(misbehaviorProbe.ref, classOf[MisbehaviorManager.InvalidFlowData])
  }

  def withPeers[T](
      server0: ActorRefT[DiscoveryServer.Command]
  )(check: AVector[BrokerInfo] => T): Unit = {
    eventually {
      val probe0 = TestProbe()
      server0.tell(DiscoveryServer.GetNeighborPeers, probe0.ref)
      probe0.expectMsgPF() { case DiscoveryServer.NeighborPeers(peers) =>
        check(peers)
      }
      ()
    }
  }
}

object DiscoveryServerSpec {

  def createConfig(
      groupSize: Int,
      port: Int,
      _peersPerGroup: Int,
      _scanFrequency: Duration = Duration.unsafe(200),
      _expireDuration: Duration = Duration.ofHoursUnsafe(1),
      _peersTimeout: Duration = Duration.ofSecondsUnsafe(5),
      _scanFastPeriod: Duration = Duration.ofMinutesUnsafe(1)
  ): (InetSocketAddress, DiscoveryConfig with BrokerConfig) = {
    val publicAddress: InetSocketAddress = new InetSocketAddress("127.0.0.1", port)
    val discoveryConfig = new DiscoveryConfig with BrokerConfig {

      val scanFrequency: Duration          = _scanFrequency
      val scanFastFrequency: Duration      = _scanFrequency
      val fastScanPeriod: Duration         = _scanFastPeriod
      val initialDiscoveryPeriod: Duration = Duration.ofSecondsUnsafe(2)
      val neighborsPerGroup: Int           = _peersPerGroup
      val maxCliqueFromSameIp: Int         = 2

      override lazy val expireDuration: Duration = _expireDuration
      override val peersTimeout: Duration        = _peersTimeout

      val groups: Int    = groupSize
      val brokerNum: Int = groupSize
      val brokerId: Int  = Random.nextInt(brokerNum)
    }
    publicAddress -> discoveryConfig
  }
}
