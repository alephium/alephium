package org.alephium.flow.io

import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.BlockHeader

import RocksDBStorage.ColumnFamily

object HeaderDB {
  def apply(storage: RocksDBStorage, cf: ColumnFamily): HeaderDB = {
    new HeaderDB(storage, cf)
  }
}

class HeaderDB(val storage: RocksDBStorage, cf: ColumnFamily) extends RocksDBColumn(storage, cf) {
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
