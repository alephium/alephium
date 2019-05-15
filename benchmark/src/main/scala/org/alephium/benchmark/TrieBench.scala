package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.alephium.crypto.Keccak256
import org.alephium.flow.io.RocksDBStorage
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.util.Files
import org.openjdk.jmh.annotations._
import org.rocksdb.Options

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class TrieBench {

  private val tmpdir = Files.tmpDir
  private val dbname = "trie"
  private val dbPath = tmpdir.resolve(dbname)

  private val db = RocksDBStorage.openUnsafe(dbPath, new Options().setCreateIfMissing(true))

  val trie = MerklePatriciaTrie.create(db)

  @Benchmark
  def randomInsert(): Unit = {
    val keys = Array.tabulate(1 << 10) { _ =>
      val key  = Keccak256.random
      val data = Keccak256.random.bytes
      trie.put(key, data)
      key
    }
    keys.foreach(trie.remove)
    assert(trie.rootHash == MerklePatriciaTrie.genesisNode.hash)
  }
}
