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

import java.net.{InetAddress, InetSocketAddress}

import akka.actor.ActorRef
import akka.io.{IO, Tcp}
import akka.testkit.{SocketUtil, TestActorRef, TestProbe}
import org.scalatest.concurrent.Eventually.eventually

import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.util.{ActorRefT, AlephiumActorSpec}

class TcpControllerSpec extends AlephiumActorSpec("TcpController") with AlephiumConfigFixture {
  trait Fixture {
    val discoveryServer    = TestProbe()
    val misbehaviorManager = TestProbe()
    val bootstrapper       = TestProbe()

    val bindAddress = SocketUtil.temporaryServerAddress()
    val controller =
      TestActorRef[TcpController](TcpController.props(bindAddress, misbehaviorManager.ref))
    val controllerActor = controller.underlyingActor

    controller ! TcpController.Start(bootstrapper.ref)
    Thread.sleep(200) // wait for tcp controller to be bounded

    def connectToController(): (InetSocketAddress, ActorRef) = {
      IO(Tcp) ! Tcp.Connect(bindAddress)
      expectMsgType[Tcp.Connected]

      val confirm = misbehaviorManager.expectMsgType[MisbehaviorManager.ConfirmConnection]
      controller ! TcpController.ConnectionConfirmed(confirm.connected, confirm.connection)

      bootstrapper.expectMsgType[Tcp.Connected]
      val connection = bootstrapper.lastSender
      (confirm.connected.remoteAddress, connection)
    }
  }

  it should "bind a tcp socket and accept incoming connections" in new Fixture {
    val address0 = connectToController()._1
    controllerActor.confirmedConnections.contains(address0) is true

    val address1 = connectToController()._1
    controllerActor.confirmedConnections.contains(address1) is true

    val address2 = connectToController()._1
    controllerActor.confirmedConnections.contains(address2) is true
  }

  it should "not accept denied connections" in new Fixture {
    IO(Tcp) ! Tcp.Connect(bindAddress)
    val confirm = misbehaviorManager.expectMsgType[MisbehaviorManager.ConfirmConnection]
    controller ! TcpController.ConnectionDenied(confirm.connected, confirm.connection)

    val address = confirm.connected.remoteAddress
    controllerActor.confirmedConnections.contains(address) is false
  }

  it should "monitor the termination of connections" in new Fixture {
    val (address, connection) = connectToController()
    controllerActor.confirmedConnections.contains(address) is true

    controllerActor.removeConnection(TestProbe().ref)
    controllerActor.confirmedConnections.contains(address) is true

    system.stop(connection)
    eventually {
      controllerActor.confirmedConnections.contains(address) is false
    }
  }

  it should "remove banned connections" in new Fixture {
    val (address, _) = connectToController()
    controllerActor.confirmedConnections.contains(address) is true

    controller ! MisbehaviorManager.PeerBanned(InetAddress.getByName("8.8.8.8"))
    eventually {
      controllerActor.confirmedConnections.contains(address) is true
    }

    controller ! MisbehaviorManager.PeerBanned(address.getAddress)
    eventually {
      controllerActor.confirmedConnections.contains(address) is false
    }
  }

  it should "handle outgoing connections" in {
    val fixture1 = new Fixture {}
    val fixture2 = new Fixture {}
    eventually {
      fixture2.controller ! TcpController.ConnectTo(fixture1.bindAddress,
                                                    ActorRefT(TestProbe().ref))
      fixture2.controllerActor.confirmedConnections.contains(fixture1.bindAddress) is true
    }
  }

  it should "forward connection failure" in new Fixture {
    val freeAddress = SocketUtil.temporaryServerAddress()
    val probe       = TestProbe()
    controller ! TcpController.ConnectTo(freeAddress, probe.ref)
    probe.expectMsgType[Tcp.CommandFailed]
  }
}
