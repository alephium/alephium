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

import akka.actor.{Actor, ActorRef}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.api.model._
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.protocol.WireVersion
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.message.{Header, Hello, Message, Payload, Pong, RequestId}
import org.alephium.protocol.model.{BrokerInfo, NetworkId}
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

  def message[T](
      injection: PartialFunction[Message, Message],
      ref: ActorRef
  )(implicit groupConfig: GroupConfig, networkConfig: NetworkConfig): Injected[T] = {
    val injectionData: ByteString => ByteString = data => {
      val message = Message.deserialize(data).toOption.get
      if (injection.isDefinedAt(message)) {
        val injected     = injection.apply(message)
        val injectedData = Message.serialize(injected)
        injectedData
      } else {
        data
      }
    }

    new Injected(injectionData, ref)
  }

  def payload[T](
      injection: PartialFunction[Payload, Payload],
      ref: ActorRef
  )(implicit groupConfig: GroupConfig, networkConfig: NetworkConfig): Injected[T] = {
    val newInjection: PartialFunction[Message, Message] = {
      case Message(header, payload) if injection.isDefinedAt(payload) =>
        Message(header, injection(payload))
    }
    message(newInjection, ref)
  }

}

class InterCliqueSyncTest extends AlephiumActorSpec {
  it should "boot and sync two cliques of 2 nodes" in new Fixture {
    test(2, 2)
  }

  it should "boot and sync two cliques of 1 and 2 nodes" in new Fixture {
    test(1, 2)
  }

  it should "boot and sync two cliques of 2 and 1 nodes" in new Fixture {
    test(2, 1)
  }

  it should "support injection" in new Fixture {
    test(
      2,
      1,
      clique1ConnectionBuild = Injected.noModification,
      clique2ConnectionBuild = Injected.noModification
    )
  }

  class Fixture extends CliqueFixture {

    // scalastyle:off method.length
    def test(
        nbOfNodesClique1: Int,
        nbOfNodesClique2: Int,
        clique1ConnectionBuild: ActorRef => ActorRefT[Tcp.Command] = ActorRefT.apply,
        clique2ConnectionBuild: ActorRef => ActorRefT[Tcp.Command] = ActorRefT.apply
    ) = {
      val fromTs = TimeStamp.now()
      val clique1 =
        bootClique(nbOfNodes = nbOfNodesClique1, connectionBuild = clique1ConnectionBuild)
      val masterPortClique1 = clique1.masterTcpPort

      clique1.start()
      clique1.startWs()
      val selfClique1 = clique1.selfClique()

      clique1.startMining()
      blockNotifyProbe.receiveN(10, Duration.ofMinutesUnsafe(2).asScala)
      clique1.stopMining()

      val clique2 =
        bootClique(
          nbOfNodes = nbOfNodesClique2,
          bootstrap = Some(new InetSocketAddress("127.0.0.1", masterPortClique1)),
          connectionBuild = clique2ConnectionBuild
        )
      val masterPortClique2 = clique2.masterTcpPort

      clique2.start()
      val selfClique2 = clique2.selfClique()

      clique2.servers.foreach { server =>
        eventually {
          val interCliquePeers =
            request[Seq[InterCliquePeerInfo]](
              getInterCliquePeerInfo,
              restPort(server.config.network.bindAddress.getPort)
            ).head
          interCliquePeers.cliqueId is selfClique1.cliqueId
          interCliquePeers.isSynced is true

          val discoveredNeighbors =
            request[Seq[BrokerInfo]](
              getDiscoveredNeighbors,
              restPort(server.config.network.bindAddress.getPort)
            )
          discoveredNeighbors.length is (nbOfNodesClique1 + nbOfNodesClique2)
        }
      }

      val toTs = TimeStamp.now()
      eventually {
        val blockflow1 = selfClique1.nodes.flatMap { peer =>
          request[FetchResponse](
            blockflowFetch(fromTs, toTs),
            peer.restPort
          ).blocks
        }
        val blockflow2 = selfClique2.nodes.flatMap { peer =>
          request[FetchResponse](
            blockflowFetch(fromTs, toTs),
            peer.restPort
          ).blocks
        }

        blockflow1.length is blockflow2.length

        blockflow1.map(_.toSet).toSet is blockflow2.map(_.toSet).toSet
      }

      eventually(request[SelfClique](getSelfClique, restPort(masterPortClique2)).synced is true)

      clique1.stop()
      clique2.stop()
    }
    // scalastyle:on method.length
  }

