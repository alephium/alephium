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
import org.alephium.util.AVector

final case class TxIndexes(
    mainGroup: GroupIndex,
    inputIndex: mutable.HashMap[AssetOutputRef, TransactionTemplate],
    outputIndex: mutable.HashMap[AssetOutputRef, (AssetOutput, TransactionTemplate)],
    addressIndex: mutable.HashMap[LockupScript, mutable.ArrayBuffer[AssetOutputRef]],
    outputType: OutputType
)(implicit groupConfig: GroupConfig) {
  def add[T](
      transaction: TransactionTemplate,
      sideEffect: TransactionTemplate => T
  ): (Option[mutable.ArrayBuffer[T]], Option[mutable.ArrayBuffer[T]]) = {
    val outputRefs = transaction.assetOutputRefs
    val parents    = getParentTxs(transaction, sideEffect)
    val children   = getChildTxs(outputRefs, sideEffect)

    transaction.unsigned.inputs.foreach(input => inputIndex.addOne(input.outputRef -> transaction))
    addRelevantOutputs(transaction, outputRefs)

    parents -> children
  }

  def addXGroupTx[T](
      transaction: TransactionTemplate,
      sideEffect: TransactionTemplate => T
  ): Option[mutable.ArrayBuffer[T]] = {
    val outputRefs = transaction.assetOutputRefs
    val children   = getChildTxs(outputRefs, sideEffect)
    addRelevantOutputs(transaction, outputRefs)
    children
  }

  private def addRelevantOutputs(
      transaction: TransactionTemplate,
      outputRefs: AVector[AssetOutputRef]
  ): Unit = {
    transaction.unsigned.fixedOutputs.foreachWithIndex { case (output, index) =>
      val outputRef = outputRefs(index)
      if (output.toGroup == mainGroup) {
        outputIndex.addOne(outputRef -> (output, transaction))
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

  def remove(transaction: TransactionTemplate): Unit = {
    _remove(transaction)
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

  def isSpent(asset: AssetOutputRef): Boolean = {
    inputIndex.contains(asset)
  }

  def isDoubleSpending(tx: TransactionTemplate): Boolean = {
    tx.unsigned.inputs.exists(txInput => isSpent(txInput.outputRef))
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def getRelevantUtxos(lockupScript: LockupScript): AVector[AssetOutputInfo] = {
    addressIndex
      .get(lockupScript)
      .map { refs =>
        AVector.from(
          refs.view.map(ref => AssetOutputInfo(ref, outputIndex(ref)._1, outputType))
        )
      }
      .getOrElse(AVector.empty)
  }

  def clear(): Unit = {
    inputIndex.clear()
    outputIndex.clear()
    addressIndex.clear()
  }

  @inline private def getParentTxs[T](
      tx: TransactionTemplate,
      sideEffect: TransactionTemplate => T
  ): Option[mutable.ArrayBuffer[T]] = {
    var result: Option[mutable.ArrayBuffer[T]] = None
    tx.unsigned.inputs.foreach { input =>
      outputIndex.get(input.outputRef).foreach { case (_, tx) =>
        result match {
          case None         => result = Some(mutable.ArrayBuffer(sideEffect(tx)))
          case Some(buffer) => buffer.append(sideEffect(tx))
        }
      }
    }
    result
  }

  @inline private def getChildTxs[T](
      outputRefs: AVector[AssetOutputRef],
      sideEffect: TransactionTemplate => T
  ): Option[mutable.ArrayBuffer[T]] = {
    var result: Option[mutable.ArrayBuffer[T]] = None
    outputRefs.foreach { outputRef =>
      inputIndex.get(outputRef).foreach { tx =>
        result match {
          case None         => result = Some(mutable.ArrayBuffer(sideEffect(tx)))
          case Some(buffer) => buffer.append(sideEffect(tx))
        }
      }
    }
    result
  }
}

object TxIndexes {
  def emptyMemPool(mainGroup: GroupIndex)(implicit groupConfig: GroupConfig): TxIndexes =
    TxIndexes(
      mainGroup,
      mutable.HashMap.empty,
      mutable.HashMap.empty,
      mutable.HashMap.empty,
      MemPoolOutput
    )
}
