package org.alephium.io

import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.io.RocksDBSource.Settings

trait RocksDBKeyValueCompanion[S <: RocksDBKeyValueStorage[_, _]] {
  def apply(storage: RocksDBSource, cf: RocksDBSource.ColumnFamily): S =
    apply(storage, cf, Settings.writeOptions, Settings.readOptions)

  def apply(storage: RocksDBSource, cf: RocksDBSource.ColumnFamily, writeOptions: WriteOptions): S =
    apply(storage, cf, writeOptions, Settings.readOptions)

  def apply(storage: RocksDBSource,
            cf: RocksDBSource.ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): S
}
