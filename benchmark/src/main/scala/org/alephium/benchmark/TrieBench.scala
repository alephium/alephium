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

import scala.util.Random

import akka.util.ByteString
import org.openjdk.jmh.annotations._

import org.alephium.io.{KeyValueStorage, RocksDBKeyValueStorage, RocksDBSource, SparseMerkleTrie}
import org.alephium.io.SparseMerkleTrie.Node
import org.alephium.protocol.Hash
import org.alephium.util.Files

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class TrieBench {
  import RocksDBSource.ColumnFamily

  def prepareTrie(): SparseMerkleTrie[Hash, Hash] = {
    val tmpdir = Files.tmpDir
    val dbname = s"trie-${Random.nextLong()}"
    val dbPath = tmpdir.resolve(dbname)

    val dbStorage: RocksDBSource = {
      val files = dbPath.toFile.listFiles
      if (files != null) {
        files.foreach(_.delete)
      }

      RocksDBSource.openUnsafe(dbPath)
    }
    val db: KeyValueStorage[Hash, Node] = RocksDBKeyValueStorage(dbStorage, ColumnFamily.Trie)
    SparseMerkleTrie.unsafe(db, Hash.zero, Hash.zero, SparseMerkleTrie.nodeCache(1000_000))
  }

  val data: Array[(ByteString, ByteString)] = Array.tabulate(1 << 20) { _ =>
    (Hash.random.bytes, Hash.random.bytes)
  }

  @Benchmark
  def randomInsert(): Unit = {
    var trie = prepareTrie()
    data.foreach { case (key, value) =>
      trie = trie.putRaw(key, value).getOrElse(???)
    }
    print(trie.rootHash.toHexString + "\n")
  }

  @Benchmark
  def randomInsertBatch(): Unit = {
    val trie = prepareTrie().inMemory()
    data.foreach { case (key, value) =>
      trie.putRaw(key, value)
    }
    trie.persistInBatch().map { trie =>
      print(trie.rootHash.toHexString + "\n")
    }
    ()
  }
}
