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

import org.alephium.flow.core.FlowUtils.AssetOutputInfo
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{AssetOutputRef, TransactionTemplate, TxOutput}
import org.alephium.protocol.vm.{LockupScript, WorldState}
import org.alephium.util.{AVector, EitherF, RWLock}

class PendingPool(
    val txs: mutable.HashMap[Hash, TransactionTemplate],
    val indexes: TxIndexes
) extends RWLock {
  def contains(txId: Hash): Boolean = readOnly {
    txs.contains(txId)
  }

  def isDoubleSpending(tx: TransactionTemplate): Boolean = readOnly {
    tx.unsigned.inputs.exists(input => indexes.isSpent(input.outputRef))
  }

  def add(tx: TransactionTemplate): Unit = writeOnly {
    if (!txs.contains(tx.id)) {
      txs.addOne(tx.id -> tx)
      indexes.add(tx)
    }
  }

  def remove(txs: AVector[TransactionTemplate]): Unit = writeOnly {
    txs.foreach(remove)
  }

  def remove(tx: TransactionTemplate): Unit = writeOnly {
    if (txs.contains(tx.id)) {
      txs.remove(tx.id)
      indexes.remove(tx)
    }
  }

  def getAll(): AVector[TransactionTemplate] = readOnly {
    AVector.from(txs.values)
  }

  def getRelevantUtxos(lockupScript: LockupScript): AVector[AssetOutputInfo] = readOnly {
    indexes.getRelevantUtxos(lockupScript)
  }

  def extractReadyTxs(worldState: WorldState.Persisted): IOResult[AVector[TransactionTemplate]] =
    readOnly {
      EitherF.foldTry(txs.values, AVector.empty[TransactionTemplate]) { case (acc, tx) =>
        worldState.containsAllInputs(tx).map {
          case true  => acc :+ tx
          case false => acc
        }
      }
    }

  // Left means the output is spent
  def getUtxo(outputRef: AssetOutputRef): Either[Unit, Option[TxOutput]] = {
    indexes.getUtxo(outputRef)
  }
}

object PendingPool {
  def empty: PendingPool = new PendingPool(mutable.HashMap.empty, TxIndexes.empty)
}
