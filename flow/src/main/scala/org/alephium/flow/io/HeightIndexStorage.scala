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

import akka.util.ByteString
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.flow.io.HeightIndexStorage.hashesSerde
import org.alephium.io.{RocksDBKeyValueStorage, RocksDBSource}
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.model.{BlockHash, ChainIndex}
import org.alephium.serde._
import org.alephium.util.{AVector, Bytes}

object HeightIndexStorage {
  implicit val hashesSerde: Serde[AVector[BlockHash]] = avectorSerde[BlockHash]
}

class HeightIndexStorage(
    chainIndex: ChainIndex,
    storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Int, AVector[BlockHash]](storage, cf, writeOptions, readOptions) {
  private val postFix =
    ByteString(chainIndex.from.value.toByte, chainIndex.to.value.toByte, Storages.heightPostfix)

  override def storageKey(key: Int): ByteString = Bytes.from(key) ++ postFix
}
