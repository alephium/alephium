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

import org.alephium.util._

class IntraCliqueSyncTest extends AlephiumSpec {
  it should "boot and sync single node clique" in new TestFixture("1-node") {
    val server = bootNode(publicPort = defaultMasterPort, brokerId = 0, brokerNum = 1)
    server.start().futureValue is (())
    eventually(request[Boolean](getSelfCliqueSynced) is true)
  }

  it should "boot and sync two nodes clique" in new TestFixture("2-nodes") {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server0.start().futureValue is (())

    request[Boolean](getSelfCliqueSynced) is false

    val server1 = bootNode(publicPort = generatePort, brokerId = 1)
    server1.start().futureValue is (())

    eventually(request[Boolean](getSelfCliqueSynced) is true)
  }
}
