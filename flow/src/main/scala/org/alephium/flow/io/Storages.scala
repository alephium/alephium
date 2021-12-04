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

import org.alephium.cache.SparseMerkleTrie.Node
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.WorldState
import org.alephium.storage._
import org.alephium.storage.setting.StorageSetting
import org.alephium.util.AVector

object Storages {
  val isInitializedPostfix: Byte = 0
  val blockStatePostfix: Byte    = 1
  val trieHashPostfix: Byte      = 2
  val heightPostfix: Byte        = 3
  val chainStatePostfix: Byte    = 4
  val dbVersionPostfix: Byte     = 5
  val bootstrapInfoPostFix: Byte = 6

  def createUnsafe(rootPath: Path, dbFolder: String, settings: StorageSetting)(implicit
      config: GroupConfig
  ): Storages = {
    val db                = createStorageUnsafe(rootPath, dbFolder, settings)
    val blockStorage      = BlockStorage(db, ColumnFamily.Block)
    val headerStorage     = BlockHeaderStorage(db, ColumnFamily.Header)
    val blockStateStorage = BlockStateStorage(db, ColumnFamily.All)
    val txStorage         = TxStorage(db, ColumnFamily.All)
    val nodeStateStorage  = NodeStateStorage(db, ColumnFamily.All)
    val trieStorage       = KeyValueStorage[Hash, Node](db, ColumnFamily.Trie)
    val trieHashStorage   = WorldStateStorage(trieStorage, db, ColumnFamily.All)
    val emptyWorldState   = WorldState.emptyPersisted(trieStorage)
    val pendingTxStorage  = PendingTxStorage(db, ColumnFamily.PendingTx)
    val readyTxStorage    = ReadyTxStorage(db, ColumnFamily.ReadyTx)

    Storages(
      AVector(db),
      headerStorage,
      blockStorage,
      txStorage,
      emptyWorldState,
      trieHashStorage,
      blockStateStorage,
      nodeStateStorage,
      pendingTxStorage,
      readyTxStorage
    )
  }

  private def createStorageUnsafe(
      rootPath: Path,
      dbFolder: String,
      settings: StorageSetting
  ): KeyValueSource = {
    val dbPath = rootPath.resolve(dbFolder)
    StorageInitialiser.openUnsafe(dbPath, settings, ColumnFamily.values.toIterable)
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
    readyTxStorage: ReadyTxStorage
) extends KeyValueSourceDestroyable {

  def closeUnsafe(): Unit = sources.foreach(_.close())

  def dESTROYUnsafe(): Unit = sources.foreach(_.dESTROYUnsafe())
}
