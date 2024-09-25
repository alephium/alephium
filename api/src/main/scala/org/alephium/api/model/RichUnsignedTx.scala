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

import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model.{TransactionId, UnsignedTransaction}
import org.alephium.util.{AVector, U256}

final case class RichUnsignedTx(
    txId: TransactionId,
    version: Byte,
    networkId: Byte,
    scriptOpt: Option[Script],
    gasAmount: Int,
    gasPrice: U256,
    inputs: AVector[RichAssetInput],
    fixedOutputs: AVector[FixedAssetOutput]
) {
  def toProtocol()(implicit networkConfig: NetworkConfig): Either[String, UnsignedTransaction] = {
    val unsignedTx = UnsignedTx(
      txId,
      version,
      networkId,
      scriptOpt,
      gasAmount,
      gasPrice,
      inputs.map(input => (AssetInput(OutputRef(input.hint, input.key), input.unlockScript))),
      fixedOutputs
    )
    unsignedTx.toProtocol()
  }
}

object RichUnsignedTx {
  def fromProtocol(
    unsignedTx: UnsignedTransaction,
    inputs: AVector[RichAssetInput]
  ): RichUnsignedTx = {
    RichUnsignedTx(
      unsignedTx.id,
      unsignedTx.version,
      unsignedTx.networkId.id,
      unsignedTx.scriptOpt.map(Script.fromProtocol),
      unsignedTx.gasAmount.value,
      unsignedTx.gasPrice.value,
      inputs,
      unsignedTx.fixedOutputs.zipWithIndex.map { case (out, index) =>
        FixedAssetOutput.fromProtocol(out, unsignedTx.id, index)
      }
    )
  }
}
