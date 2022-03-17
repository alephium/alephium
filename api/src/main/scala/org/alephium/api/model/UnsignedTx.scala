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
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model.{NetworkId, UnsignedTransaction}
import org.alephium.protocol.vm
import org.alephium.util.{AVector, U256}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class UnsignedTx(
    txId: Hash,
    version: Byte,
    networkId: Byte,
    scriptOpt: Option[Script] = None,
    gasAmount: Int,
    gasPrice: U256,
    inputs: AVector[AssetInput],
    fixedOutputs: AVector[FixedAssetOutput]
) {
  def toProtocol()(implicit networkConfig: NetworkConfig): Either[String, UnsignedTransaction] = {
    val gas = vm.GasPrice(gasPrice)
    for {
      id          <- NetworkId.from(networkId.toInt).toRight("Invalid network id")
      _           <- Either.cond(id == networkConfig.networkId, (), "Network id mismatch")
      script      <- scriptOpt.map(_.toProtocol().map(Some(_))).getOrElse(Right(None))
      assetInputs <- inputs.mapE(_.toProtocol())
      gasBox      <- vm.GasBox.from(gasAmount).toRight("Invalid gas amount")
      _           <- Either.cond(vm.GasPrice.validate(gas), (), "Invalid gas price")
      utx = UnsignedTransaction(
        version,
        id,
        script,
        gasBox,
        gas,
        assetInputs,
        fixedOutputs.map(_.toProtocol())
      )
      _ <- Either.cond(txId == utx.hash, (), "Invalid hash")
    } yield utx
  }
}

object UnsignedTx {
  def fromProtocol(unsignedTx: UnsignedTransaction): UnsignedTx = {
    UnsignedTx(
      unsignedTx.hash,
      unsignedTx.version,
      unsignedTx.networkId.id,
      unsignedTx.scriptOpt.map(Script.fromProtocol),
      unsignedTx.gasAmount.value,
      unsignedTx.gasPrice.value,
      unsignedTx.inputs.map(AssetInput.from),
      unsignedTx.fixedOutputs.zipWithIndex.map { case (out, index) =>
        FixedAssetOutput.fromProtocol(out, unsignedTx.hash, index)
      }
    )
  }
}
