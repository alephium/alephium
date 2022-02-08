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
import org.alephium.protocol.vm.{LogStates, LogStatesId}

trait LogStorage extends KeyValueStorage[LogStatesId, LogStates] {
  def addLogStates(logStates: LogStates): IOResult[Unit]
  def getLogStates(logStatesId: LogStatesId): IOResult[Option[LogStates]]
}

object LogRocksDBStorage extends RocksDBKeyValueCompanion[LogRocksDBStorage] {
  override def apply(
      storage: RocksDBSource,
      cf: ColumnFamily,
      writeOptions: WriteOptions,
      readOptions: ReadOptions
  ): LogRocksDBStorage = {
    new LogRocksDBStorage(storage, cf, writeOptions, readOptions)
  }
}

class LogRocksDBStorage(
    val storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[LogStatesId, LogStates](
      storage,
      cf,
      writeOptions,
      readOptions
    )
    with LogStorage {
  override def addLogStates(logStates: LogStates): IOResult[Unit] = {
    put(logStates.id(), logStates)
  }

  override def getLogStates(logStatesId: LogStatesId): IOResult[Option[LogStates]] = {
    getOpt(logStatesId)
  }
}
