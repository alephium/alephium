package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import scala.util.Random

import org.openjdk.jmh.annotations._
import org.rocksdb.{ColumnFamilyOptions, DBOptions, RocksDB}
import org.rocksdb.util.SizeUnit

import org.alephium.flow.io.{RocksDBColumn, RocksDBStorage}
import org.alephium.protocol.ALF.Hash
import org.alephium.util.Files

@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
// scalastyle:off
class RocksDBBench {
  import RocksDBStorage.{ColumnFamily, Compaction, Settings}

  {
    RocksDB.loadLibrary()
  }

  private val N      = 1000000
  private val tmpdir = Files.tmpDir

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def randomInsertAndLookup(db: RocksDBColumn): Unit = {
    val random = new Random(0)

    // Insert N
    val keys = (0 to N).map { _ =>
      val bytes = Hash.random.bytes
      db.put(bytes, bytes)
      bytes
    }

    def randomKey() = keys(random.nextInt(N))

    // Query N / 2
    (0 to N / 2).foreach { _ =>
      val key = randomKey()
      db.getRaw(key).right.toOption.get
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
    val id   = Random.alphanumeric.take(12).mkString
    val path = tmpdir.resolve(s"bench-$name-$id")

    val files = path.toFile.listFiles
    if (files != null) {
      files.foreach(_.delete)
    }

    val storage: RocksDBStorage =
      RocksDBStorage.openUnsafeWithOptions(path, databaseOptions, columnOptions)

    RocksDBColumn(storage, ColumnFamily.All)
  }

  def createDBForBudget(name: String,
                        compaction: Compaction,
                        memoryBudgetPerCol: Long): RocksDBColumn =
    createDB(name,
             Settings.databaseOptionsForBudget(compaction, memoryBudgetPerCol),
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
    val db: RocksDBColumn = createDBForBudget("ssd-128", Compaction.SSD, 128 * SizeUnit.MB)
    randomInsertAndLookup(db)
  }

  @Benchmark
  def ssdSettingsFor256(): Unit = {
    val db: RocksDBColumn = createDBForBudget("ssd-256", Compaction.SSD, 256 * SizeUnit.MB)
    randomInsertAndLookup(db)
  }

  @Benchmark
  def ssdSettingsFor512(): Unit = {
    val db: RocksDBColumn = createDBForBudget("ssd-512", Compaction.SSD, 512 * SizeUnit.MB)
    randomInsertAndLookup(db)
  }

  @Benchmark
  def hddSettings(): Unit = {
    val db: RocksDBColumn =
      createDB("hdd",
               Settings.databaseOptions(Compaction.HDD),
               Settings.columnOptions(Compaction.HDD))

    randomInsertAndLookup(db)
  }

  @Benchmark
  def hddSettingsFor128(): Unit = {
    val db: RocksDBColumn = createDBForBudget("hdd-128", Compaction.HDD, 128 * SizeUnit.MB)
    randomInsertAndLookup(db)
  }

  @Benchmark
  def hddSettingsFor256(): Unit = {
    val db: RocksDBColumn = createDBForBudget("hdd-256", Compaction.HDD, 256 * SizeUnit.MB)
    randomInsertAndLookup(db)
  }

  @Benchmark
  def hddSettingsFor512(): Unit = {
    val db: RocksDBColumn = createDBForBudget("hdd-512", Compaction.HDD, 512 * SizeUnit.MB)
    randomInsertAndLookup(db)
  }
}
