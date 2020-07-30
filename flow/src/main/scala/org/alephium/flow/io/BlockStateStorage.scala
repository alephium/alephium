package org.alephium.flow.io

import akka.util.ByteString
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.flow.model.BlockState
import org.alephium.io._
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.Hash

trait BlockStateStorage extends KeyValueStorage[Hash, BlockState] {
  override def storageKey(key: Hash): ByteString =
    key.bytes ++ ByteString(Storages.blockStatePostfix)
}

object BlockStateRockDBStorage extends RocksDBKeyValueCompanion[BlockStateRockDBStorage] {
  def apply(storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): BlockStateRockDBStorage = {
    new BlockStateRockDBStorage(storage, cf, writeOptions, readOptions)
  }
}

class BlockStateRockDBStorage(
    storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Hash, BlockState](storage, cf, writeOptions, readOptions)
    with BlockStateStorage
