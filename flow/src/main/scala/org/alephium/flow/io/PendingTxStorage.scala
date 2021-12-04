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

import org.alephium.flow.model.PersistedTxId
import org.alephium.io._
import org.alephium.protocol.model.TransactionTemplate
import org.alephium.storage.{ColumnFamily, KeyValueSource, KeyValueStorage}

object PendingTxStorage {
  def apply(storage: KeyValueSource, cf: ColumnFamily): PendingTxStorage =
    new PendingTxStorage(storage, cf)
}

class PendingTxStorage(val storage: KeyValueSource, cf: ColumnFamily)
    extends KeyValueStorage[PersistedTxId, TransactionTemplate](storage, cf) {

  def replace(
      oldId: PersistedTxId,
      newId: PersistedTxId,
      tx: TransactionTemplate
  ): IOResult[Unit] = {
    assume(oldId.hash == newId.hash)
    IOUtils.tryExecute {
      deleteUnsafe(oldId)
      putUnsafe(newId, tx)
    }
  }
}
