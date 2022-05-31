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

import org.alephium.api.model.Group
import org.alephium.util.AlephiumActorSpec

class FailedTxTest extends AlephiumActorSpec {
  it should "return the right error details" in new CliqueFixture {
    val clique = bootClique(
      nbOfNodes = 2,
      configOverrides = Map(("alephium.mempool.auto-mine-for-dev", true))
    )
    clique.start()

    val selfClique = clique.selfClique()
    val group      = request[Group](getGroup(address), clique.masterRestPort)
    val index      = group.group % selfClique.brokerNum
    val restPort   = selfClique.nodes(index).restPort

    def test() = {
      val (unsignedTx, txId) = {
        val code   = "@use(approvedAssets = false) TxScript Main { return }"
        val result = buildExecuteScriptTxWithPort(code, restPort)
        (result.unsignedTx, result.txId)
      }
      val txQuery0 = submitTxQuery(unsignedTx, txId)
      requestFailed(txQuery0, restPort, StatusCode.InternalServerError)

      val txQuery1 = submitTxQuery(unsignedTx, txId)
      requestFailed(txQuery1, restPort, StatusCode.InternalServerError)
    }

    test()
    test() // make the same test fail with the same error
  }
}
