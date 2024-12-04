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

class BlocksExportImportTest extends AlephiumActorSpec {
  it should "correcly export/import blocks" in new CliqueFixture {
    val filename = s"export-import-test-${TimeStamp.now().millis}"

    val server = bootNode(publicPort = defaultMasterPort, brokerId = 0, brokerNum = 1)

    val blockMinedNum = 10
    server.start().futureValue is ()

    startWsClient(defaultRestMasterPort).futureValue is ()

    request[Boolean](startMining, defaultRestMasterPort) is true

    awaitNBlocks(blockMinedNum)

    request[Boolean](stopMining, defaultRestMasterPort) is true

    unitRequest(exportBlocks(filename))

    server.stop().futureValue is ()

    val newPort   = generatePort()
    val newServer = bootNode(publicPort = newPort, brokerId = 0, brokerNum = 1)

    newServer.start().futureValue is ()

    startWsClient(restPort(newPort)).futureValue is ()

    val file = rootPath.resolve(filename).toFile

    BlocksImporter.importBlocks(file, newServer.node).rightValue >= blockMinedNum is true

    awaitNBlocks(blockMinedNum)

    newServer.stop().futureValue is ()
  }
}
