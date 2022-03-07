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
import org.alephium.io.RocksDBSource.{ColumnFamily, Settings}
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.vm.{LogStates, LogStatesId, WorldState}

trait WorldStateStorage extends KeyValueStorage[BlockHash, WorldState.Hashes] {
  val trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node]
  val logStorage: KeyValueStorage[LogStatesId, LogStates]

  override def storageKey(key: BlockHash): ByteString =
    key.bytes ++ ByteString(Storages.trieHashPostfix)

  def getPersistedWorldState(hash: BlockHash): IOResult[WorldState.Persisted] = {
    get(hash).map(_.toPersistedWorldState(trieStorage, logStorage))
  }

  def getWorldStateHash(hash: BlockHash): IOResult[Hash] = {
    get(hash).map(_.stateHash)
  }

  def getCachedWorldState(hash: BlockHash): IOResult[WorldState.Cached] = {
    get(hash).map(_.toCachedWorldState(trieStorage, logStorage))
  }

  def putTrie(hash: BlockHash, worldState: WorldState.Persisted): IOResult[Unit] = {
    put(hash, worldState.toHashes)
  }
}

object WorldStateRockDBStorage {
  def apply(
      trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
      logStorage: KeyValueStorage[LogStatesId, LogStates],
      storage: RocksDBSource,
      cf: ColumnFamily,
      writeOptions: WriteOptions
  ): WorldStateRockDBStorage = {
    new WorldStateRockDBStorage(
      trieStorage,
      logStorage,
      storage,
      cf,
      writeOptions,
      Settings.readOptions
    )
  }

  def apply(
      trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
      logStorage: KeyValueStorage[LogStatesId, LogStates],
      storage: RocksDBSource,
      cf: ColumnFamily,
      writeOptions: WriteOptions,
      readOptions: ReadOptions
  ): WorldStateRockDBStorage = {
    new WorldStateRockDBStorage(trieStorage, logStorage, storage, cf, writeOptions, readOptions)
  }
}

class WorldStateRockDBStorage(
    val trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
    val logStorage: KeyValueStorage[LogStatesId, LogStates],
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
