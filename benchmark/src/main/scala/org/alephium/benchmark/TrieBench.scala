package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import org.alephium.flow.io.{RocksDBKeyValueStorage, RocksDBSource}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.util.{KeyValueStorage, MerklePatriciaTrie}
import org.alephium.protocol.util.MerklePatriciaTrie.Node
import org.alephium.util.Files

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class TrieBench {
  import RocksDBSource.ColumnFamily

  private val tmpdir = Files.tmpDir
  private val dbname = "trie"
  private val dbPath = tmpdir.resolve(dbname)

  val dbStorage: RocksDBSource = {
    val files = dbPath.toFile.listFiles
    if (files != null) {
      files.foreach(_.delete)
    }

    RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.SSD)
  }
  val db: KeyValueStorage[Hash, Node]      = RocksDBKeyValueStorage(dbStorage, ColumnFamily.Trie)
  val trie: MerklePatriciaTrie[Hash, Hash] = MerklePatriciaTrie.build(db, Hash.zero, Hash.zero)
  val genesisHash: Hash                    = trie.rootHash

  @Benchmark
  def randomInsert(): Unit = {
    val keys = Array.tabulate(1 << 10) { _ =>
      val key  = Hash.random.bytes
      val data = Hash.random.bytes
      trie.putRaw(key, data)
      key
    }
    keys.foreach(trie.removeRaw)
    assert(trie.rootHash == genesisHash)
  }
}
