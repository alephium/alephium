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

package org.alephium.api.endpoints

import sttp.tapir._
import sttp.tapir.generic.auto._

import org.alephium.api.Endpoints._
import org.alephium.api.UtilJson.avectorReadWriter
import org.alephium.api.endpoints._
import org.alephium.api.model.{Address => _, _}
import org.alephium.protocol.model.{Balance => _, Transaction => _, _}
import org.alephium.util.AVector

trait MempoolEndpoints extends BaseEndpoints {

  private lazy val mempoolEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("mempool")
      .tag("Mempool")

  private lazy val mempoolTxEndpoint: BaseEndpoint[Unit, Unit] =
    mempoolEndpoint
      .in("transactions")

  lazy val listMempoolTransactions: BaseEndpoint[Unit, AVector[MempoolTransactions]] =
    mempoolTxEndpoint.get
      .out(jsonBody[AVector[MempoolTransactions]])
      .summary("List mempool transactions")

  lazy val rebroadcastMempoolTransaction: BaseEndpoint[TransactionId, Unit] =
    mempoolTxEndpoint.put
      .in("rebroadcast")
      .in(query[TransactionId]("txId"))
      .summary("Rebroadcase a mempool transaction to the network")

  lazy val clearMempool: BaseEndpoint[Unit, Unit] =
    mempoolTxEndpoint.delete
      .summary("Remove all transactions from mempool")

  lazy val validateMempoolTransactions: BaseEndpoint[Unit, Unit] =
    mempoolTxEndpoint.put
      .in("validate")
      .summary("Validate all mempool transactions and remove invalid ones")
}
