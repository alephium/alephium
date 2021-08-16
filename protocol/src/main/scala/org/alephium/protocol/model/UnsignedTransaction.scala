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

package org.alephium.protocol.model

import org.alephium.macros.HashSerde
import org.alephium.protocol.ALF
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.vm._
import org.alephium.serde._
import org.alephium.util.{AVector, TimeStamp, U256}

/** Upto one new token might be issued in each transaction exception for the coinbase transaction
  * The id of the new token will be hash of the first input
  *
  * @param chainId the id of the chain which can accept the tx
  * @param scriptOpt optional script for invoking stateful contracts
  * @param startGas the amount of gas can be used for tx execution
  * @param inputs a vector of TxInput
  * @param fixedOutputs a vector of TxOutput. ContractOutput are put in front of AssetOutput
  */
@HashSerde
final case class UnsignedTransaction(
    chainId: ChainId,
    scriptOpt: Option[StatefulScript],
    startGas: GasBox,
    gasPrice: GasPrice,
    inputs: AVector[TxInput],
    fixedOutputs: AVector[AssetOutput]
) extends AnyRef {
  // this might only works for validated tx
  def fromGroup(implicit config: GroupConfig): GroupIndex = {
    inputs.head.fromGroup
  }

  // this might only works for validated tx
  def toGroup(implicit config: GroupConfig): GroupIndex = {
    val from    = fromGroup
    val outputs = fixedOutputs
    if (outputs.isEmpty) {
      from
    } else {
      val index = outputs.indexWhere(_.toGroup != from)
      if (index == -1) {
        from
      } else {
        outputs(index).toGroup
      }
    }
  }

  // this might only works for validated tx
  def chainIndex(implicit config: GroupConfig): ChainIndex = ChainIndex(fromGroup, toGroup)
}

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] = {
    val serde: Serde[UnsignedTransaction] = Serde.forProduct6(
      UnsignedTransaction.apply,
      t => (t.chainId, t.scriptOpt, t.startGas, t.gasPrice, t.inputs, t.fixedOutputs)
    )
    serde.validate(tx => if (GasBox.validate(tx.startGas)) Right(()) else Left("Invalid Gas"))
  }

  def apply(
      scriptOpt: Option[StatefulScript],
      startGas: GasBox,
      gasPrice: GasPrice,
      inputs: AVector[TxInput],
      fixedOutputs: AVector[AssetOutput]
  )(implicit networkConfig: NetworkConfig): UnsignedTransaction = {
    new UnsignedTransaction(
      networkConfig.chainId,
      scriptOpt,
      startGas,
      gasPrice,
      inputs,
      fixedOutputs
    )
  }

  def apply(
      txScriptOpt: Option[StatefulScript],
      inputs: AVector[TxInput],
      fixedOutputs: AVector[AssetOutput]
  )(implicit networkConfig: NetworkConfig): UnsignedTransaction = {
    UnsignedTransaction(
      networkConfig.chainId,
      txScriptOpt,
      minimalGas,
      defaultGasPrice,
      inputs,
      fixedOutputs
    )
  }

  def apply(inputs: AVector[TxInput], fixedOutputs: AVector[AssetOutput])(implicit
      networkConfig: NetworkConfig
  ): UnsignedTransaction = {
    UnsignedTransaction(
      networkConfig.chainId,
      None,
      minimalGas,
      defaultGasPrice,
      inputs,
      fixedOutputs
    )
  }

  def coinbase(inputs: AVector[TxInput], fixedOutputs: AVector[AssetOutput])(implicit
      networkConfig: NetworkConfig
  ): UnsignedTransaction = {
    UnsignedTransaction(
      networkConfig.chainId,
      None,
      minimalGas,
      defaultGasPrice,
      inputs,
      fixedOutputs
    )
  }

  def transferAlf(
      inputs: AVector[(AssetOutputRef, AssetOutput)],
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      outputInfos: AVector[(LockupScript.Asset, U256, Option[TimeStamp])],
      gas: GasBox,
      gasPrice: GasPrice
  )(implicit networkConfig: NetworkConfig): Either[String, UnsignedTransaction] = {
    assume(gas >= minimalGas)
    assume(gasPrice.value <= ALF.MaxALFValue)
    val gasFee = gasPrice * gas
    for {
      inputSum     <- inputs.foldE(U256.Zero)(_ add _._2.amount toRight s"Input amount overflow")
      outputAmount <- outputInfos.foldE(U256.Zero)(_ add _._2 toRight s"Output amount overflow")
      remainder0   <- inputSum.sub(outputAmount).toRight(s"Not enough balance")
      remainder    <- remainder0.sub(gasFee).toRight(s"Not enough balance for gas fee")
    } yield {
      var outputs = outputInfos.map { case (toLockupScript, amount, lockTimeOpt) =>
        TxOutput.asset(amount, toLockupScript, lockTimeOpt)
      }
      if (remainder > U256.Zero) {
        outputs = outputs :+ TxOutput.asset(remainder, fromLockupScript)
      }
      UnsignedTransaction(
        networkConfig.chainId,
        scriptOpt = None,
        gas,
        gasPrice,
        inputs.map { case (ref, _) =>
          TxInput(ref, fromUnlockScript)
        },
        outputs
      )
    }
  }
}
