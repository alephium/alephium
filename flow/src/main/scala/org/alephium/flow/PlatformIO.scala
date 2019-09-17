package org.alephium.flow

import java.nio.file.Path

import org.rocksdb.WriteOptions

import org.alephium.flow.io.{Disk, HeaderDB, RocksDBColumn, RocksDBStorage}
import org.alephium.flow.io.RocksDBStorage.ColumnFamily
import org.alephium.flow.trie.MerklePatriciaTrie

trait PlatformIO {
  def disk: Disk

  def headerDB: HeaderDB

  def emptyTrie: MerklePatriciaTrie
}

object PlatformIO {
  def init(rootPath: Path,
           dbFolder: String,
           dbName: String,
           writeOptions: WriteOptions): (Disk, HeaderDB, MerklePatriciaTrie) = {
    val disk: Disk = Disk.createUnsafe(rootPath)
    val dbStorage = {
      val dbPath = {
        val path = rootPath.resolve(dbFolder)
        Disk.createDirUnsafe(path)
        path
      }
      val path = dbPath.resolve(dbName)
      RocksDBStorage.openUnsafe(path, RocksDBStorage.Compaction.HDD)
    }
    val headerDB: HeaderDB = HeaderDB(dbStorage, ColumnFamily.All, writeOptions)
    val emptyTrie: MerklePatriciaTrie =
      MerklePatriciaTrie.createStateTrie(RocksDBColumn(dbStorage, ColumnFamily.Trie, writeOptions))

    (disk, headerDB, emptyTrie)
  }
}
