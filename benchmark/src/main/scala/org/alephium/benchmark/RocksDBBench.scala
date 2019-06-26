package org.alephium.benchmark

import scala.util.Random
import java.util.concurrent.TimeUnit

import org.alephium.crypto.Keccak256
import org.alephium.flow.io.{RocksDBColumn, RocksDBStorage}
import org.alephium.util.Files
import org.openjdk.jmh.annotations._

import RocksDBStorage.ColumnFamily

@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
// scalastyle:off
class RocksDBBench {

  private val random = new Random(0)

  private val N = 1000000

  private val tmpdir = Files.tmpDir
  private val dbPath = tmpdir.resolve(RocksDBStorage.ColumnFamily.All.name)

  val dbStorage: RocksDBStorage = {
    val files = dbPath.toFile.listFiles
    if (files != null) {
      files.foreach(_.delete)
    }

    RocksDBStorage.openUnsafe(dbPath, RocksDBStorage.Compaction.SSD)
  }

  val db: RocksDBColumn = RocksDBColumn(dbStorage, ColumnFamily.All)

  @Benchmark
  def randomInsertAndLookup(): Unit = {
    // Insert N
    val keys = (0 to N).map { _ =>
      val bytes = Keccak256.random.bytes
      db.put(bytes, bytes)
      bytes
    }

    def randomKey() = keys(random.nextInt(N))

    // Query N / 2
    (0 to N / 2).foreach { _ =>
      val key = randomKey()
      db.getRaw(key).right.get
    }

    // Delete N / 2
    (0 to N / 2).foreach { _ =>
      val key = randomKey()
      db.delete(key)
    }

    // Query N / 2 (ignoring key which are not found)
    (0 to N / 2).foreach { _ =>
      val key = randomKey()
      db.getRaw(key)
    }
  }
}
