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

import akka.io.Tcp
import akka.testkit.TestProbe

import org.alephium.flow.network.bootstrap.{InfoFixture, IntraCliqueInfo}
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.util.AlephiumActorSpec

class BootstrapperSpec extends AlephiumActorSpec {
  it should "bootstrap a master" in new Fixture {
    override val configValues = Map(
      ("alephium.network.internal-address", s"127.0.0.1:9972"),
      ("alephium.network.coordinator-address", s"127.0.0.1:9972"),
      ("alephium.network.external-address", s"127.0.0.1:9972")
    )

    //Peer connects
    bootstrapper ! connected

    //Broker info is full,
    cliqueManagerProbe.send(bootstrapper, Bootstrapper.ForwardConnection)
    tcpControllerProbe.expectMsg(TcpController.WorkFor(cliqueManagerProbe.ref))

    //CliqueManager is now responsible for new connection
    bootstrapper ! connected
    cliqueManagerProbe.expectMsg(connected)
    expectMsgType[Tcp.Register]
    expectMsg(Tcp.ResumeReading)

    //Receiving IntraCliqueInfo
    bootstrapper ! Bootstrapper.SendIntraCliqueInfo(intraCliqueInfo)
    cliqueManagerProbe.expectMsg(CliqueManager.Start(cliqueInfo))

    //Answering IntraCliqueInfo request
    bootstrapper ! Bootstrapper.GetIntraCliqueInfo
    expectMsg(intraCliqueInfo)
  }

  it should "bootstrap a peer" in new Fixture {
    bootstrapper ! Bootstrapper.SendIntraCliqueInfo(intraCliqueInfo)
    tcpControllerProbe.expectMsg(TcpController.WorkFor(cliqueManagerProbe.ref))
    cliqueManagerProbe.expectMsg(CliqueManager.Start(cliqueInfo))
    bootstrapper ! connected
    cliqueManagerProbe.expectMsg(connected)
  }

  it should "bootstrap a single node clique" in new Fixture {
    override val configValues: Map[String, Any] = Map(
      "alephium.broker.broker-num" -> 1
    )

    bootstrapper ! Bootstrapper.GetIntraCliqueInfo
    tcpControllerProbe.expectMsg(TcpController.WorkFor(cliqueManagerProbe.ref))
    val cliqueInfo1 = cliqueManagerProbe.expectMsgPF() { case CliqueManager.Start(cliqueInfo) =>
      cliqueInfo
    }
    val intraCliqueInfo1 = expectMsgPF() { case intraCliqueInfo: IntraCliqueInfo =>
      intraCliqueInfo
    }
    intraCliqueInfo1.cliqueInfo is cliqueInfo1
  }

  trait Fixture extends AlephiumConfigFixture with InfoFixture {
    val tcpControllerProbe = TestProbe()
    val cliqueManagerProbe = TestProbe()

    lazy val intraCliqueInfo = genIntraCliqueInfo
    lazy val cliqueInfo      = intraCliqueInfo.cliqueInfo
    lazy val bootstrapper = {
      val actor = system.actorOf(Bootstrapper.props(tcpControllerProbe.ref, cliqueManagerProbe.ref))
      tcpControllerProbe.expectMsg(TcpController.Start(actor))
      actor
    }
    lazy val connected = Tcp.Connected(socketAddressGen.sample.get, socketAddressGen.sample.get)
  }
}
