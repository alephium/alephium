package org.alephium.flow.platform

import java.nio.file.Path

import org.rocksdb.WriteOptions

import org.alephium.flow.io._
import org.alephium.flow.io.RocksDBSource.ColumnFamily
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.config.GroupConfig

trait PlatformIO {
  def blockStorage: BlockStorage

  def headerStorage: BlockHeaderStorage

  def nodeStateStorage: NodeStateStorage

  def emptyTrie: MerklePatriciaTrie

  def txPoolCapacity: Int
}

object PlatformIO {
  def init(rootPath: Path, dbFolder: String, dbName: String, writeOptions: WriteOptions)(
      implicit config: GroupConfig)
    : (BlockStorage, BlockHeaderStorage, NodeStateStorage, MerklePatriciaTrie) = {
    val blockStorage: BlockStorage = BlockStorage.createUnsafe(rootPath)
    val dbStorage = {
      val dbPath = {
        val path = rootPath.resolve(dbFolder)
        IOUtils.createDirUnsafe(path)
        path
      }
      val path = dbPath.resolve(dbName)
      RocksDBSource.openUnsafe(path, RocksDBSource.Compaction.HDD)
    }
    val headerStorage    = BlockHeaderStorage(dbStorage, ColumnFamily.All, writeOptions)
    val nodeStateStorage = NodeStateStorage(dbStorage, ColumnFamily.All, writeOptions)
    val emptyTrie =
      MerklePatriciaTrie.createStateTrie(
        RocksDBKeyValueStorage(dbStorage, ColumnFamily.Trie, writeOptions))

    (blockStorage, headerStorage, nodeStateStorage, emptyTrie)
  }
}
