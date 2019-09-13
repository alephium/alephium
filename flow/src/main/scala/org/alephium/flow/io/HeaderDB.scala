package org.alephium.flow.io

import RocksDBStorage.ColumnFamily
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.BlockHeader

object HeaderDB {
  import RocksDBStorage.Settings

  def apply(storage: RocksDBStorage,
            cf: ColumnFamily,
            writeOptions: WriteOptions = Settings.writeOptions,
            readOptions: ReadOptions   = Settings.readOptions): HeaderDB = {
    new HeaderDB(storage, cf, writeOptions, readOptions)
  }
}

class HeaderDB(val storage: RocksDBStorage,
               cf: ColumnFamily,
               writeOptions: WriteOptions,
               readOptions: ReadOptions)
    extends RocksDBColumn(storage, cf, writeOptions, readOptions) {
  def getHeaderOpt(hash: Keccak256): IOResult[Option[BlockHeader]] =
    getOpt(hash.bytes)

  def getHeaderOptUnsafe(hash: Keccak256): Option[BlockHeader] =
    getOptUnsafe[BlockHeader](hash.bytes)

  def getHeader(hash: Keccak256): IOResult[BlockHeader] =
    get[BlockHeader](hash.bytes)

  def getHeaderUnsafe(hash: Keccak256): BlockHeader =
    getUnsafe[BlockHeader](hash.bytes)

  def putHeader(header: BlockHeader): IOResult[Unit] =
    put[BlockHeader](header.hash.bytes, header)

  def putHeaderUnsafe(header: BlockHeader): Unit =
    putUnsafe[BlockHeader](header.hash.bytes, header)

  def deleteHeader(hash: Keccak256): IOResult[Unit] = {
    delete(hash.bytes)
  }

  def deleteHeaderUnsafe(hash: Keccak256): Unit = {
    deleteUnsafe(hash.bytes)
  }
}
