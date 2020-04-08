package org.alephium.flow.io

import java.nio.file.Path

import org.rocksdb.WriteOptions

import org.alephium.flow.io.RocksDBSource.ColumnFamily
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.config.GroupConfig

object Storages {
  val blockStatePostfix: Byte = 0
  val heightPostfix: Byte     = 1
  val tipsPostfix: Byte       = 2

  def createUnsafe(rootPath: Path, dbFolder: String, dbName: String, writeOptions: WriteOptions)(
      implicit config: GroupConfig): Storages = {
    val blockStorage: BlockStorage = BlockStorage.createUnsafe(rootPath)
    val dbStorage                  = createRocksDBUnsafe(rootPath, dbFolder, dbName)
    val headerStorage              = BlockHeaderStorage(dbStorage, ColumnFamily.All, writeOptions)
    val blockStateStorage          = BlockStateStorage(dbStorage, ColumnFamily.All, writeOptions)
    val nodeStateStorage           = NodeStateStorage(dbStorage, ColumnFamily.All, writeOptions)
    val emptyTrie =
      MerklePatriciaTrie.createStateTrie(
        RocksDBKeyValueStorage(dbStorage, ColumnFamily.Trie, writeOptions))

    Storages(headerStorage, blockStorage, emptyTrie, blockStateStorage, nodeStateStorage)
  }

  private def createRocksDBUnsafe(rootPath: Path,
                                  dbFolder: String,
                                  dbName: String): RocksDBSource = {
    val path = {
      val path = rootPath.resolve(dbFolder)
      IOUtils.createDirUnsafe(path)
      path
    }
    val dbPath = path.resolve(dbName)
    RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)
  }
}

final case class Storages(
    headerStorage: BlockHeaderStorage,
    blockStorage: BlockStorage,
    trie: MerklePatriciaTrie,
    blockStateStorage: BlockStateStorage,
    nodeStateStorage: NodeStateStorage
)
