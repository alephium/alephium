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

package org.alephium.protocol.vm.event

import org.alephium.crypto.Byte32
import org.alephium.io.{RocksDBSource, StorageFixture}
import org.alephium.protocol.model.ContractId
import org.alephium.protocol.vm.{LogStateRef, LogStates, LogStatesId}
import org.alephium.util.AVector

trait Fixture extends StorageFixture {
  def newLogStorage(storage: RocksDBSource): LogStorage = {
    val logDb        = newDB[LogStatesId, LogStates](storage, RocksDBSource.ColumnFamily.Log)
    val logRefDb     = newDB[Byte32, AVector[LogStateRef]](storage, RocksDBSource.ColumnFamily.Log)
    val logCounterDb = newDB[ContractId, Int](storage, RocksDBSource.ColumnFamily.LogCounter)
    LogStorage(logDb, logRefDb, logCounterDb)
  }

  def newCachedLog(storage: RocksDBSource): CachedLog = {
    CachedLog.from(newLogStorage(storage))
  }

  def newStagingLog(storage: RocksDBSource): StagingLog = {
    CachedLog.from(newLogStorage(storage)).staging()
  }
}
