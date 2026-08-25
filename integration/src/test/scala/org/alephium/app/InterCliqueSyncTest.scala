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
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.{Actor, ActorRef}
import org.apache.pekko.io.Tcp
import org.apache.pekko.util.ByteString

import org.alephium.api.model._
import org.alephium.flow.mining.Miner
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.protocol.WireVersion
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.message._
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
  private def startCliques(cliques: Seq[CliqueFixture#Clique]): Unit = {
    implicit val ec: ExecutionContext = system.dispatcher
    discard(Future.traverse(cliques)(_.startWithoutCheckSyncStateAsync()).futureValue)
  }

  private def stopCliques(cliques: Seq[CliqueFixture#Clique]): Unit = {
    implicit val ec: ExecutionContext = system.dispatcher
    discard(Future.traverse(cliques)(_.stopAsync()).futureValue)
  }

  it should "boot and sync two cliques of 2 nodes using protocol v2" in new Fixture {
    test(2, 2)
  }

  it should "boot and sync two cliques of 1 and 2 nodes using protocol v2" in new Fixture {
    test(1, 2)
  }

  it should "boot and sync two cliques of 2 and 1 nodes using protocol v2" in new Fixture {
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
        bootClique(
          nbOfNodes = nbOfNodesClique1,
          connectionBuild = clique1ConnectionBuild
        )
      val masterPortClique1 = clique1.masterTcpPort

      clique1.start()
      clique1.startWsAndWaitConnection()
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

      eventually {
        implicit val ec: ExecutionContext = system.dispatcher
        val peerStates = Future
          .traverse(clique2.servers.toSeq) { server =>
            val serverRestPort = restPort(server.config.network.bindAddress.getPort)
            for {
              interCliquePeers <-
                requestAsync[Seq[InterCliquePeerInfo]](getInterCliquePeerInfo, serverRestPort)
              discoveredNeighbors <- requestAsync[Seq[BrokerInfo]](
                getDiscoveredNeighbors,
                serverRestPort
              )
            } yield (interCliquePeers.head, discoveredNeighbors)
          }
          .futureValue

        peerStates.foreach { case (interCliquePeers, discoveredNeighbors) =>
          interCliquePeers.cliqueId is selfClique1.cliqueId
          interCliquePeers.isSynced is true
          discoveredNeighbors.length is (nbOfNodesClique1 + nbOfNodesClique2)
        }
      }

      val toTs = TimeStamp.now()
      eventually {
        implicit val ec: ExecutionContext = system.dispatcher
        def fetchBlocks(selfClique: SelfClique) = {
          Future
            .traverse(selfClique.nodes.toSeq) { peer =>
              requestAsync[BlocksPerTimeStampRange](blockflowFetch(fromTs, toTs), peer.restPort)
            }
            .map(_.flatMap(_.blocks.toSeq))
        }

        val (blockflow1, blockflow2) =
          fetchBlocks(selfClique1).zip(fetchBlocks(selfClique2)).futureValue

        blockflow1.length is blockflow2.length

        blockflow1.map(_.toSet).toSet is blockflow2.map(_.toSet).toSet
      }

      eventually(request[SelfClique](getSelfClique, restPort(masterPortClique2)).synced is true)

      stopCliques(Seq(clique1, clique2))
    }
    // scalastyle:on method.length
  }

  it should "sync four p2p v2 cliques" in new P2PV2CliquesSyncFixture {
    test()
  }

  trait SyncFixtureBase extends CliqueFixture {
    private def fetchBlocks(
        clique: Clique,
        fromTs: TimeStamp,
        toTs: TimeStamp
    ): Future[Seq[AVector[BlockEntry]]] = {
      implicit val ec: ExecutionContext = system.dispatcher
      Future
        .traverse(clique.servers.toSeq) { server =>
          requestAsync[BlocksPerTimeStampRange](blockflowFetch(fromTs, toTs), server.restPort)
        }
        .map(_.flatMap(_.blocks.toSeq))
    }

    private def checkBlocks(
        blockflow1: Seq[AVector[BlockEntry]],
        blockflow2: Seq[AVector[BlockEntry]]
    ) = {
      blockflow1.length is blockflow2.length
      blockflow1.map(_.toSet).toSet is blockflow2.map(_.toSet).toSet
    }

    def awaitSyncedWithSameBlocks(
        bootstrapClique: Clique,
        cliques: Seq[Clique],
        fromTs: TimeStamp,
        toTs: TimeStamp
    ): Unit = eventually {
      implicit val ec: ExecutionContext = system.dispatcher
      val result = (for {
        bootstrapBlocks <- fetchBlocks(bootstrapClique, fromTs, toTs)
        cliqueStates <- Future.traverse(cliques) { clique =>
          for {
            blocks <- fetchBlocks(clique, fromTs, toTs)
            state  <- requestAsync[SelfClique](getSelfClique, clique.masterRestPort)
          } yield (blocks, state)
        }
      } yield (bootstrapBlocks, cliqueStates)).futureValue

      result._2.foreach { case (blocks, state) =>
        checkBlocks(result._1, blocks)
        state.synced is true
      }
    }
  }

  trait P2PV2CliquesSyncFixture extends SyncFixtureBase {
    // scalastyle:off method.length
    def test() = {
      val fromTs  = TimeStamp.now()
      val clique1 = bootClique(1)

      clique1.start()
      clique1.startWsAndWaitConnection()

      clique1.startMining()
      blockNotifyProbe.receiveN(10, Duration.ofMinutesUnsafe(2).asScala)
      clique1.stopMining()

      val remainCliques = (1 until 4).map { _ =>
        bootClique(
          1,
          Some(new InetSocketAddress("127.0.0.1", clique1.masterTcpPort))
        )
      }
      startCliques(remainCliques)

      val toTs = TimeStamp.now()
      awaitSyncedWithSameBlocks(clique1, remainCliques, fromTs, toTs)

      stopCliques(clique1 +: remainCliques)
    }
    // scalastyle:on method.length
  }

  trait P2PV2SyncFixture extends SyncFixtureBase {
    private val configOverrides = Map[String, Any](
      "alephium.network.enable-p2p-v2"          -> true,
      "alephium.network.stable-sync-frequency" -> "2 seconds"
    )

    val chainStateMessageCount = new AtomicInteger(0)
    val otherSyncMessageCount  = new AtomicInteger(0)
    val injection: PartialFunction[Payload, Payload] = {
      case msg: Ping => msg
      case msg: Pong => msg
      case msg: ChainState =>
        val _ = chainStateMessageCount.incrementAndGet()
        msg
      case msg =>
        val _ = otherSyncMessageCount.incrementAndGet()
        msg
    }

    // scalastyle:off method.length
    def test(cliqueSize: Int, mining: Boolean) = {
      val fromTs = TimeStamp.now()

      val clique1 = bootClique(
        1,
        None,
        Injected.payload(injection, _),
        configOverrides
      )
      clique1.start()

      val remainCliques = (0 until cliqueSize - 1).map { _ =>
        bootClique(
          1,
          Some(new InetSocketAddress("127.0.0.1", clique1.masterTcpPort)),
          Injected.payload(injection, _),
          configOverrides
        )
      }

      if (mining) {
        clique1.startWsAndWaitConnection()
        clique1.startMining()
        awaitNBlocks(128)
        clique1.stopMining()
      }

      startCliques(remainCliques)

      val toTs = TimeStamp.now()
      awaitSyncedWithSameBlocks(clique1, remainCliques, fromTs, toTs)

      chainStateMessageCount.set(0)
      otherSyncMessageCount.set(0)
      eventually {
        chainStateMessageCount.get > 0 is true
        otherSyncMessageCount.get() is 0
      }

      stopCliques(clique1 +: remainCliques)
    }
  }

  it should "sync between v2 nodes without mining" in new P2PV2SyncFixture {
    test(10, false)
  }

  it should "sync between v2 nodes with mining" in new P2PV2SyncFixture {
    test(10, true)
  }

  it should "punish peer if not same chain id" in new CliqueFixture {
    val server0 = bootClique(1).servers.head
    server0.start().futureValue is ()

    val currentNetworkId = config.network.networkId
    currentNetworkId isnot NetworkId.AlephiumMainNet
    val modifier: ByteString => ByteString = { data =>
      val message = Message.deserialize(data).rightValue
      Message.serialize(message.payload)(new NetworkConfig {
        val networkId: NetworkId               = NetworkId.AlephiumMainNet
        val noPreMineProof: ByteString         = ByteString.empty
        val lemanHardForkTimestamp: TimeStamp  = TimeStamp.now()
        val rhoneHardForkTimestamp: TimeStamp  = TimeStamp.now()
        val danubeHardForkTimestamp: TimeStamp = TimeStamp.now()
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

  it should "sync ghost uncle blocks" in new CliqueFixture {
    val fromTs          = TimeStamp.now()
    val bootstrapClique = bootClique(1, None)
    bootstrapClique.startWithoutCheckSyncState()

    val cliques0 = AVector.from(0 until 3).map { _ =>
      bootClique(1, Some(new InetSocketAddress("127.0.0.1", bootstrapClique.masterTcpPort)))
    }
    startCliques(cliques0.toSeq)

    eventually {
      cliques0.foreach { clique =>
        request[SelfClique](getSelfClique, restPort(clique.masterTcpPort)).synced is true
      }
    }

    bootstrapClique.startMining()
    cliques0.foreach(_.startMining())
    eventually {
      request[BlocksPerTimeStampRange](
        blockflowFetch(fromTs, TimeStamp.now()),
        bootstrapClique.masterRestPort
      ).blocks.flatMap(identity).length >= 64 is true
    }
    cliques0.foreach { clique =>
      clique.servers.head.miner ! Miner.Stop
    }

    val blocks = eventually {
      val currentBlocks = request[BlocksPerTimeStampRange](
        blockflowFetch(fromTs, TimeStamp.now()),
        bootstrapClique.masterRestPort
      ).blocks.flatMap(identity)
      currentBlocks.exists(_.ghostUncles.nonEmpty) is true
      currentBlocks
    }
    bootstrapClique.servers.head.miner ! Miner.Stop

    val clique1 = bootClique(
      1,
      Some(new InetSocketAddress("127.0.0.1", bootstrapClique.masterTcpPort))
    )
    clique1.startWithoutCheckSyncState()

    val allCliques = cliques0 :+ clique1
    blocks.foreach { block =>
      eventually {
        implicit val ec: ExecutionContext = system.dispatcher
        Future
          .traverse(allCliques.toSeq) { clique =>
            requestAsync[BlockEntry](getBlock(block.hash.toHexString), clique.masterRestPort)
          }
          .futureValue
          .foreach(_ is block)
      }
      ()
    }

    stopCliques(Seq(bootstrapClique, clique1) ++ cliques0.toSeq)
  }
}
