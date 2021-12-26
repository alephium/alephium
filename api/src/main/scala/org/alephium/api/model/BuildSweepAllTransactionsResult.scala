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

package org.alephium.api.model

import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.UnsignedTransaction
import org.alephium.serde.serialize
import org.alephium.util.{AVector, Hex}

final case class BuildSweepAllTransactionsResult(
    unsignedTxs: AVector[SweepAllTransaction],
    fromGroup: Int,
    toGroup: Int
)

final case class SweepAllTransaction(
    txId: Hash,
    unsignedTx: String
)

object BuildSweepAllTransactionsResult {

  def from(
      unsignedTx: UnsignedTransaction
  )(implicit groupConfig: GroupConfig): BuildSweepAllTransactionsResult = {
    val sweepAllTx = SweepAllTransaction(
      unsignedTx.hash,
      Hex.toHexString(serialize(unsignedTx))
    )
    BuildSweepAllTransactionsResult(
      AVector(sweepAllTx),
      unsignedTx.fromGroup.value,
      unsignedTx.toGroup.value
    )
  }
}