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

package org.alephium.flow.io

import scala.util.Random

import org.alephium.flow.model.PersistedTxId
import org.alephium.io.RocksDBSource
import org.alephium.protocol.model.{NoIndexModelGenerators, TransactionTemplate}
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}

class PendingTxStorageSpec
    extends AlephiumSpec
    with NoIndexModelGenerators
    with StorageSpec[PendingTxRocksDBStorage] {
  import RocksDBSource.ColumnFamily

  override val dbname: String = "pending-tx-storage-spec"
  override val builder: RocksDBSource => PendingTxRocksDBStorage =
    source => PendingTxRocksDBStorage(source, ColumnFamily.PendingTx)

  it should "exists/put/get/remove for tx" in {
    val currentTs = TimeStamp.now()
    forAll(transactionGen()) { transaction =>
      val tx = transaction.toTemplate
      val id = PersistedTxId(currentTs, tx.id)
      storage.exists(id) isE false
      storage.put(id, tx) isE ()
      storage.exists(id) isE true
      storage.get(id) isE tx
      storage.remove(id) isE ()
      storage.exists(id) isE false
    }
  }

  trait Fixture {
    val currentTs = TimeStamp.now()
    val txsSize   = 30
    val txs = AVector.tabulate(txsSize) { idx =>
      val ts = currentTs.plusSecondsUnsafe(idx.toLong)
      genPendingTx(ts)
    }

    def genPendingTx(ts: TimeStamp): (PersistedTxId, TransactionTemplate) = {
      val tx = transactionGen().sample.get.toTemplate
      val id = PersistedTxId(ts, tx.id)
      (id, tx)
    }

    def addTxs(txs: AVector[(PersistedTxId, TransactionTemplate)]) = {
      Random.shuffle(txs.toIterable).foreach { case (id, tx) =>
        storage.put(id, tx) isE ()
        storage.get(id) isE tx
      }
    }
  }

  it should "iterate pending tx in order" in new Fixture {
    addTxs(txs)
    var index = 0
    storage.iterate { (txId, tx) =>
      txs(index) is (txId -> tx)
      index += 1
    }
    index is txsSize
    storage.size() is txsSize
  }

  it should "works for remove/put when iterate" in new Fixture {
    addTxs(txs)

    val pendingTxs = AVector.fill(txsSize)(genPendingTx(currentTs))
    var index      = 0
    storage.iterate { (txId, _) =>
      storage.remove(txId) isE ()
      val (newTxId, newTx) = pendingTxs(index)
      storage.put(newTxId, newTx) isE ()
      index += 1
    }

    index is txsSize
    txs.foreach { case (txId, _) =>
      storage.exists(txId) isE false
    }
    pendingTxs.foreach { case (txId, tx) =>
      storage.get(txId) isE tx
    }
  }

  it should "replace old tx id with new tx id" in new Fixture {
    val (oldTxId, tx) = genPendingTx(currentTs)
    val newTxId       = oldTxId.copy(timestamp = currentTs.plusSecondsUnsafe(1))
    storage.put(oldTxId, tx) isE ()
    storage.get(oldTxId) isE tx
    storage.exists(newTxId) isE false
    storage.replace(oldTxId, newTxId, tx) isE ()
    storage.get(newTxId) isE tx
    storage.exists(oldTxId) isE false
  }
}
