package org.alephium.flow.io

import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.flow.io.RocksDBSource.ColumnFamily
import org.alephium.flow.model.BlockState
import org.alephium.protocol.ALF.Hash

object BlockStateStorage extends RocksDBKeyValueCompanion[BlockStateStorage] {
  def apply(storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): BlockStateStorage = {
    new BlockStateStorage(storage, cf, writeOptions, readOptions)
  }
}

class BlockStateStorage(
    storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Hash, BlockState](storage, cf, writeOptions, readOptions)
