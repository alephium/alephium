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

package org.alephium.protocol.vm

import org.alephium.crypto.Byte32
import org.alephium.io.{RocksDBSource, SparseMerkleTrie, StorageFixture}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{ContractId, TxOutputRef}
import org.alephium.protocol.vm.event.LogStorage
import org.alephium.protocol.vm.nodeindexes.NodeIndexesStorage
import org.alephium.protocol.vm.nodeindexes.TxIdTxOutputLocators
import org.alephium.protocol.vm.subcontractindex._
import org.alephium.serde.{avectorSerde, eitherSerde, intSerde}
import org.alephium.util.AVector

trait VMFactory extends StorageFixture {
  lazy val cachedWorldState: WorldState.Cached = {
    val storage = newDBStorage()
    val trieDb  = newDB[Hash, SparseMerkleTrie.Node](storage, RocksDBSource.ColumnFamily.Trie)
    val trieImmutableStateStorage =
      newDB[Hash, ContractStorageImmutableState](storage, RocksDBSource.ColumnFamily.Trie)
    val logDb        = newDB[LogStatesId, LogStates](storage, RocksDBSource.ColumnFamily.Log)
    val logRefDb     = newDB[Byte32, AVector[LogStateRef]](storage, RocksDBSource.ColumnFamily.Log)
    val logCounterDb = newDB[ContractId, Int](storage, RocksDBSource.ColumnFamily.LogCounter)
    val logStorage   = LogStorage(logDb, logRefDb, logCounterDb)
    val txOutputRefIndexStorage =
      Some(
        newDB[TxOutputRef.Key, TxIdTxOutputLocators](
          storage,
          RocksDBSource.ColumnFamily.TxOutputRefIndex
        )
      )
    val parentContractIndexStorage =
      newDB[ContractId, ContractId](storage, RocksDBSource.ColumnFamily.ParentContract)
    val subContractIndexStateStorage = newDB[SubContractIndexStateId, SubContractIndexState](
      storage,
      RocksDBSource.ColumnFamily.SubContract
    )
    val subContractIndexCounterStorage =
      newDB[ContractId, Int](storage, RocksDBSource.ColumnFamily.SubContractCounter)
    val subContractIndexStorage = SubContractIndexStorage(
      parentContractIndexStorage,
      subContractIndexStateStorage,
      subContractIndexCounterStorage
    )
    val nodeIndexesStorage = NodeIndexesStorage(
      logStorage,
      txOutputRefIndexStorage,
      Some(subContractIndexStorage)
    )

    WorldState.emptyCached(trieDb, trieImmutableStateStorage, nodeIndexesStorage)
  }
}
