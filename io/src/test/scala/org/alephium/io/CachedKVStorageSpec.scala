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

package org.alephium.io

import scala.collection.mutable

import org.alephium.crypto.Blake2b
import org.alephium.util.AlephiumSpec

class CachedKVStorageSpec extends AlephiumSpec with StorageFixture {
  trait Fixture {
    val dbSource = newDBStorage()
    val storage  = newDB[Blake2b, Int](dbSource, RocksDBSource.ColumnFamily.All)
  }

  it should "not persist cached key" in new Fixture {
    val key = Blake2b.random
    storage.put(key, 1) isE ()
    val cachedStorage = CachedKVStorage.from(storage)
    cachedStorage.get(key) isE 1
    cachedStorage.caches(key) is Cached(1)

    val accumulator = mutable.Map.empty[Blake2b, Int]
    CachedKVStorage.accumulateUpdates(accumulator.update, cachedStorage.caches)
    accumulator.isEmpty is true
  }

  it should "not be able to persist key removal" in new Fixture {
    val key = Blake2b.random
    storage.put(key, 1) isE ()
    val cachedStorage = CachedKVStorage.from(storage)
    cachedStorage.get(key) isE 1
    cachedStorage.caches(key) is Cached(1)

    cachedStorage.remove(key) isE ()
    cachedStorage.caches(key) is a[Removed[_]]

    assertThrows[RuntimeException](cachedStorage.persist())
  }

  it should "keep the order of inserted value" in new Fixture {
    val cachedStorage = CachedKVStorage.from(storage)
    (0 until 10).foreach { k =>
      cachedStorage.put(Blake2b.random, k) isE ()
    }
    cachedStorage.caches.values.toSeq is (0 until 10).map(k => Inserted(k))
  }
}
