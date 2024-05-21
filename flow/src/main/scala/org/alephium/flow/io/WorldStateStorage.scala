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

import org.alephium.io._
import org.alephium.io.RocksDBSource.{ColumnFamily, ProdSettings}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.BlockHash
import org.alephium.protocol.vm.{ContractStorageImmutableState, WorldState}
import org.alephium.protocol.vm.event.LogStorage

trait WorldStateStorage extends KeyValueStorage[BlockHash, WorldState.Hashes] {
  val trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node]
  val trieImmutableStateStorage: KeyValueStorage[Hash, ContractStorageImmutableState]
  val logStorage: LogStorage

  override def storageKey(key: BlockHash): ByteString =
    key.bytes ++ ByteString(Storages.trieHashPostfix)

  def getPersistedWorldState(hash: BlockHash): IOResult[WorldState.Persisted] = {
    get(hash).map(_.toPersistedWorldState(trieStorage, trieImmutableStateStorage, logStorage))
  }

  def getWorldStateHash(hash: BlockHash): IOResult[Hash] = {
    get(hash).map(_.stateHash)
  }

  def getCachedWorldState(hash: BlockHash): IOResult[WorldState.Cached] = {
    get(hash).map(_.toCachedWorldState(trieStorage, trieImmutableStateStorage, logStorage))
  }

  def putTrie(hash: BlockHash, worldState: WorldState.Persisted): IOResult[Unit] = {
    put(hash, worldState.toHashes)
  }
}

object WorldStateRockDBStorage {
  def apply(
      trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
      trieImmutableStateStorage: KeyValueStorage[Hash, ContractStorageImmutableState],
      logStorage: LogStorage,
      storage: RocksDBSource,
      cf: ColumnFamily,
      writeOptions: WriteOptions
  ): WorldStateRockDBStorage = {
    new WorldStateRockDBStorage(
      trieStorage,
      trieImmutableStateStorage,
      logStorage,
      storage,
      cf,
      writeOptions,
      ProdSettings.readOptions
    )
  }
}

class WorldStateRockDBStorage(
    val trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
    val trieImmutableStateStorage: KeyValueStorage[Hash, ContractStorageImmutableState],
    val logStorage: LogStorage,
    storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[BlockHash, WorldState.Hashes](
      storage,
      cf,
      writeOptions,
      readOptions
    )
    with WorldStateStorage
