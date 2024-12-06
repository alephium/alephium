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
import org.alephium.protocol.vm.nodeindexes.NodeIndexesStorage.TxIdBlockHashes
import org.alephium.protocol.vm.subcontractindex.CachedSubContractIndex

// format: off
final case class CachedNodeIndexes(
    logStorageCache: CachedLog,
    txOutputRefIndexCache: TxOutputRefIndexStorage[CachedKVStorage[TxOutputRef.Key, TxIdBlockHashes]],
    subContractIndexCache: Option[CachedSubContractIndex]
) {
  def persist(): IOResult[NodeIndexesStorage] = {
    for {
      logStorage <- logStorageCache.persist()
      txOutputRefIndexStorage <- txOutputRefIndexCache.value match {
        case Some(cache) => cache.persist().map(storage => TxOutputRefIndexStorage(Some(storage)))
        case None        => Right(TxOutputRefIndexStorage(None))
      }
      subContractIndexStorage <- subContractIndexCache match {
        case Some(cache) => cache.persist().map(Some(_))
        case None        => Right(None)
      }
    } yield NodeIndexesStorage(
      logStorage,
      txOutputRefIndexStorage,
      subContractIndexStorage
    )
  }

  def staging(): StagingNodeIndexes = {
    new StagingNodeIndexes(
      logStorageCache.staging(),
      TxOutputRefIndexStorage(txOutputRefIndexCache.value.map(_.staging())),
      subContractIndexCache.map(_.staging())
    )
  }
}
// format: on
object CachedNodeIndexes {
  @inline def from(nodeIndexesStorage: NodeIndexesStorage): CachedNodeIndexes = {
    new CachedNodeIndexes(
      CachedLog.from(nodeIndexesStorage.logStorage),
      TxOutputRefIndexStorage(
        nodeIndexesStorage.txOutputRefIndexStorage.value.map(CachedKVStorage.from)
      ),
      nodeIndexesStorage.subContractIndexStorage.map(CachedSubContractIndex.from)
    )
  }
}
