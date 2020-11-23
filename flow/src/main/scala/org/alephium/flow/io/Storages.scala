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

import org.alephium.io.{IOResult, KeyValueSource, RocksDBKeyValueStorage, RocksDBSource}
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.io.SparseMerkleTrie.Node
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.WorldState
import org.alephium.util.AVector

object Storages {
  val isInitializedPostfix: Byte = 0
  val blockStatePostfix: Byte    = 1
  val trieHashPostfix: Byte      = 2
  val heightPostfix: Byte        = 3
  val chainStatePostfix: Byte    = 4

  def createUnsafe(rootPath: Path, dbFolder: String, writeOptions: WriteOptions)(
      implicit config: GroupConfig): Storages = {
    val db                = createRocksDBUnsafe(rootPath, dbFolder)
    val blockStorage      = BlockRockDBStorage(db, ColumnFamily.Block, writeOptions)
    val headerStorage     = BlockHeaderRockDBStorage(db, ColumnFamily.Header, writeOptions)
    val blockStateStorage = BlockStateRockDBStorage(db, ColumnFamily.All, writeOptions)
    val nodeStateStorage  = NodeStateRockDBStorage(db, ColumnFamily.All, writeOptions)
    val trieStorage       = RocksDBKeyValueStorage[Hash, Node](db, ColumnFamily.Trie, writeOptions)
    val trieHashStorage   = WorldStateRockDBStorage(trieStorage, db, ColumnFamily.All, writeOptions)
    val emptyWorldState   = WorldState.emptyPersisted(trieStorage)

    Storages(AVector(db),
             headerStorage,
             blockStorage,
             emptyWorldState,
             trieHashStorage,
             blockStateStorage,
             nodeStateStorage)
  }

  private def createRocksDBUnsafe(rootPath: Path, dbFolder: String): RocksDBSource = {
    val dbPath = rootPath.resolve(dbFolder)
    RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)
  }
}

final case class Storages(
    sources: AVector[KeyValueSource],
    headerStorage: BlockHeaderStorage,
    blockStorage: BlockStorage,
    emptyWorldState: WorldState,
    worldStateStorage: WorldStateStorage,
    blockStateStorage: BlockStateStorage,
    nodeStateStorage: NodeStateStorage
) extends KeyValueSource {
  def close(): IOResult[Unit] = sources.foreachE(_.close())

  def closeUnsafe(): Unit = sources.foreach(_.close())

  def dESTROY(): IOResult[Unit] = sources.foreachE(_.dESTROY())

  def dESTROYUnsafe(): Unit = sources.foreach(_.dESTROYUnsafe())
}
