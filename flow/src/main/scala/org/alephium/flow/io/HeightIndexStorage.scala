package org.alephium.flow.io

import akka.util.ByteString
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.flow.io.HeightIndexStorage.hashesSerde
import org.alephium.io.{RocksDBKeyValueStorage, RocksDBSource}
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.Hash
import org.alephium.protocol.model.ChainIndex
import org.alephium.serde._
import org.alephium.util.{AVector, Bytes}

object HeightIndexStorage {
  implicit val hashesSerde: Serde[AVector[Hash]] = avectorSerde[Hash]
}

class HeightIndexStorage(
    chainIndex: ChainIndex,
    storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Int, AVector[Hash]](storage, cf, writeOptions, readOptions) {
  private val postFix =
    ByteString(chainIndex.from.value.toByte, chainIndex.to.value.toByte, Storages.heightPostfix)

  override def storageKey(key: Int): ByteString = Bytes.toBytes(key) ++ postFix
}
