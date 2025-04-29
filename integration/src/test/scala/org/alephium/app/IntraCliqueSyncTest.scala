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

import sttp.model.StatusCode

import org.alephium.api.model.SelfClique
import org.alephium.protocol.model.ChainIndex
import org.alephium.util._

class IntraCliqueSyncTest extends AlephiumActorSpec {
  it should "boot and sync single node clique" in new CliqueFixture {
    val server = bootNode(publicPort = defaultMasterPort, brokerId = 0, brokerNum = 1)
    server.start().futureValue is (())
    eventually(request[SelfClique](getSelfClique, defaultRestMasterPort).selfReady is true)

    server.stop().futureValue is ()
  }

  it should "boot and sync two nodes clique" in new CliqueFixture {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server0.start().futureValue is ()

    val blocks = AVector.tabulate(groups0) { toGroup =>
      mineAndAndOneBlock(server0, ChainIndex.unsafe(0, toGroup))
    }

    eventually(requestFailed(getSelfClique, statusCode = StatusCode.InternalServerError))
    blocks.foreach { block =>
      eventually(server0.node.blockFlow.containsUnsafe(block.hash) is true)
    }

    val server1 = bootNode(publicPort = generatePort(), brokerId = 1)
    server1.start().futureValue is ()

    eventually(request[SelfClique](getSelfClique, defaultRestMasterPort).selfReady is true)
    blocks.foreach { block =>
      eventually(server1.node.blockFlow.containsUnsafe(block.hash) is true)
    }

    server0.stop().futureValue is ()
    server1.stop().futureValue is ()
  }
}
