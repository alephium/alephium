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

import org.alephium.flow.core.FlowUtils._
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, RWLock}

final case class TxIndexes(
    mainGroup: GroupIndex,
    inputIndex: mutable.HashSet[AssetOutputRef],
    outputIndex: mutable.HashMap[AssetOutputRef, AssetOutput],
    addressIndex: mutable.HashMap[LockupScript, mutable.ArrayBuffer[AssetOutputRef]],
    outputType: OutputType
)(implicit groupConfig: GroupConfig)
    extends RWLock {
  def add(transaction: TransactionTemplate): Unit = writeOnly {
    _add(transaction)
  }

  def add(transactions: AVector[TransactionTemplate]): Unit = writeOnly {
    transactions.foreach(_add)
  }

  private def _add(transaction: TransactionTemplate): Unit = {
    transaction.unsigned.inputs.foreach(input => inputIndex.addOne(input.outputRef))
    transaction.unsigned.fixedOutputs.foreachWithIndex { case (output, index) =>
      if (output.toGroup == mainGroup) {
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
  }

  def remove(transaction: TransactionTemplate): Unit = writeOnly {
    _remove(transaction)
  }

  def remove(transactions: AVector[TransactionTemplate]): Unit = writeOnly {
    transactions.foreach(_remove)
  }

  private def _remove(transaction: TransactionTemplate): Unit = {
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

  def isSpent(asset: AssetOutputRef): Boolean = readOnly {
    inputIndex.contains(asset)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def getRelevantUtxos(lockupScript: LockupScript): AVector[AssetOutputInfo] = readOnly {
    addressIndex
      .get(lockupScript)
      .map { refs =>
        AVector.from(
          refs.view.map(ref =>
            AssetOutputInfo(ref, outputIndex(ref).asInstanceOf[AssetOutput], outputType)
          )
        )
      }
      .getOrElse(AVector.empty)
  }

  def getOutput(outputRef: AssetOutputRef): Option[TxOutput] = readOnly {
    outputIndex.get(outputRef)
  }

  def clear(): Unit = {
    inputIndex.clear()
    outputIndex.clear()
    addressIndex.clear()
  }
}

object TxIndexes {
  def emptySharedPool(mainGroup: GroupIndex)(implicit groupConfig: GroupConfig): TxIndexes =
    TxIndexes(
      mainGroup,
      mutable.HashSet.empty,
      mutable.HashMap.empty,
      mutable.HashMap.empty,
      SharedPoolOutput
    )

  def emptyPendingPool(mainGroup: GroupIndex)(implicit groupConfig: GroupConfig): TxIndexes =
    TxIndexes(
      mainGroup,
      mutable.HashSet.empty,
      mutable.HashMap.empty,
      mutable.HashMap.empty,
      PendingPoolOutput
    )
}
