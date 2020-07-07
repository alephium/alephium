package org.alephium.flow.io

import java.nio.file.Path

import org.rocksdb.WriteOptions

import org.alephium.io.{IOResult, KeyValueSource, RocksDBKeyValueStorage, RocksDBSource}
import org.alephium.io.MerklePatriciaTrie.Node
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.WorldState
import org.alephium.util.AVector

object Storages {
  val isInitializedPostfix: Byte = 0
  val blockStatePostfix: Byte    = 1
  val trieHashPostfix: Byte      = 2
  val heightPostfix: Byte        = 3
  val chainStatePostfix: Byte    = 4

  trait Config {
    def blockCacheCapacity: Int
  }

  def createUnsafe(
      rootPath: Path,
      dbFolder: String,
      blocksFolder: String,
      writeOptions: WriteOptions)(implicit config: GroupConfig with Config): Storages = {
    val blockStorage =
      BlockDiskStorage.createUnsafe(rootPath, blocksFolder, config.blockCacheCapacity)
    val db                = createRocksDBUnsafe(rootPath, dbFolder)
    val headerStorage     = BlockHeaderRockDBStorage(db, ColumnFamily.Header, writeOptions)
    val blockStateStorage = BlockStateRockDBStorage(db, ColumnFamily.All, writeOptions)
    val nodeStateStorage  = NodeStateRockDBStorage(db, ColumnFamily.All, writeOptions)
    val trieStorage       = RocksDBKeyValueStorage[Hash, Node](db, ColumnFamily.Trie, writeOptions)
    val trieHashStorage   = WorldStateRockDBStorage(trieStorage, db, ColumnFamily.All, writeOptions)
    val emptyWorldState   = WorldState.empty(trieStorage)

    Storages(AVector(db, blockStorage.source),
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
    trieHashStorage: WorldStateStorage,
    blockStateStorage: BlockStateStorage,
    nodeStateStorage: NodeStateStorage
) extends KeyValueSource {
  def close(): IOResult[Unit] = sources.foreachE(_.close())

  def closeUnsafe(): Unit = sources.foreach(_.close())

  def dESTROY(): IOResult[Unit] = sources.foreachE(_.dESTROY())

  def dESTROYUnsafe(): Unit = sources.foreach(_.dESTROYUnsafe())
}
