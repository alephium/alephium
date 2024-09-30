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

package org.alephium.flow.core

import org.alephium.flow.core.FlowUtils.{AssetOutputInfo, MemPoolOutput}
import org.alephium.protocol.model.{AssetOutputRef, TxOutputRef, UnsignedTransaction}
import org.alephium.util.AVector

final case class ExtraUtxosInfo(
    newUtxos: AVector[AssetOutputInfo],
    spentUtxos: AVector[AssetOutputRef]
) {
  def merge(utxos: AVector[AssetOutputInfo]): AVector[AssetOutputInfo] = {
    newUtxos ++ utxos.filterNot(utxo => spentUtxos.contains(utxo.ref))
  }
}

object ExtraUtxosInfo {
  def empty: ExtraUtxosInfo = ExtraUtxosInfo(
    newUtxos = AVector.empty,
    spentUtxos = AVector.empty
  )

  def updateExtraUtxosInfoWithUnsignedTx(
      extraUtxosInfo: ExtraUtxosInfo,
      unsignedTx: UnsignedTransaction
  ): ExtraUtxosInfo = {
    val remainingNewUtxos = extraUtxosInfo.newUtxos.filterNot { utxo =>
      unsignedTx.inputs.exists(_.outputRef == utxo.ref)
    }
    val newUtxosFromTx = unsignedTx.fixedOutputs.mapWithIndex { (txOutput, index) =>
      val txOutputRef = AssetOutputRef.from(txOutput, TxOutputRef.key(unsignedTx.id, index))
      AssetOutputInfo(txOutputRef, txOutput, MemPoolOutput)
    }

    extraUtxosInfo.copy(
      newUtxos = remainingNewUtxos ++ newUtxosFromTx,
      spentUtxos = extraUtxosInfo.spentUtxos ++ unsignedTx.inputs.map(_.outputRef)
    )
  }
}
