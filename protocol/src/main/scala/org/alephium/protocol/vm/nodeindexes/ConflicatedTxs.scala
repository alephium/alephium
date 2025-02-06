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

package org.alephium.protocol.vm.nodeindexes

import org.alephium.io.{CachedKVStorage, IOResult, KeyValueStorage}
import org.alephium.protocol.model.{BlockHash, TransactionId}
import org.alephium.serde._
import org.alephium.util.AVector

final case class ConflictedTxsPerBlock(block: BlockHash, txs: AVector[TransactionId])
object ConflictedTxsPerBlock {
  implicit val serde: Serde[ConflictedTxsPerBlock] =
    Serde.forProduct2(ConflictedTxsPerBlock.apply, t => (t.block, t.txs))
}
final case class ConflictedTxsSource(
    intraBlock: BlockHash,
    txs: AVector[TransactionId]
)
object ConflictedTxsSource {
  implicit val serde: Serde[ConflictedTxsSource] =
    Serde.forProduct2(ConflictedTxsSource.apply, t => (t.intraBlock, t.txs))
}

// Storage for tracking conflicted transactions in blocks
// A transaction is conflicted when its inputs are already spent by another transaction
final case class ConflictedTxsStorage(
    // Maps an intra-block (checkpoint block) hash to a list of inter-group blocks containing conflicted transactions
    conflictedTxsPerIntraBlock: KeyValueStorage[BlockHash, AVector[ConflictedTxsPerBlock]],
    // Reverse index of the index above, mapping inter-group block hash to the checkpoint block hash and the conflicted transactions
    conflictedTxsReversedIndex: KeyValueStorage[BlockHash, AVector[ConflictedTxsSource]]
) {
  def cache(): CachedConflictedTxsStorage = {
    CachedConflictedTxsStorage(
      CachedKVStorage.from(conflictedTxsPerIntraBlock),
      CachedKVStorage.from(conflictedTxsReversedIndex)
    )
  }
}

final case class CachedConflictedTxsStorage(
    conflictedTxsPerIntraBlock: CachedKVStorage[BlockHash, AVector[ConflictedTxsPerBlock]],
    conflictedTxsReversedIndex: CachedKVStorage[BlockHash, AVector[ConflictedTxsSource]]
) {
  def persist(): IOResult[ConflictedTxsStorage] = {
    for {
      conflictedTxsPerIntraBlockStorage <- conflictedTxsPerIntraBlock.persist()
      conflictedTxsReversedIndexStorage <- conflictedTxsReversedIndex.persist()
    } yield ConflictedTxsStorage(
      conflictedTxsPerIntraBlockStorage,
      conflictedTxsReversedIndexStorage
    )
  }

  def addConflicts(
      checkpointBlock: BlockHash,
      block: BlockHash,
      txs: AVector[TransactionId]
  ): IOResult[Unit] = {
    val elem0 = ConflictedTxsPerBlock(block, txs)
    val update0 = conflictedTxsPerIntraBlock.getOpt(checkpointBlock).flatMap {
      case Some(conflicts) =>
        conflictedTxsPerIntraBlock
          .put(checkpointBlock, conflicts :+ elem0)
      case None => conflictedTxsPerIntraBlock.put(checkpointBlock, AVector(elem0))
    }
    val elem1 = ConflictedTxsSource(checkpointBlock, txs)
    val update1 = conflictedTxsReversedIndex.getOpt(block).flatMap {
      case Some(conflicts) =>
        conflictedTxsReversedIndex.put(block, conflicts :+ elem1)
      case None =>
        conflictedTxsReversedIndex.put(block, AVector(elem1))
    }
    for {
      _ <- update0
      _ <- update1
    } yield ()
  }
}
