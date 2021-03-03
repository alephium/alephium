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

import akka.actor.{Actor, ActorRef}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.api.model.{PeerStatus, SelfClique}
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.flow.network.broker.ConnectionHandler
import org.alephium.protocol.message.{Message, Payload, Pong}
import org.alephium.protocol.model.NetworkType
import org.alephium.util._

class InjectedPayload[T](injection: PartialFunction[Payload, Payload], ref: ActorRef)(implicit groupConfig: GroupConfig, networkConfig: NetworkConfig) extends ActorRefT[T](ref) {
  override def !(message: T)(implicit sender: ActorRef = Actor.noSender): Unit =  {
    message match {
      case Tcp.Write(data, ack) =>
        val staging = ConnectionHandler.tryDeserializePayload(data, networkConfig.networkType)
                      .toOption.flatMap(identity).getOrElse { sys.error("Invalid data")}

        if (injection.isDefinedAt(staging.value)) {
          val injected = injection.apply(staging.value)
          val injectedData = Message.serialize(injected, networkConfig.networkType)
          ref.!(Tcp.Write(injectedData, ack))(sender)
        } else {
          ref.!(message)(sender)
        }
      case _ =>
        ref.!(message)(sender)
    }
  }
}

class Injected[T](injection: ByteString => Option[ByteString], ref: ActorRef) extends ActorRefT[T](ref) {
  override def !(message: T)(implicit sender: ActorRef = Actor.noSender): Unit =  {
    message match {
      case Tcp.Write(data, ack) =>
        injection.apply(data) match {
          case Some(injectedData) =>
            ref.!(Tcp.Write(injectedData, ack))(sender)
          case None =>
            ref.!(message)(sender)
        }
      case _ =>
        ref.!(message)(sender)
    }
  }
}

object Injected {
  def  apply[T](injection: ByteString => Option[ByteString], ref: ActorRef): Injected[T] =
    new Injected(injection, ref)

  def payload[T](injection: PartialFunction[Payload, Payload], ref: ActorRef)(implicit groupConfig: GroupConfig, networkConfig: NetworkConfig): Injected[T] = {
    val injectionData: ByteString => Option[ByteString] = data => {
      val staging = ConnectionHandler.tryDeserializePayload(data, networkConfig.networkType)
                    .toOption.flatMap(identity).getOrElse { sys.error("Invalid data")}

      if (injection.isDefinedAt(staging.value)) {
        val injected = injection.apply(staging.value)
        val injectedData = Message.serialize(injected, networkConfig.networkType)
        Some(injectedData)
      } else {
        None
      }
    }

    new Injected(injectionData, ref)
  }
}

class IntraCliqueSyncTest extends AlephiumSpec {
  it should "boot and sync single node clique" in new TestFixture("1-node") {
    val server = bootNode(publicPort = defaultMasterPort, brokerId = 0, brokerNum = 1)
    server.start().futureValue is (())
    eventually(request[SelfClique](getSelfClique).synced is true)

    server.stop().futureValue is ()
  }

  it should "boot and sync two nodes clique" in new TestFixture("2-nodes") {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server0.start().futureValue is ()

    Thread.sleep(2000) // wait until the server is fully operating
    requestFailed(getSelfClique)

    val server1 = bootNode(publicPort = generatePort, brokerId = 1)
    server1.start().futureValue is ()

    eventually(request[SelfClique](getSelfClique).synced is true)

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }

  it should "ban node if not same network type" in new TestFixture("2-nodes") {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server0.start().futureValue is ()

    val server1MasterPort = generatePort
    val server1 =
      bootNode(publicPort = server1MasterPort, brokerId = 1, networkType = Some(NetworkType.Mainnet))
    server1.start().futureValue is ()

    val selfClique1 = request[SelfClique](getSelfClique, restPort(server1MasterPort))

    selfClique1.peers.find(_.restPort == restPort(defaultMasterPort)).flatMap(_.status).exists {
      case PeerStatus.Banned(_) => true
      case _ => false
    } is true

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }

  it should "ban node if send invalid pong" in new TestFixture("2-nodes") {
    val injection: PartialFunction[Payload, Payload] = {
      case Pong(_) => Pong(0)
    }

    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server0.start().futureValue is ()

    val server1MasterPort = generatePort
    val server1 = bootNode(publicPort = server1MasterPort, brokerId = 1, connectionBuild = Injected.payload(injection, _))

    server1.start().futureValue is ()

    Thread.sleep(2000)

    val selfClique0 = request[SelfClique](getSelfClique, restPort(defaultMasterPort))
    selfClique0.peers.find(_.restPort == restPort(defaultMasterPort)).flatMap(_.status).exists {
      case PeerStatus.Banned(_) => true
      case _ => false
    } is true

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }

  it should "ban node if spamming" in new TestFixture("2-nodes") {
    val injectionData: ByteString => Option[ByteString] = {
      _ => Some(ByteString.fromArray(Array.fill[Byte](42)(0)))
    }

    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server0.start().futureValue is ()

    val server1MasterPort = generatePort
    val server1 = bootNode(publicPort = server1MasterPort, brokerId = 1, connectionBuild = Injected(injectionData, _))

    server1.start().futureValue is ()

    val selfClique0 = request[SelfClique](getSelfClique, restPort(defaultMasterPort))
    selfClique0.peers.find(_.restPort == restPort(defaultMasterPort)).flatMap(_.status).exists {
      case PeerStatus.Banned(_) => true
      case _ => false
    } is true

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }
}
