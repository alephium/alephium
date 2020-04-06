package org.alephium.flow.io

import RocksDBSource.ColumnFamily
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.BlockHeader

object BlockHeaderStorage extends RocksDBKeyValueCompanion[BlockHeaderStorage] {
  def apply(storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): BlockHeaderStorage = {
    new BlockHeaderStorage(storage, cf, writeOptions, readOptions)
  }
}

class BlockHeaderStorage(
    val storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Hash, BlockHeader](storage, cf, writeOptions, readOptions) {
  def put(blockHeader: BlockHeader): IOResult[Unit] = put(blockHeader.hash, blockHeader)

  def putUnsafe(blockHeader: BlockHeader): Unit = putUnsafe(blockHeader.hash, blockHeader)

  def exists(blockHeader: BlockHeader): IOResult[Boolean] = exists(blockHeader.hash)

  def existsUnsafe(blockHeader: BlockHeader): Boolean = existsUnsafe(blockHeader.hash)

  def delete(blockHeader: BlockHeader): IOResult[Unit] = delete(blockHeader.hash)

  def deleteUnsafe(blockHeader: BlockHeader): Unit = deleteUnsafe(blockHeader.hash)
}
