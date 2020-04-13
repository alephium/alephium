package org.alephium.flow.io

import java.nio.file.Path

import org.rocksdb.WriteOptions

import org.alephium.flow.io.RocksDBSource.ColumnFamily
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.flow.trie.MerklePatriciaTrie.Node
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig

object Storages {
  val blockStatePostfix: Byte = 0
  val trieHashPostfix: Byte   = 1
  val heightPostfix: Byte     = 2
  val tipsPostfix: Byte       = 3

  trait Config {
    def blockCacheCapacity: Int
  }

  def createUnsafe(rootPath: Path, dbFolder: String, dbName: String, writeOptions: WriteOptions)(
      implicit config: GroupConfig with Config): Storages = {
    val blockStorage      = BlockStorage.createUnsafe(rootPath, config.blockCacheCapacity)
    val db                = createRocksDBUnsafe(rootPath, dbFolder, dbName)
    val headerStorage     = BlockHeaderStorage(db, ColumnFamily.Header, writeOptions)
    val blockStateStorage = BlockStateStorage(db, ColumnFamily.All, writeOptions)
    val nodeStateStorage  = NodeStateStorage(db, ColumnFamily.All, writeOptions)
    val trieStorage       = RocksDBKeyValueStorage[Hash, Node](db, ColumnFamily.Trie, writeOptions)
    val emptyTrie         = MerklePatriciaTrie.createStateTrie(trieStorage)
    val trieHashStorage   = TrieHashStorage(trieStorage, db, ColumnFamily.All, writeOptions)

    Storages(headerStorage,
             blockStorage,
             emptyTrie,
             trieHashStorage,
             blockStateStorage,
             nodeStateStorage)
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
    trieStorage: MerklePatriciaTrie,
    trieHashStorage: TrieHashStorage,
    blockStateStorage: BlockStateStorage,
    nodeStateStorage: NodeStateStorage
)
