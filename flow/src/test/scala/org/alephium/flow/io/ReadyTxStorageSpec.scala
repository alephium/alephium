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

import org.alephium.flow.model.ReadyTxInfo
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.io.RocksDBSource
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.{Generators, Hash}
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}

class ReadyTxStorageSpec
    extends AlephiumSpec
    with StorageSpec[ReadyTxRocksDBStorage]
    with AlephiumConfigFixture {

  override val dbname: String = "ready-tx-storage-spec"
  override val builder: RocksDBSource => ReadyTxRocksDBStorage =
    source => ReadyTxRocksDBStorage(source, ColumnFamily.PendingTx)

  it should "exists/put/get/remove for tx id" in new Generators {
    forAll(hashGen, chainIndexGen) { (hash, chainIndex) =>
      val readyTxInfo = ReadyTxInfo(chainIndex, TimeStamp.now())
      storage.exists(hash) isE false
      storage.put(hash, readyTxInfo) isE ()
      storage.exists(hash) isE true
      storage.get(hash) isE readyTxInfo
      storage.delete(hash) isE ()
      storage.exists(hash) isE false
    }
  }

  trait Fixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val entrySize  = 30
    val currentTs  = TimeStamp.now()

    def genEntries(): AVector[(Hash, ReadyTxInfo)] = {
      AVector.fill(entrySize) {
        val hash        = Hash.generate
        val readyTxInfo = ReadyTxInfo(chainIndex, currentTs)
        hash -> readyTxInfo
      }
    }
  }

  it should "works for remove/put when iterate" in new Fixture {
    val entries0 = genEntries().toIterable.toMap
    entries0.foreach { case (hash, info) =>
      storage.put(hash, info) isE ()
    }
    val entries1 = genEntries()
    var index    = 0
    storage.iterate { (hash, info) =>
      entries0(hash) is info
      storage.delete(hash) isE ()
      val (newHash, newInfo) = entries1(index)
      storage.put(newHash, newInfo) isE ()
      index += 1
    }
    index is entrySize
    entries0.foreach { case (hash, _) =>
      storage.exists(hash) isE false
    }
    entries1.foreach { case (hash, info) =>
      storage.get(hash) isE info
    }
  }

  it should "remove all entries in storage" in new Fixture {
    val entries = genEntries()
    entries.foreach { case (hash, info) =>
      storage.put(hash, info) isE ()
      storage.get(hash) isE info
    }
    storage.clear() isE ()
    entries.foreach { case (hash, _) =>
      storage.exists(hash) isE false
    }
  }
}