  it should "punish peer if not same chain id" in new CliqueFixture {
    val server0 = bootClique(1).servers.head
    server0.start().futureValue is ()

    val currentNetworkId = config.network.networkId
    currentNetworkId isnot NetworkId.AlephiumMainNet
    val modifier: ByteString => ByteString = { data =>
      val message = Message.deserialize(data).rightValue
      Message.serialize(message.payload)(new NetworkConfig {
        val networkId: NetworkId              = NetworkId.AlephiumMainNet
        val noPreMineProof: ByteString        = ByteString.empty
        val lemanHardForkTimestamp: TimeStamp = TimeStamp.now()
      })
    }
    val server1 =
      bootClique(
        1,
        bootstrap = Some(
          new InetSocketAddress("127.0.0.1", server0.config.network.coordinatorAddress.getPort)
        ),
        connectionBuild = Injected.apply(modifier, _)
      ).servers.head
    server1.start().futureValue is ()

    val server1Address = server1.config.network.bindAddress.getAddress
    eventually {
      haveBeenPunished(server0, server1Address, MisbehaviorManager.Warning.penalty)
      existUnreachable(server1) is true
    }

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }

  it should "ban node if send invalid pong" in new CliqueFixture {
    val injection: PartialFunction[Payload, Payload] = { case Pong(requestId) =>
      val updatedRequestId = if (requestId.value.addUnsafe(U32.One) != U32.Zero) {
        RequestId(requestId.value.addUnsafe(U32.One))
      } else {
        RequestId(requestId.value.addUnsafe(U32.Two))
      }

      Pong(updatedRequestId)
    }

    val server0 = bootClique(
      1,
      configOverrides = Map(
        ("alephium.network.ping-frequency", "1 seconds"),
        ("alephium.network.penalty-frequency", "1 seconds")
      )
    ).servers.head
    server0.start().futureValue is ()

    val server1 = bootClique(
      1,
      bootstrap =
        Some(new InetSocketAddress("127.0.0.1", server0.config.network.coordinatorAddress.getPort)),
      connectionBuild = Injected.payload(injection, _)
    ).servers.head

    server1.start().futureValue is ()

    eventually {
      existBannedPeers(server0) is true
      existUnreachable(server1) is true
    }

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }

  it should "punish peer if spamming" in new CliqueFixture {
    val injectionData: ByteString => ByteString = { _ =>
      ByteString.fromArray(Array.fill[Byte](51)(-1))
    }

    val server0 = bootClique(1).servers.head
    server0.start().futureValue is ()

    val server1 = bootClique(
      1,
      bootstrap =
        Some(new InetSocketAddress("127.0.0.1", server0.config.network.coordinatorAddress.getPort)),
      connectionBuild = Injected(injectionData, _)
    ).servers.head

    server1.start().futureValue is ()

    val server1Address = server1.config.network.bindAddress.getAddress
    eventually {
      haveBeenPunished(server0, server1Address, MisbehaviorManager.Warning.penalty)
      existUnreachable(server1) is true
    }

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }

  it should "punish peer if version is not compatible" in new CliqueFixture {
    val dummyVersion = WireVersion(WireVersion.currentWireVersion.value + 1)
    val injection: PartialFunction[Message, Message] = { case Message(_, payload: Hello) =>
      Message(Header(dummyVersion), payload)
    }

    val server0 = bootClique(1).servers.head
    server0.start().futureValue is ()

    val server1 = bootClique(
      1,
      bootstrap =
        Some(new InetSocketAddress("127.0.0.1", server0.config.network.coordinatorAddress.getPort)),
      connectionBuild = Injected.message(injection, _)
    ).servers.head

    server1.start().futureValue is ()

    val server1Address = server1.config.network.bindAddress.getAddress
    eventually {
      haveBeenPunished(server0, server1Address, MisbehaviorManager.Warning.penalty)
      existUnreachable(server1) is true
    }

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }
}
