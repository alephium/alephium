package org.alephium.flow.io

import org.rocksdb._

import org.alephium.protocol.util.KeyValueStorage
import org.alephium.serde._

object RocksDBKeyValueStorage {
  import RocksDBSource.Settings

  def apply[K: Serializer, V: Serde](storage: RocksDBSource,
                                     cf: RocksDBSource.ColumnFamily): KeyValueStorage[K, V] =
    apply(storage, cf, Settings.writeOptions, Settings.readOptions)

  def apply[K: Serializer, V: Serde](storage: RocksDBSource,
                                     cf: RocksDBSource.ColumnFamily,
                                     writeOptions: WriteOptions): KeyValueStorage[K, V] =
    apply(storage, cf, writeOptions, Settings.readOptions)

  def apply[K: Serializer, V: Serde](storage: RocksDBSource,
                                     cf: RocksDBSource.ColumnFamily,
                                     writeOptions: WriteOptions,
                                     readOptions: ReadOptions): KeyValueStorage[K, V] =
    new RocksDBKeyValueStorage(storage, cf, writeOptions, readOptions)
}

class RocksDBKeyValueStorage[K, V](
    storage: RocksDBSource,
    cf: RocksDBSource.ColumnFamily,
    val writeOptions: WriteOptions,
    val readOptions: ReadOptions
)(implicit val keySerializer: Serializer[K], val valueSerde: Serde[V])
    extends KeyValueStorage[K, V]
    with RocksDBColumn {
  protected val db: RocksDB                = storage.db
  protected val handle: ColumnFamilyHandle = storage.handle(cf)
}
