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
import org.alephium.protocol.model._
import org.alephium.util.{AVector, U256}

final case class Tx(
    id: Hash,
    inputs: AVector[Input],
    outputs: AVector[Output],
    startGas: Int,
    gasPrice: U256
)

object Tx {
  def from(tx: Transaction, networkType: NetworkType): Tx =
    Tx(
      tx.id,
      tx.unsigned.inputs.map[Input](Input.from) ++
        tx.contractInputs.map[Input](Input.from),
      tx.unsigned.fixedOutputs.map(Output.from(_, networkType)) ++
        tx.generatedOutputs.map(Output.from(_, networkType)),
      tx.unsigned.startGas.value,
      tx.unsigned.gasPrice.value
    )

  def from(unsignedTx: UnsignedTransaction, networkType: NetworkType): Tx =
    Tx(
      unsignedTx.hash,
      unsignedTx.inputs.map(Input.from),
      unsignedTx.fixedOutputs.map(Output.from(_, networkType)),
      unsignedTx.startGas.value,
      unsignedTx.gasPrice.value
    )

  def fromTemplate(tx: TransactionTemplate, networkType: NetworkType): Tx =
    from(tx.unsigned, networkType)
}
