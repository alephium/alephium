package org.alephium.flow.io

import RocksDBStorage.{ColumnFamily, Settings}
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.BlockHeader

object HeaderDB {
  def apply(storage: RocksDBStorage, cf: ColumnFamily): HeaderDB =
    apply(storage, cf, Settings.writeOptions, Settings.readOptions)

  def apply(storage: RocksDBStorage, cf: ColumnFamily, writeOptions: WriteOptions): HeaderDB =
    apply(storage, cf, writeOptions, Settings.readOptions)

  def apply(storage: RocksDBStorage,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): HeaderDB = {
    new HeaderDB(storage, cf, writeOptions, readOptions)
  }
}

class HeaderDB(val storage: RocksDBStorage,
               cf: ColumnFamily,
               writeOptions: WriteOptions,
               readOptions: ReadOptions)
    extends RocksDBColumn(storage, cf, writeOptions, readOptions) {
  def getHeaderOpt(hash: Hash): IOResult[Option[BlockHeader]] =
    getOpt(hash.bytes)

  def getHeaderOptUnsafe(hash: Hash): Option[BlockHeader] =
    getOptUnsafe[BlockHeader](hash.bytes)

  def getHeader(hash: Hash): IOResult[BlockHeader] =
    get[BlockHeader](hash.bytes)

  def getHeaderUnsafe(hash: Hash): BlockHeader =
    getUnsafe[BlockHeader](hash.bytes)

  def putHeader(header: BlockHeader): IOResult[Unit] =
    put[BlockHeader](header.hash.bytes, header)

  def putHeaderUnsafe(header: BlockHeader): Unit =
    putUnsafe[BlockHeader](header.hash.bytes, header)

  def deleteHeader(hash: Hash): IOResult[Unit] = {
    delete(hash.bytes)
  }

  def deleteHeaderUnsafe(hash: Hash): Unit = {
    deleteUnsafe(hash.bytes)
  }
}
