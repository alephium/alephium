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

import org.alephium.io.{RocksDBSource, StorageFixture}
import org.alephium.protocol.model.{BlockHash, TransactionId}
import org.alephium.serde.avectorSerde
import org.alephium.util.{AlephiumSpec, AVector}

class ConflictedTxsSpec extends AlephiumSpec with StorageFixture {
  it should "add conflicted txs" in {
    val dbSource = newDBStorage()
    val conflictedTxsPerIntraBlockStorage =
      newDB[BlockHash, AVector[ConflictedTxsPerBlock]](dbSource, RocksDBSource.ColumnFamily.All)
    val conflictedTxsReversedIndexStorage =
      newDB[BlockHash, AVector[ConflictedTxsSource]](dbSource, RocksDBSource.ColumnFamily.All)
    val storage =
      ConflictedTxsStorage(conflictedTxsPerIntraBlockStorage, conflictedTxsReversedIndexStorage)
        .cache()

    val checkpointBlock0 = BlockHash.generate
    val blockHash0       = BlockHash.generate
    val txs0             = AVector.fill(3)(TransactionId.generate)
    storage.addConflicts(checkpointBlock0, blockHash0, txs0) isE ()
    storage.conflictedTxsPerIntraBlock.get(checkpointBlock0) isE AVector(
      ConflictedTxsPerBlock(blockHash0, txs0)
    )
    storage.conflictedTxsReversedIndex.get(blockHash0) isE AVector(
      ConflictedTxsSource(checkpointBlock0, txs0)
    )

    val blockHash1 = BlockHash.generate
    val txs1       = AVector.fill(3)(TransactionId.generate)
    storage.addConflicts(checkpointBlock0, blockHash1, txs1) isE ()
    storage.conflictedTxsPerIntraBlock.get(checkpointBlock0) isE AVector(
      ConflictedTxsPerBlock(blockHash0, txs0),
      ConflictedTxsPerBlock(blockHash1, txs1)
    )
    storage.conflictedTxsReversedIndex.get(blockHash1) isE AVector(
      ConflictedTxsSource(checkpointBlock0, txs1)
    )

    val checkpointBlock1 = BlockHash.generate
    storage.addConflicts(checkpointBlock1, blockHash1, txs1) isE ()
    storage.conflictedTxsReversedIndex.get(blockHash1) isE AVector(
      ConflictedTxsSource(checkpointBlock0, txs1),
      ConflictedTxsSource(checkpointBlock1, txs1)
    )
    storage.conflictedTxsReversedIndex.get(blockHash0) isE AVector(
      ConflictedTxsSource(checkpointBlock0, txs0)
    )
  }
}
