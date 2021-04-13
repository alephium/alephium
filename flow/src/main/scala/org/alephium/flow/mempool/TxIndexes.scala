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

package org.alephium.flow.mempool

import scala.collection.mutable

import org.alephium.flow.core.FlowUtils.{AssetOutputInfo, MempoolOutput}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.AVector

case class TxIndexes(
    inputIndex: mutable.HashSet[AssetOutputRef],
    outputIndex: mutable.HashMap[AssetOutputRef, TxOutput],
    addressIndex: mutable.HashMap[LockupScript, mutable.ArrayBuffer[AssetOutputRef]]
) {
  def add(transaction: TransactionTemplate): Unit = {
    transaction.unsigned.inputs.foreach(input => inputIndex.addOne(input.outputRef))
    transaction.unsigned.fixedOutputs.foreachWithIndex { case (output, index) =>
      val outputRef = AssetOutputRef.from(output, TxOutputRef.key(transaction.id, index))
      outputIndex.addOne(outputRef -> output)
      addressIndex.get(output.lockupScript) match {
        case Some(outputs) =>
          if (!outputs.contains(outputRef)) {
            outputs.addOne(outputRef)
          }
        case None => addressIndex.addOne(output.lockupScript -> mutable.ArrayBuffer(outputRef))
      }
    }
  }

  def remove(transaction: TransactionTemplate): Unit = {
    transaction.unsigned.inputs.foreach(input => inputIndex.remove(input.outputRef))
    transaction.unsigned.fixedOutputs.foreachWithIndex { case (output, index) =>
      val outputRef = AssetOutputRef.from(output, TxOutputRef.key(transaction.id, index))
      outputIndex.remove(outputRef)
      addressIndex.get(output.lockupScript) match {
        case Some(outputs) =>
          outputs -= outputRef
          if (outputs.isEmpty) addressIndex.remove(output.lockupScript)
        case None => () // already removed
      }
    }
  }

  def isSpent(asset: AssetOutputInfo): Boolean = isSpent(asset.ref)

  def isSpent(asset: AssetOutputRef): Boolean = inputIndex.contains(asset)

  def getRelevantUtxos(lockupScript: LockupScript): AVector[AssetOutputInfo] = {
    addressIndex
      .get(lockupScript)
      .map { refs =>
        AVector.from(
          refs.view.map(ref =>
            AssetOutputInfo(ref, outputIndex(ref).asInstanceOf[AssetOutput], MempoolOutput)
          )
        )
      }
      .getOrElse(AVector.empty)
  }

  // Left means the output is spent
  def getUtxo(outputRef: AssetOutputRef): Either[Unit, Option[TxOutput]] = {
    if (inputIndex.contains(outputRef)) {
      Left(())
    } else {
      Right(outputIndex.get(outputRef))
    }
  }
}

object TxIndexes {
  def empty: TxIndexes =
    TxIndexes(mutable.HashSet.empty, mutable.HashMap.empty, mutable.HashMap.empty)
}
