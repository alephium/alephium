package org.alephium.flow.io

import akka.util.ByteString
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.io._
import org.alephium.io.RocksDBSource.{ColumnFamily, Settings}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.vm.WorldState

trait WorldStateStorage extends KeyValueStorage[Hash, WorldState.Hashes] {
  val trieStorage: KeyValueStorage[Hash, MerklePatriciaTrie.Node]

  override def storageKey(key: Hash): ByteString = key.bytes ++ ByteString(Storages.trieHashPostfix)

  def getPersistedWorldState(hash: Hash): IOResult[WorldState.Persisted] = {
    get(hash).map(_.toPersistedWorldState(trieStorage))
  }

  def getCachedWorldState(hash: Hash): IOResult[WorldState.Cached] = {
    get(hash).map(_.toCachedWorldState(trieStorage))
  }

  def putTrie(hash: Hash, worldState: WorldState): IOResult[Unit] = {
    worldState.persist.flatMap(state => put(hash, state.toHashes))
  }
}

object WorldStateRockDBStorage {
  def apply(trieStorage: KeyValueStorage[Hash, MerklePatriciaTrie.Node],
            storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions): WorldStateRockDBStorage = {
    new WorldStateRockDBStorage(trieStorage, storage, cf, writeOptions, Settings.readOptions)
  }

  def apply(trieStorage: KeyValueStorage[Hash, MerklePatriciaTrie.Node],
            storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): WorldStateRockDBStorage = {
    new WorldStateRockDBStorage(trieStorage, storage, cf, writeOptions, readOptions)
  }
}

class WorldStateRockDBStorage(
    val trieStorage: KeyValueStorage[Hash, MerklePatriciaTrie.Node],
    storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Hash, WorldState.Hashes](storage, cf, writeOptions, readOptions)
    with WorldStateStorage
