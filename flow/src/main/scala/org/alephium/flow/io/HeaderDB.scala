package org.alephium.flow.io

import java.nio.file.Path

import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.BlockHeader
import org.rocksdb.{Options, ReadOptions, RocksDB}

object HeaderDB {
  import RocksDBStorage.execute

  def open(path: Path, options: Options): IOResult[HeaderDB] = execute {
    openUnsafe(path, options)
  }

  def openUnsafe(path: Path, options: Options): HeaderDB = {
    RocksDB.loadLibrary()
    val db = RocksDB.open(options, path.toString)
    new HeaderDB(path, db, RocksDBStorage.readOptions)
  }
}

class HeaderDB(path: Path, db: RocksDB, readOptions: ReadOptions)
    extends RocksDBStorage(path, db, readOptions) {
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
