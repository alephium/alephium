package org.alephium.flow.io

import akka.util.ByteString
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.flow.io.RocksDBSource.{ColumnFamily, Settings}
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.ALF.Hash

trait TrieHashStorage extends KeyValueStorage[Hash, Hash] {
  val trieStorage: KeyValueStorage[Hash, MerklePatriciaTrie.Node]

  override def storageKey(key: Hash): ByteString = key.bytes ++ ByteString(Storages.trieHashPostfix)

  def getTrie(hash: Hash): IOResult[MerklePatriciaTrie] = {
    get(hash).map(MerklePatriciaTrie(_, trieStorage))
  }

  def putTrie(hash: Hash, trie: MerklePatriciaTrie): IOResult[Unit] = {
    put(hash, trie.rootHash)
  }
}

object TrieHashRockDBStorage {
  def apply(trieStorage: KeyValueStorage[Hash, MerklePatriciaTrie.Node],
            storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions): TrieHashRockDBStorage = {
    new TrieHashRockDBStorage(trieStorage, storage, cf, writeOptions, Settings.readOptions)
  }

  def apply(trieStorage: KeyValueStorage[Hash, MerklePatriciaTrie.Node],
            storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): TrieHashRockDBStorage = {
    new TrieHashRockDBStorage(trieStorage, storage, cf, writeOptions, readOptions)
  }
}

class TrieHashRockDBStorage(
    val trieStorage: KeyValueStorage[Hash, MerklePatriciaTrie.Node],
    storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Hash, Hash](storage, cf, writeOptions, readOptions)
    with TrieHashStorage
