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

import org.alephium.flow._
import org.alephium.flow.network.bootstrap.InfoFixture
import org.alephium.protocol.model.NoIndexModelGeneratorsLike

class BootstrapperSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  it should "bootstrap a master" in new MasterFixture("BootstrapperSpec-master") {
    val bootstrapper = system.actorOf(Bootstrapper.props(serverProbe.ref, cliqueManagerProbe.ref))

    serverProbe.expectMsg(TcpController.Start(bootstrapper))

    //Peer connects
    bootstrapper ! connected

    //Broker info is full,
    cliqueManagerProbe.send(bootstrapper, Bootstrapper.ForwardConnection)
    serverProbe.expectMsg(TcpController.WorkFor(cliqueManagerProbe.ref))

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

  it should "bootstrap a peer" in new AlephiumFlowActorSpec("BootstrapperSpec") {
    val serverProbe        = TestProbe()
    val cliqueManagerProbe = TestProbe()

    val bootstrapper = system.actorOf(Bootstrapper.props(serverProbe.ref, cliqueManagerProbe.ref))
    serverProbe.expectMsg(TcpController.Start(bootstrapper))
  }

  class MasterFixture(name: String) extends AlephiumFlowActorSpec(name) with InfoFixture {
    override val configValues = Map(
      ("alephium.network.internal-address", s"localhost:9972"),
      ("alephium.network.coordinator-address", s"localhost:9972"),
      ("alephium.network.external-address", s"localhost:9972")
    )

    val connected =
      Tcp.Connected(socketAddressGen.sample.get, socketAddressGen.sample.get)
    val intraCliqueInfo = genIntraCliqueInfo
    val cliqueInfo      = intraCliqueInfo.cliqueInfo

    val serverProbe            = TestProbe()
    val discoveryServerProbe   = TestProbe()
    val cliqueManagerProbe     = TestProbe()
    val cliqueCoordinatorProbe = TestProbe()
  }
}
