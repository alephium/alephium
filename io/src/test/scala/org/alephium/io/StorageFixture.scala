package org.alephium.io

import org.scalatest.Assertion

import org.alephium.crypto.Keccak256
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.serde.{Serde, Serializer}
import org.alephium.util.{AlephiumSpec, Files}

trait StorageFixture extends AlephiumSpec {
  private lazy val tmpdir = Files.tmpDir
  private lazy val dbname = s"test-db-${Keccak256.generate.toHexString}"
  private lazy val dbPath = tmpdir.resolve(dbname)

  private lazy val storage = RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)

  def newDB[K: Serializer, V: Serde]: KeyValueStorage[K, V] =
    RocksDBKeyValueStorage[K, V](storage, ColumnFamily.All)

  protected def postTest(): Assertion = {
    storage.dESTROY().isRight is true
  }
}
