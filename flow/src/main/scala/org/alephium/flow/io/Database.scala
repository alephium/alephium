package org.alephium.flow.io

import java.nio.file.Path

import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.{BlockHeader, TxOutput, TxOutputPoint}
import org.alephium.serde._
import org.rocksdb.{Options, RocksDB}

object Database {
  import RocksDBStorage.execute

  def open(path: Path, options: Options): IOResult[Database] = execute {
    openUnsafe(path, options)
  }

  def openUnsafe(path: Path, options: Options): Database = {
    RocksDB.loadLibrary()
    val db = RocksDB.open(options, path.toString)
    new Database(path, db)
  }

  def dESTROY(path: Path, options: Options): IOResult[Unit] = execute {
    RocksDB.destroyDB(path.toString, options)
  }

  def dESTROY(db: Database): IOResult[Unit] = execute {
    dESTROYUnsafe(db)
  }

  def dESTROYUnsafe(db: Database): Unit = {
    RocksDB.destroyDB(db.path.toString, new Options())
  }
}

class Database(path: Path, db: RocksDB) extends RocksDBStorage(path, db) {
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

  def getUTXOOpt(outputPoint: TxOutputPoint): IOResult[Option[TxOutput]] =
    getOpt[TxOutput](serialize(outputPoint))

  def getUTXOOptUnsafe(outputPoint: TxOutputPoint): Option[TxOutput] =
    getOptUnsafe[TxOutput](serialize(outputPoint))

  def getUTXO(outputPoint: TxOutputPoint): IOResult[TxOutput] =
    get[TxOutput](serialize(outputPoint))

  def getUTXOUnsafe(txOutputPoint: TxOutputPoint): TxOutput =
    getUnsafe[TxOutput](serialize(txOutputPoint))

  def putUTXO(outputPoint: TxOutputPoint, output: TxOutput): IOResult[Unit] =
    put[TxOutput](serialize(outputPoint), output)

  def putUTXOUnsafe(outputPoint: TxOutputPoint, output: TxOutput): Unit =
    putUnsafe[TxOutput](serialize(outputPoint), output)

  def deleteUTXO(txOutputPoint: TxOutputPoint): IOResult[Unit] =
    delete(serialize(txOutputPoint))

  def deleteUTXOUnsafe(txOutputPoint: TxOutputPoint): Unit =
    deleteUnsafe(serialize(txOutputPoint))
}
