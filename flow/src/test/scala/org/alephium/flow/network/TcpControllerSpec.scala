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

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.actor.ActorRef.noSender
import akka.io.{IO, Tcp}
import akka.testkit.{EventFilter, SocketUtil, TestProbe}

import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.setting.{AlephiumConfigFixture, NetworkSetting}
import org.alephium.util._

class TcpControllerSpec extends AlephiumActorSpec with AlephiumConfigFixture {

  trait Fixture {
    implicit val system = createSystem()

    val discoveryServer    = TestProbe()
    val misbehaviorManager = TestProbe()
    val bootstrapper       = TestProbe()

    val bindAddress = SocketUtil.temporaryServerAddress()
    val controller =
      newTestActorRef[TcpController](TcpController.props(bindAddress, misbehaviorManager.ref))
    val controllerActor = controller.underlyingActor

    EventFilter.info(start = "Node bound to").intercept {
      controller ! TcpController.Start(bootstrapper.ref)
    }

    def connectToController(): (InetSocketAddress, ActorRef) = {
      connectToController(10)
    }

    def connectToController(
        n: Int,
        confirmed: Boolean = true
    ): (InetSocketAddress, ActorRef) = {
      IO(Tcp) ! Tcp.Connect(bindAddress)
      expectMsgPF() {
        case _: Tcp.Connected =>
          val confirm = misbehaviorManager.expectMsgType[MisbehaviorManager.ConfirmConnection]

          if (confirmed) {
            controller ! TcpController.ConnectionConfirmed(confirm.connected, confirm.connection)
            bootstrapper.expectMsgType[Tcp.Connected]
            val connection = bootstrapper.lastSender
            (confirm.connected.remoteAddress, connection)
          } else {
            controller ! TcpController.ConnectionDenied(confirm.connected, confirm.connection)
            (confirm.connected.remoteAddress, noSender)
          }
        case _: Tcp.CommandFailed =>
          Thread.sleep(100)
          assert(n > 0)
          connectToController(n - 1, confirmed)
      }
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
    connectToController(10, confirmed = false)

    controllerActor.confirmedConnections.isEmpty is true
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
      fixture2.controller ! TcpController.ConnectTo(
        fixture1.bindAddress,
        ActorRefT(TestProbe()(fixture2.system).ref)
      )
      fixture2.controllerActor.confirmedConnections.contains(fixture1.bindAddress) is true
    }
  }

  it should "forward connection failure" in new Fixture {
    val freeAddress = SocketUtil.temporaryServerAddress()
    val probe       = TestProbe()
    controller ! TcpController.ConnectTo(freeAddress, probe.ref)
    probe.expectMsgType[Tcp.CommandFailed]
  }

  class TcpControllerTest(
      bindAddress: InetSocketAddress,
      misbehaviorManager: ActorRefT[MisbehaviorManager.Command],
      tcpListener: ActorRef,
      target: ActorRef
  )(implicit networkSetting: NetworkSetting)
      extends TcpController(bindAddress, misbehaviorManager)(networkSetting) {
    override def receive: Receive = workFor(tcpListener, target)
  }

  def testController(
      bindAddress: InetSocketAddress,
      misbehaviorManager: ActorRefT[MisbehaviorManager.Command]
  )(implicit system: ActorSystem): ActorRef = {
    val props =
      Props(
        new TcpControllerTest(bindAddress, misbehaviorManager, TestProbe().ref, TestProbe().ref)
      )
    system.actorOf(props)
  }
}
