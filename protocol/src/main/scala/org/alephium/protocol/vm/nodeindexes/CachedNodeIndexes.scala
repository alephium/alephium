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

package org.alephium.protocol.vm.nodeindexes

import org.alephium.io.{CachedKVStorage, IOResult}
import org.alephium.protocol.model.TxOutputRef
import org.alephium.protocol.vm.event.CachedLog
import org.alephium.protocol.vm.nodeindexes.TxIdTxOutputLocators
import org.alephium.protocol.vm.subcontractindex.CachedSubContractIndex

final case class CachedNodeIndexes(
    logStorageCache: CachedLog,
    txOutputRefIndexCache: Option[CachedKVStorage[TxOutputRef.Key, TxIdTxOutputLocators]],
    subContractIndexCache: Option[CachedSubContractIndex],
    conflictedTxsStorageCache: CachedConflictedTxsStorage
) {
  def persist(): IOResult[NodeIndexesStorage] = {
    for {
      logStorage <- logStorageCache.persist()
      txOutputRefIndexStorage <- txOutputRefIndexCache match {
        case Some(cache) => cache.persist().map(Some(_))
        case None        => Right(None)
      }
      subContractIndexStorage <- subContractIndexCache match {
        case Some(cache) => cache.persist().map(Some(_))
        case None        => Right(None)
      }
      conflictedTxsStorage <- conflictedTxsStorageCache.persist()
    } yield NodeIndexesStorage(
      logStorage,
      txOutputRefIndexStorage,
      subContractIndexStorage,
      conflictedTxsStorage
    )
  }

  def staging(): StagingNodeIndexes = {
    new StagingNodeIndexes(
      logStorageCache.staging(),
      txOutputRefIndexCache.map(_.staging()),
      subContractIndexCache.map(_.staging())
    )
  }
}

object CachedNodeIndexes {
  @inline def from(nodeIndexesStorage: NodeIndexesStorage): CachedNodeIndexes = {
    new CachedNodeIndexes(
      CachedLog.from(nodeIndexesStorage.logStorage),
      nodeIndexesStorage.txOutputRefIndexStorage.map(CachedKVStorage.from),
      nodeIndexesStorage.subContractIndexStorage.map(CachedSubContractIndex.from),
      nodeIndexesStorage.conflictedTxsStorage.cache()
    )
  }
}
