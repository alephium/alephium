package org.alephium.flow

import org.alephium.flow.io.{Disk, HeaderDB, RocksDBStorage}
import org.alephium.flow.trie.MerklePatriciaTrie

trait PlatformIO {
  def disk: Disk

  def dbStorage: RocksDBStorage

  def headerDB: HeaderDB

  def emptyTrie: MerklePatriciaTrie
}
