package org.alephium.flow.io

import akka.util.ByteString
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.flow.io.HeightIndexStorage.hashesSerde
import org.alephium.flow.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.ALF.Hash
import org.alephium.serde._
import org.alephium.util.{AVector, Bits}

object HeightIndexStorage extends RocksDBKeyValueCompanion[HeightIndexStorage] {
  def apply(storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): HeightIndexStorage = {
    new HeightIndexStorage(storage, cf, writeOptions, readOptions)
  }

  implicit val hashesSerde: Serde[AVector[Hash]] = avectorSerde[Hash]
}

class HeightIndexStorage(
    storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Int, AVector[Hash]](storage, cf, writeOptions, readOptions) {
  override def storageKey(key: Int): ByteString = Bits.toBytes(key) :+ Storages.heightPostfix
}
