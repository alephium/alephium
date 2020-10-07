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

import org.alephium.protocol.{Hash, HashSerde}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.{LockupScript, StatefulScript, UnlockScript}
import org.alephium.serde._
import org.alephium.util.{AVector, U64}

/**
  * Upto one new token might be issued in each transaction exception for the coinbase transaction
  * The id of the new token will be hash of the first input
  *
  * @param scriptOpt optional script for invoking stateful contracts
  * @param inputs a vector of TxInput
  * @param fixedOutputs a vector of TxOutput. ContractOutput are put in front of AssetOutput
  */
final case class UnsignedTransaction(scriptOpt: Option[StatefulScript],
                                     inputs: AVector[TxInput],
                                     fixedOutputs: AVector[AssetOutput])
    extends HashSerde[UnsignedTransaction] {
  override lazy val hash: Hash = _getHash

  // this might only works for validated tx
  def fromGroup(implicit config: GroupConfig): GroupIndex = {
    inputs.head.fromGroup
  }

  // this might only works for validated tx
  def toGroup(implicit config: GroupConfig): GroupIndex = {
    val from    = fromGroup
    val outputs = fixedOutputs
    if (outputs.isEmpty) from
    else {
      val index = outputs.indexWhere(_.toGroup != from)
      if (index == -1) from
      else outputs(index).toGroup
    }
  }

  // this might only works for validated tx
  def chainIndex(implicit config: GroupConfig): ChainIndex = ChainIndex(fromGroup, toGroup)
}

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] =
    Serde.forProduct3(UnsignedTransaction.apply, t => (t.scriptOpt, t.inputs, t.fixedOutputs))

  def apply(inputs: AVector[TxInput], fixedOutputs: AVector[AssetOutput]): UnsignedTransaction = {
    UnsignedTransaction(None, inputs, fixedOutputs)
  }

  def transferAlf(inputs: AVector[AssetOutputRef],
                  inputSum: U64,
                  fromLockupScript: LockupScript,
                  fromUnlockScript: UnlockScript,
                  toLockupScript: LockupScript,
                  amount: U64,
                  height: Int): UnsignedTransaction = {
    assume(inputSum >= amount)
    val remainder = inputSum.subUnsafe(amount)

    val toOutput   = TxOutput.asset(amount, height, toLockupScript)
    val fromOutput = TxOutput.asset(remainder, height, fromLockupScript)

    val outputs =
      if (remainder > U64.Zero) AVector[AssetOutput](toOutput, fromOutput)
      else AVector[AssetOutput](toOutput)
    UnsignedTransaction(inputs.map(TxInput(_, fromUnlockScript)), outputs)
  }
}
