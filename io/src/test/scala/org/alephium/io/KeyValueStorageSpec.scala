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

import org.alephium.crypto.{Blake2b => Hash}
import org.alephium.util.AlephiumSpec

class KeyValueStorageSpec extends AlephiumSpec {

  it should "write in batch" in new StorageFixture {
    val storage = newDBStorage()
    val db      = newDB[Hash, Hash](storage, RocksDBSource.ColumnFamily.All)
    val pairs   = Seq.tabulate(1000)(_ => Hash.random -> Hash.random)
    db.putBatch { putAccumulate =>
      pairs.foreach { case (key, value) =>
        putAccumulate(key, value)
      }
    }
    pairs.foreach { case (key, value) =>
      db.get(key) isE value
    }
  }
}
