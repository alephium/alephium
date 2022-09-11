// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.io

import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.io._
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.model.{Block, BlockHash}

trait BlockStorage extends KeyValueStorage[BlockHash, Block] {
  def put(block: Block): IOResult[Unit] = put(block.hash, block)

  def putUnsafe(block: Block): Unit = putUnsafe(block.hash, block)
}

object BlockRockDBStorage extends RocksDBKeyValueCompanion[BlockRockDBStorage] {
  def apply(
      storage: RocksDBSource,
      cf: ColumnFamily,
      writeOptions: WriteOptions,
      readOptions: ReadOptions
  ): BlockRockDBStorage = {
    new BlockRockDBStorage(storage, cf, writeOptions, readOptions)
  }
}

class BlockRockDBStorage(
    val storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[BlockHash, Block](storage, cf, writeOptions, readOptions)
    with BlockStorage {
  override def delete(key: BlockHash): IOResult[Unit] = ???

  override def deleteUnsafe(key: BlockHash): Unit = ???
}
