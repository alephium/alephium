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

import akka.util.ByteString

import org.alephium.protocol.model.{Transaction, TransactionId}
import org.alephium.serde._
import org.alephium.util.{AVector, U256}

final case class RichTransaction(
    txId: TransactionId,
    version: Byte,
    networkId: Byte,
    scriptOpt: Option[Script],
    gasAmount: Int,
    gasPrice: U256,
    inputs: AVector[RichInput],
    outputs: AVector[Output],
    scriptExecutionOk: Boolean,
    inputSignatures: AVector[ByteString],
    scriptSignatures: AVector[ByteString]
)

object RichTransaction {
  def from(transaction: Transaction, inputs: AVector[RichInput]): RichTransaction = {
    val txId = transaction.unsigned.id
    val outputs = transaction.allOutputs.zipWithIndex.map { case (out, index) =>
      Output.from(out, txId, index)
    }

    RichTransaction(
      txId = transaction.id,
      version = transaction.unsigned.version,
      networkId = transaction.unsigned.networkId.id,
      scriptOpt = transaction.unsigned.scriptOpt.map(Script.fromProtocol),
      gasAmount = transaction.unsigned.gasAmount.value,
      gasPrice = transaction.unsigned.gasPrice.value,
      inputs = inputs,
      outputs = outputs,
      scriptExecutionOk = transaction.scriptExecutionOk,
      inputSignatures = transaction.inputSignatures.map(sig => serialize(sig)),
      scriptSignatures = transaction.scriptSignatures.map(sig => serialize(sig))
    )
  }
}
