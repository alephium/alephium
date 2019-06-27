package org.alephium.benchmark

import scala.util.Random
import java.util.concurrent.TimeUnit

import org.rocksdb.{ColumnFamilyOptions, DBOptions, RocksDB}
import org.rocksdb.util.SizeUnit

import org.alephium.crypto.Keccak256
import org.alephium.flow.io.{RocksDBColumn, RocksDBStorage}
import org.alephium.util.Files
import org.openjdk.jmh.annotations._

import RocksDBStorage.{ColumnFamily, Compaction, Settings}

@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
// scalastyle:off
class RocksDBBench {
  {
    RocksDB.loadLibrary()
  }

  private val N      = 1000000
  private val tmpdir = Files.tmpDir

  def randomInsertAndLookup(db: RocksDBColumn): Unit = {
    val random = new Random(0)

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

  def createDB(name: String,
               databaseOptions: DBOptions,
               columnOptions: ColumnFamilyOptions): RocksDBColumn = {
    val path = tmpdir.resolve(s"bench-$name")

    val files = path.toFile.listFiles
    if (files != null) {
      files.foreach(_.delete)
    }

    val storage: RocksDBStorage =
      RocksDBStorage.openUnsafeWithOptions(path, databaseOptions, columnOptions)

    RocksDBColumn(storage, ColumnFamily.All, storage.readOptions)
  }

  def createDBForBudget(name: String, memoryBudgetPerCol: Long): RocksDBColumn =
    createDB(name,
             Settings.databaseOptionsForBudget(Compaction.SSD, memoryBudgetPerCol),
             Settings.columnOptionsForBudget(Compaction.SSD, memoryBudgetPerCol))

  @Benchmark
  def nothingSettings(): Unit = {
    val db: RocksDBColumn =
      createDB("nothing",
               new DBOptions()
                 .setCreateIfMissing(true)
                 .setCreateMissingColumnFamilies(true),
               new ColumnFamilyOptions)

    randomInsertAndLookup(db)
  }

  @Benchmark
  def ssdSettings(): Unit = {
    val db: RocksDBColumn =
      createDB("ssd",
               Settings.databaseOptions(Compaction.SSD),
               Settings.columnOptions(Compaction.SSD))

    randomInsertAndLookup(db)
  }

  @Benchmark
  def ssdSettingsFor128(): Unit = {
    val db: RocksDBColumn = createDBForBudget("ssd-128", 128 * SizeUnit.MB)
    randomInsertAndLookup(db)
  }

  @Benchmark
  def ssdSettingsFor256(): Unit = {
    val db: RocksDBColumn = createDBForBudget("ssd-256", 256 * SizeUnit.MB)
    randomInsertAndLookup(db)
  }

  @Benchmark
  def ssdSettingsFor512(): Unit = {
    val db: RocksDBColumn = createDBForBudget("ssd-512", 512 * SizeUnit.MB)
    randomInsertAndLookup(db)
  }
}
