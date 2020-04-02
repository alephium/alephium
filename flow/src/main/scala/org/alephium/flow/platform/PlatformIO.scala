package org.alephium.flow.platform

import java.nio.file.Path

import org.rocksdb.WriteOptions

import org.alephium.flow.io._
import org.alephium.flow.io.RocksDBStorage.ColumnFamily
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.config.GroupConfig

trait PlatformIO {
  def disk: Disk

  def headerDB: HeaderDB

  def nodeStateDB: NodeStateDB

  def emptyTrie: MerklePatriciaTrie

  def txPoolCapacity: Int
}

object PlatformIO {
  def init(rootPath: Path, dbFolder: String, dbName: String, writeOptions: WriteOptions)(
      implicit config: GroupConfig): (Disk, HeaderDB, NodeStateDB, MerklePatriciaTrie) = {
    val disk: Disk = Disk.createUnsafe(rootPath)
    val dbStorage = {
      val dbPath = {
        val path = rootPath.resolve(dbFolder)
        IOUtils.createDirUnsafe(path)
        path
      }
      val path = dbPath.resolve(dbName)
      RocksDBStorage.openUnsafe(path, RocksDBStorage.Compaction.HDD)
    }
    val headerDB    = HeaderDB(dbStorage, ColumnFamily.All, writeOptions)
    val nodeStateDB = NodeStateDB(dbStorage, ColumnFamily.All, writeOptions)
    val emptyTrie =
      MerklePatriciaTrie.createStateTrie(RocksDBColumn(dbStorage, ColumnFamily.Trie, writeOptions))

    (disk, headerDB, nodeStateDB, emptyTrie)
  }
}
