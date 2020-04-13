package org.alephium.flow.io

import akka.util.ByteString
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.flow.io.RocksDBSource.{ColumnFamily, Settings}
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.ALF.Hash

object TrieHashStorage {
  def apply(trieStorage: KeyValueStorage[Hash, MerklePatriciaTrie.Node],
            storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions): TrieHashStorage = {
    new TrieHashStorage(trieStorage, storage, cf, writeOptions, Settings.readOptions)
  }

  def apply(trieStorage: KeyValueStorage[Hash, MerklePatriciaTrie.Node],
            storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): TrieHashStorage = {
    new TrieHashStorage(trieStorage, storage, cf, writeOptions, readOptions)
  }
}

class TrieHashStorage(
    trieStorage: KeyValueStorage[Hash, MerklePatriciaTrie.Node],
    storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Hash, Hash](storage, cf, writeOptions, readOptions) {
  override def storageKey(key: Hash): ByteString = key.bytes :+ Storages.trieHashPostfix

  def getTrie(hash: Hash): IOResult[MerklePatriciaTrie] = {
    get(hash).map(MerklePatriciaTrie(_, trieStorage))
  }

  def putTrie(hash: Hash, trie: MerklePatriciaTrie): IOResult[Unit] = {
    put(hash, trie.rootHash)
  }
}
