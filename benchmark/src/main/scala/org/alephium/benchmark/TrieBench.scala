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

package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import org.alephium.cache.SparseMerkleTrie
import org.alephium.cache.SparseMerkleTrie.Node
import org.alephium.protocol.Hash
import org.alephium.storage.{ColumnFamily, KeyValueSource, KeyValueStorage, StorageInitializer}
import org.alephium.storage.setting.StorageSetting
import org.alephium.util.Files

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class TrieBench {

  private val tmpdir = Files.tmpDir
  private val dbname = "trie"
  private val dbPath = tmpdir.resolve(dbname)

  val dbStorage: KeyValueSource = {
    val files = dbPath.toFile.listFiles
    if (files != null) {
      files.foreach(_.delete)
    }

    StorageInitializer.openUnsafe(
      path = dbPath,
      setting = StorageSetting.syncWriteHDD(),
      columns = ColumnFamily.values.toIterable
    )
  }
  val db: KeyValueStorage[Hash, Node]    = KeyValueStorage(dbStorage, ColumnFamily.Trie)
  val trie: SparseMerkleTrie[Hash, Hash] = SparseMerkleTrie.build(db, Hash.zero, Hash.zero)
  val genesisHash: Hash                  = trie.rootHash

  @Benchmark
  def randomInsert(): Unit = {
    val keys = Array.tabulate(1 << 10) { _ =>
      val key  = Hash.random.bytes
      val data = Hash.random.bytes
      trie.putRaw(key, data)
      key
    }
    keys.foreach(trie.removeRaw)
    assume(trie.rootHash == genesisHash)
  }
}
