package org.alephium.flow.io

import RocksDBSource.ColumnFamily
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.io.IOResult
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.BlockHeader
import org.alephium.protocol.util.KeyValueStorage

trait BlockHeaderStorage extends KeyValueStorage[Hash, BlockHeader] {
  def put(blockHeader: BlockHeader): IOResult[Unit] = put(blockHeader.hash, blockHeader)

  def putUnsafe(blockHeader: BlockHeader): Unit = putUnsafe(blockHeader.hash, blockHeader)

  def exists(blockHeader: BlockHeader): IOResult[Boolean] = exists(blockHeader.hash)

  def existsUnsafe(blockHeader: BlockHeader): Boolean = existsUnsafe(blockHeader.hash)

  def delete(blockHeader: BlockHeader): IOResult[Unit] = delete(blockHeader.hash)

  def deleteUnsafe(blockHeader: BlockHeader): Unit = deleteUnsafe(blockHeader.hash)
}

object BlockHeaderRockDBStorage extends RocksDBKeyValueCompanion[BlockHeaderRockDBStorage] {
  def apply(storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): BlockHeaderRockDBStorage = {
    new BlockHeaderRockDBStorage(storage, cf, writeOptions, readOptions)
  }
}

class BlockHeaderRockDBStorage(
    val storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Hash, BlockHeader](storage, cf, writeOptions, readOptions)
    with BlockHeaderStorage {}
