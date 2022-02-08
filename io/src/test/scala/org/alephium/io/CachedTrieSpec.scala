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
import scala.util.Random

import org.scalacheck.Gen

import org.alephium.crypto.{Blake2b => Hash}
import org.alephium.util.AlephiumSpec
import org.alephium.util.Bytes.byteStringOrdering

class CachedTrieSpec extends AlephiumSpec {
  import CachedTrieSpec.Fixture

  it should "test random operations" in new Fixture {
    def testCreate() = {
      val (key, value) = generateKV()
      logs.addOne(key -> value)
      cached.get(key).isLeft is true
      cached.getOpt(key) isE None
      cached.put(key, value) isE ()
      cached.get(key) isE value
      cached.getOpt(key) isE Some(value)
    }

    def testRead() = {
      if (logs.nonEmpty) {
        val (key, value) = logs.head
        cached.get(key) isE value
        cached.getOpt(key) isE Some(value)
        cached.exists(key) isE true
      }
    }

    def testFalseRead() = {
      val (key, _) = generateKV()
      cached.get(key).swap isE a[IOError.KeyNotFound]
      cached.getOpt(key) isE None
      cached.exists(key) isE false
    }

    def testUpdate() = {
      if (logs.nonEmpty) {
        val (key, oldValue) = logs.head
        val (_, newValue)   = generateKV()
        logs.update(key, newValue)
        cached.get(key) isE oldValue
        cached.put(key, newValue) isE ()
        cached.get(key) isE newValue
      }
    }

    def testDelete() = {
      if (logs.nonEmpty) {
        val (key, _) = logs.head
        logs.remove(key)
        cached.exists(key) isE true
        cached.remove(key) isE ()
        cached.exists(key) isE false
      }
    }

    def testFalseDelete() = {
      val (key, _) = generateKV()
      cached.remove(key).swap isE a[IOError.KeyNotFound]
    }

    def switchMode() =
      cached match {
        case c: CachedSMT[Hash, Hash] =>
          if (Random.nextBoolean()) {
            val newTrie = c.persist().rightValue
            cached = CachedSMT.from(newTrie)
          } else {
            c.staging()
          }
        case c: StagingSMT[Hash, Hash] =>
          c.commit()
      }

    def finalTest() = {
      logs.foreach { case (key, value) =>
        cached.get(key) isE value
        cached.remove(key) isE ()
      }
      val newTrie = cached match {
        case c: CachedSMT[Hash, Hash] =>
          c.persist().rightValue
        case c: StagingSMT[Hash, Hash] =>
          c.commit()
          c.underlying.persist().rightValue
      }
      logs.foreach { case (key, _) =>
        newTrie.exists(key) isE false
      }
    }

    val gen = Gen.frequency[() => Any](
      (10, Gen.const(testCreate)),
      (10, Gen.const(testRead)),
      (5, Gen.const(testFalseRead)),
      (10, Gen.const(testUpdate)),
      (10, Gen.const(testDelete)),
      (5, Gen.const(testFalseDelete)),
      (5, Gen.const(switchMode))
    )
    (0 until 2000).foreach { _ => gen.sample.get.apply() }
    finalTest()
  }

  it should "test special case: insert -> staging -> update" in new Fixture {
    val cachedSMT      = CachedSMT.from(unCached)
    val (key0, value0) = generateKV()
    cachedSMT.put(key0, value0) isE ()
    cachedSMT.caches(key0) is Inserted(value0)

    val stagingSMT  = cachedSMT.staging()
    val (_, value1) = generateKV()
    stagingSMT.put(key0, value1) isE ()
    stagingSMT.caches(key0) is Updated(value1)

    stagingSMT.commit()
    stagingSMT.underlying.caches(key0) is Inserted(value1)
  }

  it should "test special case: insert -> staging -> remove" in new Fixture {
    val cachedSMT      = CachedSMT.from(unCached)
    val (key0, value0) = generateKV()
    cachedSMT.put(key0, value0) isE ()
    cachedSMT.caches(key0) is Inserted(value0)

    val stagingSMT = cachedSMT.staging()
    stagingSMT.remove(key0) isE ()
    stagingSMT.caches(key0) is a[Removed[_]]

    stagingSMT.commit()
    stagingSMT.underlying.caches.contains(key0) is false
  }

  it should "test special case: remove -> staging -> insert" in new Fixture {
    val (key0, value0) = generateKV()
    val (_, value1)    = generateKV()

    val cachedSMT = CachedSMT.from(unCached.put(key0, value0).rightValue)
    cachedSMT.remove(key0) isE ()
    cachedSMT.caches(key0) is a[Removed[_]]

    val stagingSMT = cachedSMT.staging()
    stagingSMT.put(key0, value1) isE ()
    stagingSMT.caches(key0) is Inserted(value1)

    stagingSMT.commit()
    stagingSMT.underlying.caches(key0) is Updated(value1)
  }
}

object CachedTrieSpec {
  implicit val ordering: Ordering[Hash] = Ordering.by(_.bytes)

  trait Fixture extends StorageFixture {
    val genesisKey   = Hash.zero
    val genesisValue = Hash.zero
    val storage      = newDBStorage()
    val db           = newDB[Hash, SparseMerkleTrie.Node](storage, RocksDBSource.ColumnFamily.All)
    val unCached     = SparseMerkleTrie.unsafe[Hash, Hash](db, genesisKey, genesisValue)

    var cached: MutableKV[Hash, Hash, Unit] = CachedSMT.from(unCached)

    val logs = mutable.SortedMap.empty[Hash, Hash]

    def generateKV(): (Hash, Hash) = {
      (Hash.random, Hash.random)
    }
  }
}
