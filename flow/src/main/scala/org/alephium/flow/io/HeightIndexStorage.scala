package org.alephium.flow.io

import akka.util.ByteString
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.flow.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.ALF.Hash
import org.alephium.util.Bits

object HeightIndexStorage extends RocksDBKeyValueCompanion[HeightIndexStorage] {
  def apply(storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): HeightIndexStorage = {
    new HeightIndexStorage(storage, cf, writeOptions, readOptions)
  }
}

class HeightIndexStorage(
    storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Int, Hash](storage, cf, writeOptions, readOptions) {
  override def storageKey(key: Int): ByteString = Bits.toBytes(key) :+ Storages.heightPostfix
}
