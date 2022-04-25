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

class StagingSMTSpec extends AlephiumSpec {

  trait Fixture extends CachedTrieSpec.Fixture {
    val stagingCache   = cached.asInstanceOf[CachedSMT[Hash, Hash]].staging()
    val (key0, value0) = generateKV()

    stagingCache.put(key0, value0)
    stagingCache.get(key0) isE value0
    stagingCache.underlying.get(key0).leftValue.getMessage is
      IOError.keyNotFound(key0, "CachedKV.get").getMessage
  }

  it should "commit changes in cache" in new Fixture {
    stagingCache.commit()
    stagingCache.caches.isEmpty is true
    stagingCache.underlying.get(key0) isE value0
  }

  it should "rollback changes in cache" in new Fixture {
    stagingCache.rollback()
    stagingCache.caches.isEmpty is true
    stagingCache.get(key0).leftValue.getMessage is
      IOError.keyNotFound(key0, "CachedKV.get").getMessage
    stagingCache.underlying.get(key0).leftValue.getMessage is
      IOError.keyNotFound(key0, "CachedKV.get").getMessage
  }
}
