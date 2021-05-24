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

import org.alephium.protocol.{ALF, Hash, HashSerde}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, StatefulScript, UnlockScript}
import org.alephium.serde._
import org.alephium.util.{AVector, TimeStamp, U256}

/** Upto one new token might be issued in each transaction exception for the coinbase transaction
  * The id of the new token will be hash of the first input
  *
  * @param scriptOpt optional script for invoking stateful contracts
  * @param startGas the amount of gas can be used for tx execution
  * @param inputs a vector of TxInput
  * @param fixedOutputs a vector of TxOutput. ContractOutput are put in front of AssetOutput
  */
final case class UnsignedTransaction(
    scriptOpt: Option[StatefulScript],
    startGas: GasBox,
    gasPrice: GasPrice,
    inputs: AVector[TxInput],
    fixedOutputs: AVector[AssetOutput]
) extends HashSerde[UnsignedTransaction] {
  override lazy val hash: Hash = _getHash

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
  // format: off
  implicit val serde: Serde[UnsignedTransaction] =
    Serde
      .forProduct5[Option[StatefulScript], GasBox, GasPrice, AVector[TxInput], AVector[AssetOutput], UnsignedTransaction](
        UnsignedTransaction.apply,
        t => (t.scriptOpt, t.startGas, t.gasPrice, t.inputs, t.fixedOutputs)
      )
      .validate(tx => if (GasBox.validate(tx.startGas)) Right(()) else Left("Invalid Gas"))
  // format: on

  def apply(
      txScriptOpt: Option[StatefulScript],
      inputs: AVector[TxInput],
      fixedOutputs: AVector[AssetOutput]
  ): UnsignedTransaction = {
    UnsignedTransaction(txScriptOpt, minimalGas, defaultGasPrice, inputs, fixedOutputs)
  }

  def apply(inputs: AVector[TxInput], fixedOutputs: AVector[AssetOutput]): UnsignedTransaction = {
    UnsignedTransaction(None, minimalGas, defaultGasPrice, inputs, fixedOutputs)
  }

  def coinbase(
      inputs: AVector[TxInput],
      fixedOutputs: AVector[AssetOutput]
  ): UnsignedTransaction = {
    UnsignedTransaction(None, minimalGas, defaultGasPrice, inputs, fixedOutputs)
  }

  def transferAlf(
      inputs: AVector[(AssetOutputRef, AssetOutput)],
      fromLockupScript: LockupScript,
      fromUnlockScript: UnlockScript,
      toLockupScript: LockupScript,
      lockTimeOpt: Option[TimeStamp],
      amount: U256,
      gas: GasBox,
      gasPrice: GasPrice
  ): Either[String, UnsignedTransaction] = {
    assume(gas >= minimalGas)
    assume(gasPrice.value <= ALF.MaxALFValue)
    val inputSum = inputs.fold(U256.Zero)(_ addUnsafe _._2.amount)
    val gasFee   = gasPrice * gas
    (for {
      remainder0 <- inputSum.sub(amount)
      remainder  <- remainder0.sub(gasFee)
    } yield {
      val toOutput   = TxOutput.asset(amount, toLockupScript, lockTimeOpt)
      val fromOutput = TxOutput.asset(remainder, fromLockupScript)

      val outputs =
        if (remainder > U256.Zero) {
          AVector[AssetOutput](toOutput, fromOutput)
        } else {
          AVector[AssetOutput](toOutput)
        }
      UnsignedTransaction(
        None,
        gas,
        gasPrice,
        inputs.map { case (ref, _) =>
          TxInput(ref, fromUnlockScript)
        },
        outputs
      )
    }).toRight(s"Not enough balance")
  }
}
