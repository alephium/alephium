package org.alephium.io

import org.scalatest.Assertion

import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.serde.{Serde, Serializer}
import org.alephium.util.{AlephiumSpec, Files}

trait StorageFixture extends AlephiumSpec {
  private val tmpdir = Files.tmpDir
  private val dbname = "test-db"
  private val dbPath = tmpdir.resolve(dbname)

  private val storage = RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)

  def newDB[K: Serializer, V: Serde]: KeyValueStorage[K, V] =
    RocksDBKeyValueStorage[K, V](storage, ColumnFamily.All)

  protected def postTest(): Assertion = {
    storage.dESTROY().isRight is true
  }
}
