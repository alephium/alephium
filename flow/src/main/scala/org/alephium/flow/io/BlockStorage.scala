package org.alephium.flow.io

import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.io._
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.Hash
import org.alephium.protocol.model.Block

trait BlockStorage extends KeyValueStorage[Hash, Block] {
  def put(block: Block): IOResult[Unit] = put(block.hash, block)

  def putUnsafe(block: Block): Unit = putUnsafe(block.hash, block)
}

object BlockRockDBStorage extends RocksDBKeyValueCompanion[BlockRockDBStorage] {
  def apply(storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): BlockRockDBStorage = {
    new BlockRockDBStorage(storage, cf, writeOptions, readOptions)
  }
}

class BlockRockDBStorage(
    val storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Hash, Block](storage, cf, writeOptions, readOptions)
    with BlockStorage {
  override def delete(key: Hash): IOResult[Unit] = ???

  override def deleteUnsafe(key: Hash): Unit = ???
}
