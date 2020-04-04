package org.alephium.flow.platform

import java.nio.file.Path

import org.rocksdb.WriteOptions

import org.alephium.flow.io._
import org.alephium.flow.io.RocksDBStorage.ColumnFamily
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.config.GroupConfig

trait PlatformIO {
  def disk: BlockStorage

  def headerDB: BlockHeaderStorage

  def nodeStateDB: NodeStateStorage

  def emptyTrie: MerklePatriciaTrie

  def txPoolCapacity: Int
}

object PlatformIO {
  def init(rootPath: Path, dbFolder: String, dbName: String, writeOptions: WriteOptions)(
      implicit config: GroupConfig)
    : (BlockStorage, BlockHeaderStorage, NodeStateStorage, MerklePatriciaTrie) = {
    val disk: BlockStorage = BlockStorage.createUnsafe(rootPath)
    val dbStorage = {
      val dbPath = {
        val path = rootPath.resolve(dbFolder)
        IOUtils.createDirUnsafe(path)
        path
      }
      val path = dbPath.resolve(dbName)
      RocksDBStorage.openUnsafe(path, RocksDBStorage.Compaction.HDD)
    }
    val headerDB    = BlockHeaderStorage(dbStorage, ColumnFamily.All, writeOptions)
    val nodeStateDB = NodeStateStorage(dbStorage, ColumnFamily.All, writeOptions)
    val emptyTrie =
      MerklePatriciaTrie.createStateTrie(
        RocksDBKeyValueStorage(dbStorage, ColumnFamily.Trie, writeOptions))

    (disk, headerDB, nodeStateDB, emptyTrie)
  }
}
