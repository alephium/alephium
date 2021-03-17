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

package org.alephium.app

import java.net.InetSocketAddress

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import akka.actor.{Actor, ActorRef}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.api.CirceUtils._
import org.alephium.api.model._
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.message.{Message, Payload, Pong}
import org.alephium.protocol.model.{BrokerInfo, NetworkType}
import org.alephium.util._

class Injected[T](injection: ByteString => ByteString, ref: ActorRef) extends ActorRefT[T](ref) {
  override def !(message: T)(implicit sender: ActorRef = Actor.noSender): Unit = {
    message match {
      case Tcp.Write(data, ack) => ref.!(Tcp.Write(injection(data), ack))(sender)
      case _                    => ref.!(message)(sender)
    }
  }
}

object Injected {
  def apply[T](injection: ByteString => ByteString, ref: ActorRef): Injected[T] =
    new Injected(injection, ref)

  def noModification[T](ref: ActorRef): Injected[T] = apply[T](identity, ref)

  def payload[T](injection: PartialFunction[Payload, Payload], ref: ActorRef)(
      implicit groupConfig: GroupConfig,
      networkConfig: NetworkConfig): Injected[T] = {
    val injectionData: ByteString => ByteString = data => {
      val payload = Message.deserialize(data, networkConfig.networkType).toOption.get.payload
      if (injection.isDefinedAt(payload)) {
        val injected     = injection.apply(payload)
        val injectedData = Message.serialize(injected, networkConfig.networkType)
        injectedData
      } else {
        data
      }
    }

    new Injected(injectionData, ref)
  }
}

class InterCliqueSyncTest extends AlephiumSpec {
  it should "boot and sync two cliques of 2 nodes" in new Fixture("2-cliques-of-2-nodes") {
    test(2, 2)
  }

  it should "boot and sync two cliques of 1 and 2 nodes" in new Fixture(
    "clique-1-node-clique-2-node") {
    test(1, 2)
  }

  it should "boot and sync two cliques of 2 and 1 nodes" in new Fixture(
    "clique-2-node-clique-1-node") {
    test(2, 1)
  }

  it should "support injection" in new Fixture("clique-2-node-clique-1-node-injection") {
    test(2, 1, connectionBuild = Injected.noModification)
  }

  class Fixture(name: String) extends TestFixture(name) {

    // scalastyle:off method.length
    def test(nbOfNodesClique1: Int,
             nbOfNodesClique2: Int,
             connectionBuild: ActorRef => ActorRefT[Tcp.Command] = ActorRefT.apply) = {
      val fromTs            = TimeStamp.now()
      val clique1           = bootClique(nbOfNodes = nbOfNodesClique1, connectionBuild = connectionBuild)
      val masterPortClique1 = clique1.head.config.network.coordinatorAddress.getPort

      Future.sequence(clique1.map(_.start())).futureValue
      startWS(wsPort(masterPortClique1))

      clique1.foreach { server =>
        request[Boolean](startMining, restPort(server.config.network.bindAddress.getPort)) is true
      }

      blockNotifyProbe.receiveN(10, Duration.ofMinutesUnsafe(2).asScala)

      clique1.foreach { server =>
        request[Boolean](stopMining, restPort(server.config.network.bindAddress.getPort)) is true
      }

      val selfClique1 = request[SelfClique](getSelfClique, restPort(masterPortClique1))

      val clique2 =
        bootClique(nbOfNodes       = nbOfNodesClique2,
                   bootstrap       = Some(new InetSocketAddress("localhost", masterPortClique1)),
                   connectionBuild = connectionBuild)
      val masterPortClique2 = clique2.head.config.network.coordinatorAddress.getPort

      Future.sequence(clique2.map(_.start())).futureValue

      clique2.foreach { server =>
        eventually {
          val interCliquePeers =
            request[Seq[InterCliquePeerInfo]](
              getInterCliquePeerInfo,
              restPort(server.config.network.bindAddress.getPort)).head
          interCliquePeers.cliqueId is selfClique1.cliqueId
          interCliquePeers.isSynced is true

          val discoveredNeighbors =
            request[Seq[BrokerInfo]](getDiscoveredNeighbors,
                                     restPort(server.config.network.bindAddress.getPort))
          discoveredNeighbors.length is (nbOfNodesClique1 + nbOfNodesClique2)
        }
      }

      val toTs = TimeStamp.now()
      eventually {
        request[FetchResponse](blockflowFetch(fromTs, toTs), restPort(masterPortClique1)).blocks.toSet is
          request[FetchResponse](blockflowFetch(fromTs, toTs), restPort(masterPortClique2)).blocks.toSet
      }

      clique1.foreach(_.stop().futureValue is ())
      clique2.foreach(_.stop().futureValue is ())
    }
    // scalastyle:on method.length
  }

  it should "ban node if not same network type" in new TestFixture("2-nodes") {
    val server0 = bootClique(1).head
    server0.start().futureValue is ()

    val currentNetworkType = config.network.networkType
    currentNetworkType isnot NetworkType.Mainnet
    val modifier: ByteString => ByteString = { data =>
      val message = Message.deserialize(data, currentNetworkType).toOption.get
      Message.serialize(message.payload, NetworkType.Mainnet)
    }
    val server1 =
      bootClique(
        1,
        bootstrap = Some(
          new InetSocketAddress("localhost", server0.config.network.coordinatorAddress.getPort)),
        connectionBuild = Injected.apply(modifier, _)).head
    server1.start().futureValue is ()

    eventually {
      val misbehaviors1 =
        request[AVector[PeerMisbehavior]](getMisbehaviors,
                                          restPort(server0.config.network.bindAddress.getPort))
      misbehaviors1.map(_.status).exists {
        case PeerStatus.Banned(_) => true
        case _                    => false
      } is true
    }

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }

  it should "ban node if send invalid pong" in new TestFixture("2-nodes") {
    val injection: PartialFunction[Payload, Payload] = {
      case Pong(x) => Pong(x + 1)
    }

    val server0 = bootClique(1).head
    server0.start().futureValue is ()

    val server1 = bootClique(
      1,
      bootstrap =
        Some(new InetSocketAddress("localhost", server0.config.network.coordinatorAddress.getPort)),
      connectionBuild = Injected.payload(injection, _)).head

    server1.start().futureValue is ()

    eventually {
      val misbehaviors0 =
        request[AVector[PeerMisbehavior]](getMisbehaviors,
                                          restPort(server0.config.network.bindAddress.getPort))

      misbehaviors0.map(_.status).exists {
        case PeerStatus.Banned(_) => true
        case _                    => false
      } is true
    }

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }

  it should "ban node if spamming" in new TestFixture("2-nodes") {
    val injectionData: ByteString => ByteString = { _ =>
      ByteString.fromArray(Array.fill[Byte](51)(-1))
    }

    val server0 = bootClique(1).head
    server0.start().futureValue is ()

    val server1 = bootClique(
      1,
      bootstrap =
        Some(new InetSocketAddress("localhost", server0.config.network.coordinatorAddress.getPort)),
      connectionBuild = Injected(injectionData, _)).head

    server1.start().futureValue is ()

    eventually {
      val misbehaviors0 =
        request[AVector[PeerMisbehavior]](getMisbehaviors,
                                          restPort(server0.config.network.bindAddress.getPort))
      misbehaviors0.map(_.status).exists {
        case PeerStatus.Banned(_) => true
        case _                    => false
      } is true
    }

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }
}
