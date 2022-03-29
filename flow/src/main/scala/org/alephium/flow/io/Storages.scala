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

import java.nio.file.Path

import org.rocksdb.WriteOptions

import org.alephium.io._
import org.alephium.io.RocksDBSource.ColumnFamily._
import org.alephium.io.SparseMerkleTrie.Node
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.{LogStates, LogStatesId, WorldState}
import org.alephium.util.AVector

object Storages {
  val isInitializedPostfix: Byte = 0
  val blockStatePostfix: Byte    = 1
  val trieHashPostfix: Byte      = 2
  val heightPostfix: Byte        = 3
  val chainStatePostfix: Byte    = 4
  val dbVersionPostfix: Byte     = 5
  val bootstrapInfoPostFix: Byte = 6

  def createUnsafe(rootPath: Path, storageDbFolder: String, writeOptions: WriteOptions)(implicit
      config: GroupConfig
  ): Storages = {
    val db                = createRocksDBUnsafe(rootPath, storageDbFolder)
    val blockStorage      = BlockRockDBStorage(db, Block, writeOptions)
    val headerStorage     = BlockHeaderRockDBStorage(db, Header, writeOptions)
    val blockStateStorage = BlockStateRockDBStorage(db, All, writeOptions)
    val txStorage         = TxRocksDBStorage(db, All, writeOptions)
    val nodeStateStorage  = NodeStateRockDBStorage(db, All, writeOptions)
    val trieStorage       = RocksDBKeyValueStorage[Hash, Node](db, Trie, writeOptions)
    val logStorage        = RocksDBKeyValueStorage[LogStatesId, LogStates](db, Log, writeOptions)
    val logCounterStorage = RocksDBKeyValueStorage[Hash, Int](db, LogCounter, writeOptions)
    val worldStateStorage =
      WorldStateRockDBStorage(trieStorage, logStorage, logCounterStorage, db, All, writeOptions)
    val emptyWorldState  = WorldState.emptyPersisted(trieStorage, logStorage, logCounterStorage)
    val pendingTxStorage = PendingTxRocksDBStorage(db, PendingTx, writeOptions)
    val readyTxStorage   = ReadyTxRocksDBStorage(db, ReadyTx, writeOptions)
    val brokerStorage    = BrokerRocksDBStorage(db, Broker, writeOptions)

    Storages(
      AVector(db),
      headerStorage,
      blockStorage,
      txStorage,
      emptyWorldState,
      worldStateStorage,
      blockStateStorage,
      nodeStateStorage,
      pendingTxStorage,
      readyTxStorage,
      brokerStorage,
      logStorage
    )
  }

  def createRocksDBUnsafe(rootPath: Path, dbFolder: String): RocksDBSource = {
    val dbPath = rootPath.resolve(dbFolder)
    RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)
  }
}

final case class Storages(
    sources: AVector[KeyValueSource],
    headerStorage: BlockHeaderStorage,
    blockStorage: BlockStorage,
    txStorage: TxStorage,
    emptyWorldState: WorldState.Persisted,
    worldStateStorage: WorldStateStorage,
    blockStateStorage: BlockStateStorage,
    nodeStateStorage: NodeStateStorage,
    pendingTxStorage: PendingTxStorage,
    readyTxStorage: ReadyTxStorage,
    brokerStorage: BrokerStorage,
    logStorage: KeyValueStorage[LogStatesId, LogStates]
) extends KeyValueSource {
  def close(): IOResult[Unit] = sources.foreachE(_.close())

  def closeUnsafe(): Unit = sources.foreach(_.close())

  def dESTROY(): IOResult[Unit] = sources.foreachE(_.dESTROY())

  def dESTROYUnsafe(): Unit = sources.foreach(_.dESTROYUnsafe())
}
